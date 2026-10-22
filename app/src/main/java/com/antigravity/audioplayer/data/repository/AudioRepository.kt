package com.antigravity.audioplayer.data.repository

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

            // 2. Scan only the user-selected folders
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

            val scannedList = mutableListOf<AudioFileMetadata>()

            for (folderUri in savedFolders) {
                try {
                    val rootDir = DocumentFile.fromTreeUri(context, folderUri)
                    if (rootDir != null && rootDir.isDirectory) {
                        scanDirectory(context, rootDir, scannedList)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

    private fun scanDirectory(context: Context, dir: DocumentFile, resultList: MutableList<AudioFileMetadata>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                scanDirectory(context, file, resultList)
            } else {
                val name = file.name ?: ""
                val lower = name.lowercase()
                if (lower.endsWith(".flac") || lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                    lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".aac")) {
                    val lastMod = file.lastModified()
                    val metadata = extractExactAudioMetadata(
                        context = context,
                        uri = file.uri,
                        fallbackTitle = name.substringBeforeLast("."),
                        fallbackArtist = dir.name ?: "Unknown Artist",
                        fallbackAlbum = dir.name ?: "Unknown Album",
                        fallbackDuration = 0L,
                        fallbackPath = name,
                        dateAdded = if (lastMod > 0) lastMod else System.currentTimeMillis()
                    )
                    resultList.add(metadata)
                }
            }
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
        dateAdded: Long = 0L
    ): AudioFileMetadata {
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = fallbackAlbum
        var duration = fallbackDuration
        var sampleRate = 44100
        var bitDepth = 16
        var bitrate = 320
        var formatEnum = when {
            fallbackPath.endsWith(".flac", ignoreCase = true) -> AudioFormat.FLAC
            fallbackPath.endsWith(".wav", ignoreCase = true) -> AudioFormat.WAV
            fallbackPath.endsWith(".m4a", ignoreCase = true) || fallbackPath.endsWith(".aac", ignoreCase = true) -> AudioFormat.AAC
            fallbackPath.endsWith(".ogg", ignoreCase = true) -> AudioFormat.OGG
            else -> AudioFormat.MP3
        }

        var trackNum = 0
        val mmr = MediaMetadataRetriever()
        try {
            val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
            if (pfd != null) {
                pfd.use { mmr.setDataSource(it.fileDescriptor) }
            } else {
                mmr.setDataSource(context, uri)
            }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate = it / 1000 }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { trk ->
                trackNum = trk.substringBefore("/").trim().toIntOrNull() ?: 0
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
        } finally {
            try { mmr.release() } catch (e: Exception) {}
        }

        // Fallback: extract track number from filename if missing (e.g. "01 - Track.flac" or "02. Track.mp3")
        if (trackNum == 0) {
            val fileNameOnly = fallbackPath.substringAfterLast("/").substringAfterLast("\\")
            val match = Regex("^(\\d{1,3})[\\s._-]").find(fileNameOnly)
            if (match != null) {
                trackNum = match.groupValues[1].toIntOrNull() ?: 0
            }
        }

        // Extract exact sample rate and bit depth using MediaExtractor
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        val pcm = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        bitDepth = when (pcm) {
                            AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            AndroidAudioFormat.ENCODING_PCM_32BIT -> 32
                            AndroidAudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> 16
                        }
                    } else if (formatEnum == AudioFormat.FLAC || formatEnum == AudioFormat.WAV) {
                        if (sampleRate >= 48000 || fallbackPath.contains("24", ignoreCase = true)) {
                            bitDepth = 24
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { extractor.release() } catch (e: Exception) {}
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
}
