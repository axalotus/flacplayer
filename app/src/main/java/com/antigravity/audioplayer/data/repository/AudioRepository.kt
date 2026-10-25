package com.antigravity.audioplayer.data.repository

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.FileDescriptor

interface AudioRepository {
    val tracksFlow: Flow<List<AudioFileMetadata>>
    val isLoadingFlow: Flow<Boolean>
    suspend fun loadTracks(context: Context)
    suspend fun rescanLibrary(context: Context): Int
    suspend fun addNavidromeTrack(context: Context, track: AudioFileMetadata)
    suspend fun scanCustomFolder(context: Context, folderUri: Uri)
    suspend fun removeFolder(context: Context, folderUri: Uri)
}

class AudioRepositoryImpl : AudioRepository {

    private val _tracks = MutableStateFlow<List<AudioFileMetadata>>(emptyList())
    override val tracksFlow: Flow<List<AudioFileMetadata>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoadingFlow: Flow<Boolean> = _isLoading.asStateFlow()

    override suspend fun loadTracks(context: Context) {
        withContext(Dispatchers.IO) {
            val prefs = LibraryPreferences.getInstance(context)
            val savedFolders = prefs.getSavedFolderUris()

            // If no user folders are selected, library must be completely empty (no mock/demo tracks)
            if (savedFolders.isEmpty()) {
                _tracks.value = emptyList()
                prefs.saveCachedTracks(emptyList())
                _isLoading.value = false
                return@withContext
            }

            // 1. Instant load valid cached tracks for saved folders
            val cached = prefs.getCachedTracks().filter {
                !it.id.startsWith("demo_") && !it.id.startsWith("navidrome_demo_")
            }
            if (cached.isNotEmpty()) {
                _tracks.value = cached
            }

            // 2. Incrementally scan user-selected folders in background (reusing cached metadata for known URIs)
            rescanLibrary(context)
        }
    }

    override suspend fun rescanLibrary(context: Context): Int {
        return withContext(Dispatchers.IO) {
            _isLoading.value = true
            val prefs = LibraryPreferences.getInstance(context)
            val savedFolders = prefs.getSavedFolderUris()

            if (savedFolders.isEmpty()) {
                _tracks.value = emptyList()
                prefs.saveCachedTracks(emptyList())
                _isLoading.value = false
                return@withContext 0
            }

            // Build fast lookup map of already cached tracks by URI to avoid re-parsing unchanged files
            val cachedByUri = HashMap<String, AudioFileMetadata>()
            for (track in prefs.getCachedTracks()) {
                if (!track.id.startsWith("demo_") && !track.id.startsWith("navidrome_demo_") && track.durationMs > 0L) {
                    cachedByUri[track.uri.toString()] = track
                }
            }
            for (track in _tracks.value) {
                if (!track.id.startsWith("demo_") && !track.id.startsWith("navidrome_demo_") && track.durationMs > 0L) {
                    cachedByUri[track.uri.toString()] = track
                }
            }

            val scannedList = mutableListOf<AudioFileMetadata>()
            // Reuse a single MediaMetadataRetriever instance across the entire scan pass
            val sharedMmr = MediaMetadataRetriever()

            try {
                for (folderUri in savedFolders) {
                    try {
                        val treeDocId = try {
                            DocumentsContract.getTreeDocumentId(folderUri)
                        } catch (_: Exception) {
                            null
                        }

                        if (treeDocId != null) {
                            val folderName = folderUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Unknown"
                            scanTreeWithCursor(
                                context = context,
                                treeUri = folderUri,
                                parentDocId = treeDocId,
                                parentFolderName = folderName,
                                prefs = prefs,
                                cachedByUri = cachedByUri,
                                sharedMmr = sharedMmr,
                                resultList = scannedList
                            )
                        } else {
                            val rootDir = DocumentFile.fromTreeUri(context, folderUri)
                            if (rootDir != null && rootDir.isDirectory) {
                                scanDirectoryFallback(
                                    context = context,
                                    dir = rootDir,
                                    prefs = prefs,
                                    cachedByUri = cachedByUri,
                                    sharedMmr = sharedMmr,
                                    resultList = scannedList
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } finally {
                try { sharedMmr.release() } catch (_: Exception) {}
            }

            val updatedList = scannedList.distinctBy { it.id }.sortedByDescending { it.dateAdded }
            _tracks.value = updatedList
            prefs.saveCachedTracks(updatedList)
            _isLoading.value = false
            updatedList.size
        }
    }

    override suspend fun scanCustomFolder(context: Context, folderUri: Uri) {
        withContext(Dispatchers.IO) {
            _isLoading.value = true
            val prefs = LibraryPreferences.getInstance(context)
            prefs.saveFolderUri(folderUri)
            rescanLibrary(context)
        }
    }

    override suspend fun removeFolder(context: Context, folderUri: Uri) {
        withContext(Dispatchers.IO) {
            val prefs = LibraryPreferences.getInstance(context)
            prefs.removeFolderUri(folderUri)
            loadTracks(context)
        }
    }

    /**
     * Fast SAF tree traversal using DocumentsContract cursor queries (1 query per folder instead of 3N DocumentFile IPC calls).
     */
    private fun scanTreeWithCursor(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        parentFolderName: String,
        prefs: LibraryPreferences,
        cachedByUri: Map<String, AudioFileMetadata>,
        sharedMmr: MediaMetadataRetriever,
        resultList: MutableList<AudioFileMetadata>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val subDirs = mutableListOf<Pair<String, String>>()

        try {
            context.contentResolver.query(
                childrenUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = if (idIndex >= 0) cursor.getString(idIndex) else continue
                    val name = (if (nameIndex >= 0) cursor.getString(nameIndex) else null) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirs.add(docId to name)
                    } else if (isSupportedAudioFile(name)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val uriStr = fileUri.toString()
                        val cached = cachedByUri[uriStr]
                        if (cached != null) {
                            resultList.add(cached)
                        } else {
                            val metadata = extractExactAudioMetadata(
                                context = context,
                                uri = fileUri,
                                fallbackTitle = name.substringBeforeLast("."),
                                fallbackArtist = parentFolderName.ifBlank { "Unknown Artist" },
                                fallbackAlbum = parentFolderName.ifBlank { "Unknown Album" },
                                fallbackDuration = 0L,
                                fallbackPath = name,
                                dateAdded = prefs.getOrSetTrackFirstSeenTime(uriStr),
                                mmr = sharedMmr
                            )
                            resultList.add(metadata)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        for ((subDocId, subName) in subDirs) {
            scanTreeWithCursor(
                context = context,
                treeUri = treeUri,
                parentDocId = subDocId,
                parentFolderName = subName,
                prefs = prefs,
                cachedByUri = cachedByUri,
                sharedMmr = sharedMmr,
                resultList = resultList
            )
        }
    }

    private fun scanDirectoryFallback(
        context: Context,
        dir: DocumentFile,
        prefs: LibraryPreferences,
        cachedByUri: Map<String, AudioFileMetadata>,
        sharedMmr: MediaMetadataRetriever,
        resultList: MutableList<AudioFileMetadata>
    ) {
        val dirName = dir.name ?: "Unknown"
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                scanDirectoryFallback(context, file, prefs, cachedByUri, sharedMmr, resultList)
            } else {
                val name = file.name ?: ""
                if (isSupportedAudioFile(name)) {
                    val fileUri = file.uri
                    val uriStr = fileUri.toString()
                    val cached = cachedByUri[uriStr]
                    if (cached != null) {
                        resultList.add(cached)
                    } else {
                        val metadata = extractExactAudioMetadata(
                            context = context,
                            uri = fileUri,
                            fallbackTitle = name.substringBeforeLast("."),
                            fallbackArtist = dirName,
                            fallbackAlbum = dirName,
                            fallbackDuration = 0L,
                            fallbackPath = name,
                            dateAdded = prefs.getOrSetTrackFirstSeenTime(uriStr),
                            mmr = sharedMmr
                        )
                        resultList.add(metadata)
                    }
                }
            }
        }
    }

    private fun isSupportedAudioFile(name: String): Boolean {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == name.length - 1) return false
        return when (name.substring(dotIdx + 1).lowercase()) {
            "flac", "mp3", "wav", "m4a", "ogg", "aac" -> true
            else -> false
        }
    }

    /**
     * Parses FLAC STREAMINFO or WAV fmt header directly from FileDescriptor in microseconds
     * without spawning a native MediaExtractor instance.
     */
    private fun parseHeaderSampleRateAndBitDepth(fd: FileDescriptor): Pair<Int, Int>? {
        return try {
            val header = ByteArray(44)
            val bytesRead = Os.pread(fd, header, 0, header.size, 0L)
            if (bytesRead >= 26 &&
                header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte() &&
                (header[4].toInt() and 0x7F) == 0
            ) {
                // FLAC STREAMINFO starts at offset 8; sample rate (20 bits) + channels (3 bits) + bps-1 (5 bits) at offset 18..21
                val b18 = header[18].toInt() and 0xFF
                val b19 = header[19].toInt() and 0xFF
                val b20 = header[20].toInt() and 0xFF
                val b21 = header[21].toInt() and 0xFF
                val sr = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
                val bps = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1
                if (sr in 8000..384000 && bps in 8..32) {
                    return sr to bps
                }
            } else if (bytesRead >= 36 &&
                header[0] == 'R'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() &&
                header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() &&
                header[9] == 'A'.code.toByte() &&
                header[10] == 'V'.code.toByte() &&
                header[11] == 'E'.code.toByte()
            ) {
                val sr = (header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)
                val bps = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
                if (sr in 8000..384000 && bps in 8..32) {
                    return sr to bps
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractExactAudioMetadata(
        context: Context,
        uri: Uri,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDuration: Long,
        fallbackPath: String,
        dateAdded: Long = 0L,
        mmr: MediaMetadataRetriever
    ): AudioFileMetadata {
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = fallbackAlbum
        var duration = fallbackDuration
        var sampleRate = 0
        var bitDepth = 0
        var bitrate = 320
        var formatEnum = when {
            fallbackPath.endsWith(".flac", ignoreCase = true) -> AudioFormat.FLAC
            fallbackPath.endsWith(".wav", ignoreCase = true) -> AudioFormat.WAV
            fallbackPath.endsWith(".m4a", ignoreCase = true) || fallbackPath.endsWith(".aac", ignoreCase = true) -> AudioFormat.AAC
            fallbackPath.endsWith(".ogg", ignoreCase = true) -> AudioFormat.OGG
            else -> AudioFormat.MP3
        }

        var trackNum = 0

        try {
            val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fileDescriptor
                    // Fast header check for FLAC / WAV (zero native MediaExtractor overhead)
                    parseHeaderSampleRateAndBitDepth(fd)?.let { (sr, bps) ->
                        sampleRate = sr
                        bitDepth = bps
                    }

                    try {
                        mmr.setDataSource(fd)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate = it / 1000 }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { trk ->
                            trackNum = trk.substringBefore("/").trim().toIntOrNull() ?: 0
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (sampleRate <= 0) {
                                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let { sampleRate = it }
                            }
                            if (bitDepth <= 0) {
                                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.let { bitDepth = it }
                            }
                        }
                        val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
                        formatEnum = when {
                            mime.contains("flac", ignoreCase = true) || fallbackPath.endsWith(".flac", ignoreCase = true) -> AudioFormat.FLAC
                            mime.contains("wav", ignoreCase = true) || fallbackPath.endsWith(".wav", ignoreCase = true) -> AudioFormat.WAV
                            mime.contains("mpeg", ignoreCase = true) || fallbackPath.endsWith(".mp3", ignoreCase = true) -> AudioFormat.MP3
                            mime.contains("aac", ignoreCase = true) || fallbackPath.endsWith(".m4a", ignoreCase = true) -> AudioFormat.AAC
                            mime.contains("ogg", ignoreCase = true) || fallbackPath.endsWith(".ogg", ignoreCase = true) -> AudioFormat.OGG
                            else -> AudioFormat.MP3
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Fallback to MediaExtractor ONLY if sampleRate is still unknown (e.g. non-FLAC/WAV on Android < 12)
                    if (sampleRate <= 0) {
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(fd)
                            for (i in 0 until extractor.trackCount) {
                                val format = extractor.getTrackFormat(i)
                                val mime = format.getString(MediaFormat.KEY_MIME)
                                if (mime?.startsWith("audio/") == true) {
                                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    }
                                    if (bitDepth <= 0 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                        val pcm = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        bitDepth = when (pcm) {
                                            AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                            AndroidAudioFormat.ENCODING_PCM_32BIT -> 32
                                            AndroidAudioFormat.ENCODING_PCM_FLOAT -> 32
                                            else -> 16
                                        }
                                    }
                                    break
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { extractor.release() } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate = it / 1000 }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { trk ->
                    trackNum = trk.substringBefore("/").trim().toIntOrNull() ?: 0
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let { sampleRate = it }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.let { bitDepth = it }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (sampleRate <= 0) sampleRate = 44100
        if (bitDepth <= 0) {
            bitDepth = if ((formatEnum == AudioFormat.FLAC || formatEnum == AudioFormat.WAV) &&
                (sampleRate >= 48000 || fallbackPath.contains("24", ignoreCase = true))
            ) {
                24
            } else {
                16
            }
        }

        // Fallback: extract track number from filename if missing (e.g. "01 - Track.flac" or "02. Track.mp3")
        if (trackNum == 0) {
            val fileNameOnly = fallbackPath.substringAfterLast("/").substringAfterLast("\\")
            val match = TRACK_NUMBER_REGEX.find(fileNameOnly)
            if (match != null) {
                trackNum = match.groupValues[1].toIntOrNull() ?: 0
            }
        }

        return AudioFileMetadata(
            id = "folder_${uri.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            uri = uri,
            format = formatEnum,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrate,
            trackNumber = trackNum,
            dateAdded = dateAdded
        )
    }

    override suspend fun addNavidromeTrack(context: Context, track: AudioFileMetadata) {
        val prefs = LibraryPreferences.getInstance(context)
        val current = _tracks.value.toMutableList()
        current.add(0, track)
        val updated = current.distinctBy { it.id }
        _tracks.value = updated
        prefs.saveCachedTracks(updated)
    }

    companion object {
        private val TRACK_NUMBER_REGEX = Regex("^(\\d{1,3})[\\s._-]")
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }
}
