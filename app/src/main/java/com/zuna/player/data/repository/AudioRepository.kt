package com.zuna.player.data.repository

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
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.model.AudioFormat
import com.zuna.player.data.storage.LibraryPreferences
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

            val updatedList = scannedList.distinctBy { it.uri.toString() }.sortedByDescending { it.dateAdded }
            _tracks.value = updatedList
            prefs.saveCachedTracks(updatedList)
            prefs.flushTrackFirstSeenCache()
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
            "flac", "mp3", "wav", "m4a", "mp4", "ogg", "oga", "opus", "aac", "alac", "aiff", "aif", "aifc" -> true
            else -> false
        }
    }

    private data class ParsedHeaderInfo(
        val format: AudioFormat? = null,
        val sampleRate: Int = 0,
        val bitDepth: Int = 0
    )

    private fun readBe32(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)
    }

    private fun parseAiffExtended80(buf: ByteArray, off: Int): Int {
        val exp = (((buf[off].toInt() and 0x7F) shl 8) or (buf[off + 1].toInt() and 0xFF)) - 16383
        if (exp < 0 || exp > 30) return 0
        val hiMant = readBe32(buf, off + 2)
        val loMant = readBe32(buf, off + 6)
        var value = hiMant.toDouble() * Math.pow(2.0, (exp - 31).toDouble())
        value += loMant.toDouble() * Math.pow(2.0, (exp - 63).toDouble())
        val sr = value.toInt()
        return if (sr in 8000..384000) sr else 0
    }

    private fun parseMp4AlacHeader(fd: FileDescriptor): ParsedHeaderInfo? {
        return try {
            val fileSize = Os.fstat(fd).st_size
            if (fileSize < 24L) return null
            val hdr = ByteArray(16)
            var pos = 0L
            var moovOffset = -1L
            var moovSize = 0L
            while (pos + 8 <= fileSize) {
                val r = Os.pread(fd, hdr, 0, 8, pos)
                if (r < 8) break
                var sz = readBe32(hdr, 0)
                val tp = readBe32(hdr, 4)
                if (sz == 1L && pos + 16 <= fileSize) {
                    Os.pread(fd, hdr, 8, 8, pos + 8)
                    sz = (readBe32(hdr, 8) shl 32) or readBe32(hdr, 12)
                } else if (sz == 0L) {
                    sz = fileSize - pos
                }
                if (sz < 8 || pos + sz > fileSize) break
                if (tp == 0x6D6F6F76L) { // 'moov'
                    moovOffset = pos
                    moovSize = sz
                    break
                }
                pos += sz
            }
            if (moovOffset < 0 || moovSize <= 8 || moovSize > 2L * 1024L * 1024L) return null
            val moovBuf = ByteArray(moovSize.toInt())
            var readTotal = 0
            while (readTotal < moovBuf.size) {
                val n = Os.pread(fd, moovBuf, readTotal, moovBuf.size - readTotal, moovOffset + readTotal)
                if (n <= 0) break
                readTotal += n
            }
            if (readTotal < 36) return null
            // Locate 36-byte child 'alac' magic cookie box inside stsd ('alac' version/flags(4) + 24-byte config)
            for (i in 0..readTotal - 36) {
                if (moovBuf[i + 4] == 'a'.code.toByte() &&
                    moovBuf[i + 5] == 'l'.code.toByte() &&
                    moovBuf[i + 6] == 'a'.code.toByte() &&
                    moovBuf[i + 7] == 'c'.code.toByte()
                ) {
                    val boxSz = readBe32(moovBuf, i)
                    if (boxSz in 36L..48L &&
                        moovBuf[i + 8] == 0.toByte() &&
                        moovBuf[i + 9] == 0.toByte() &&
                        moovBuf[i + 10] == 0.toByte() &&
                        moovBuf[i + 11] == 0.toByte()
                    ) {
                        val bps = moovBuf[i + 17].toInt() and 0xFF
                        val ch = moovBuf[i + 21].toInt() and 0xFF
                        val sr = readBe32(moovBuf, i + 32).toInt()
                        if (ch in 1..8 && bps in listOf(16, 20, 24, 32) && sr in 8000..384000) {
                            return ParsedHeaderInfo(AudioFormat.ALAC, sr, bps)
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses FLAC / WAV / AIFF / Ogg / MP4-ALAC headers directly from FileDescriptor
     * without spawning an extra heavy MediaExtractor when unnecessary.
     */
    private fun parseHeaderInfo(fd: FileDescriptor): ParsedHeaderInfo? {
        return try {
            val header = ByteArray(96)
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
                    return ParsedHeaderInfo(AudioFormat.FLAC, sr, bps)
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
                    return ParsedHeaderInfo(AudioFormat.WAV, sr, bps)
                }
            } else if (bytesRead >= 38 &&
                header[0] == 'F'.code.toByte() &&
                header[1] == 'O'.code.toByte() &&
                header[2] == 'R'.code.toByte() &&
                header[3] == 'M'.code.toByte() &&
                header[8] == 'A'.code.toByte() &&
                header[9] == 'I'.code.toByte() &&
                header[10] == 'F'.code.toByte()
            ) {
                // Scan first chunk headers for COMM
                var pos = 12
                while (pos + 8 <= bytesRead) {
                    val cId = String(header, pos, 4, Charsets.US_ASCII)
                    val cSize = readBe32(header, pos + 4).toInt()
                    if (cId == "COMM" && pos + 26 <= bytesRead) {
                        val bps = ((header[pos + 14].toInt() and 0xFF) shl 8) or (header[pos + 15].toInt() and 0xFF)
                        val sr = parseAiffExtended80(header, pos + 16)
                        if (sr in 8000..384000 && bps in 8..64) {
                            return ParsedHeaderInfo(AudioFormat.AIFF, sr, bps)
                        }
                    }
                    if (cSize < 0) break
                    pos += 8 + cSize + (cSize and 1)
                }
            } else if (bytesRead >= 44 &&
                header[0] == 'O'.code.toByte() &&
                header[1] == 'g'.code.toByte() &&
                header[2] == 'g'.code.toByte() &&
                header[3] == 'S'.code.toByte()
            ) {
                val headStr = String(header, 0, bytesRead, Charsets.ISO_8859_1)
                val opusIdx = headStr.indexOf("OpusHead")
                if (opusIdx >= 0 && opusIdx + 16 <= bytesRead) {
                    return ParsedHeaderInfo(AudioFormat.OPUS, 48000, 16)
                }
                val vorbisIdx = headStr.indexOf("\u0001vorbis")
                if (vorbisIdx >= 0 && vorbisIdx + 16 <= bytesRead) {
                    val o = vorbisIdx + 12
                    val sr = (header[o].toInt() and 0xFF) or
                        ((header[o + 1].toInt() and 0xFF) shl 8) or
                        ((header[o + 2].toInt() and 0xFF) shl 16) or
                        ((header[o + 3].toInt() and 0xFF) shl 24)
                    if (sr in 8000..192000) {
                        return ParsedHeaderInfo(AudioFormat.OGG, sr, 16)
                    }
                }
            } else if (bytesRead >= 12 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            ) {
                return parseMp4AlacHeader(fd)
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
            fallbackPath.endsWith(".aiff", ignoreCase = true) ||
                fallbackPath.endsWith(".aif", ignoreCase = true) ||
                fallbackPath.endsWith(".aifc", ignoreCase = true) -> AudioFormat.AIFF
            fallbackPath.endsWith(".alac", ignoreCase = true) -> AudioFormat.ALAC
            fallbackPath.endsWith(".opus", ignoreCase = true) -> AudioFormat.OPUS
            fallbackPath.endsWith(".ogg", ignoreCase = true) ||
                fallbackPath.endsWith(".oga", ignoreCase = true) -> AudioFormat.OGG
            fallbackPath.endsWith(".m4a", ignoreCase = true) ||
                fallbackPath.endsWith(".mp4", ignoreCase = true) ||
                fallbackPath.endsWith(".aac", ignoreCase = true) -> AudioFormat.AAC
            else -> AudioFormat.MP3
        }

        var trackNum = 0

        try {
            val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fileDescriptor
                    var detectedFormatFromHeader: AudioFormat? = null
                    parseHeaderInfo(fd)?.let { info ->
                        if (info.sampleRate > 0) sampleRate = info.sampleRate
                        if (info.bitDepth > 0) bitDepth = info.bitDepth
                        if (info.format != null) {
                            detectedFormatFromHeader = info.format
                            formatEnum = info.format
                        }
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
                        if (detectedFormatFromHeader == null) {
                            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
                            formatEnum = when {
                                mime.contains("flac", ignoreCase = true) || fallbackPath.endsWith(".flac", ignoreCase = true) -> AudioFormat.FLAC
                                mime.contains("aiff", ignoreCase = true) || fallbackPath.endsWith(".aiff", ignoreCase = true) ||
                                    fallbackPath.endsWith(".aif", ignoreCase = true) || fallbackPath.endsWith(".aifc", ignoreCase = true) -> AudioFormat.AIFF
                                mime.contains("alac", ignoreCase = true) || fallbackPath.endsWith(".alac", ignoreCase = true) -> AudioFormat.ALAC
                                mime.contains("opus", ignoreCase = true) || fallbackPath.endsWith(".opus", ignoreCase = true) -> AudioFormat.OPUS
                                mime.contains("wav", ignoreCase = true) || fallbackPath.endsWith(".wav", ignoreCase = true) -> AudioFormat.WAV
                                mime.contains("mpeg", ignoreCase = true) || fallbackPath.endsWith(".mp3", ignoreCase = true) -> AudioFormat.MP3
                                mime.contains("ogg", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true) ||
                                    fallbackPath.endsWith(".ogg", ignoreCase = true) || fallbackPath.endsWith(".oga", ignoreCase = true) -> AudioFormat.OGG
                                mime.contains("aac", ignoreCase = true) || mime.contains("mp4", ignoreCase = true) ||
                                    fallbackPath.endsWith(".m4a", ignoreCase = true) || fallbackPath.endsWith(".aac", ignoreCase = true) -> AudioFormat.AAC
                                else -> AudioFormat.MP3
                            }
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
            bitDepth = if ((formatEnum == AudioFormat.FLAC || formatEnum == AudioFormat.WAV || formatEnum == AudioFormat.ALAC || formatEnum == AudioFormat.AIFF) &&
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
