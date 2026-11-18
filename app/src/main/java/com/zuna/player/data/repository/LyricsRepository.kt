package com.zuna.player.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.model.LyricsData
import com.zuna.player.data.model.LyricsSource
import com.zuna.player.data.parser.EmbeddedLyricsExtractor
import com.zuna.player.data.parser.LyricsParser
import com.zuna.player.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset

sealed class LyricsLoadState {
    object Idle : LyricsLoadState()
    object Loading : LyricsLoadState()
    data class Success(val lyrics: LyricsData) : LyricsLoadState()
    data class NotFound(val message: String = "Текст не найден") : LyricsLoadState()
    data class NetworkError(val message: String) : LyricsLoadState()
}

object LyricsRepository {

    private const val TAG = "LyricsRepository"
    private val memoryCache = LruCache<String, LyricsData>(100)

    private val _currentLyrics = MutableStateFlow<LyricsData?>(null)
    val currentLyrics = _currentLyrics.asStateFlow()

    private val _loadState = MutableStateFlow<LyricsLoadState>(LyricsLoadState.Idle)
    val loadState = _loadState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var activeTrackId: String? = null

    suspend fun loadLyricsForTrack(context: Context, track: AudioFileMetadata?) {
        if (track == null) {
            _currentLyrics.value = null
            _loadState.value = LyricsLoadState.Idle
            _isLoading.value = false
            activeTrackId = null
            return
        }

        activeTrackId = track.id

        // Check in-memory cache first
        val cached = memoryCache.get(track.id)
        if (cached != null) {
            _currentLyrics.value = cached
            _loadState.value = LyricsLoadState.Success(cached)
            _isLoading.value = false
            return
        }

        _isLoading.value = true
        _loadState.value = LyricsLoadState.Loading

        withContext(Dispatchers.IO) {
            val state = fetchLyricsState(context, track)
            if (activeTrackId == track.id) {
                _loadState.value = state
                _isLoading.value = false
                when (state) {
                    is LyricsLoadState.Success -> {
                        memoryCache.put(track.id, state.lyrics)
                        _currentLyrics.value = state.lyrics
                    }
                    else -> {
                        _currentLyrics.value = null
                    }
                }
            }
        }
    }

    fun setManualLyrics(trackId: String, lyricsData: LyricsData) {
        memoryCache.put(trackId, lyricsData)
        if (activeTrackId == null || activeTrackId == trackId) {
            activeTrackId = trackId
            _currentLyrics.value = lyricsData
            _loadState.value = LyricsLoadState.Success(lyricsData)
            _isLoading.value = false
        }
    }

    suspend fun fetchOnlineLyricsForced(context: Context, track: AudioFileMetadata): LyricsData? {
        activeTrackId = track.id
        _isLoading.value = true
        _loadState.value = LyricsLoadState.Loading
        return withContext(Dispatchers.IO) {
            val result = com.zuna.player.data.online.LrclibLyricsProvider.fetchLyricsWithResult(context, track, forceRefresh = true)
            val state = when (result) {
                is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.Success -> {
                    memoryCache.put(track.id, result.lyrics)
                    LyricsLoadState.Success(result.lyrics)
                }
                is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.NotFound -> {
                    LyricsLoadState.NotFound("LRCLIB не содержит подходящего текста для этой композиции")
                }
                is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.NetworkError -> {
                    LyricsLoadState.NetworkError(result.message)
                }
            }

            if (activeTrackId == track.id) {
                _loadState.value = state
                _isLoading.value = false
                _currentLyrics.value = (state as? LyricsLoadState.Success)?.lyrics
            }
            (state as? LyricsLoadState.Success)?.lyrics
        }
    }

    private suspend fun fetchLyricsState(context: Context, track: AudioFileMetadata): LyricsLoadState {
        // 1. Local sources (Strict Priority: Embedded -> Sidecar -> Folder -> MMR)
        val local = fetchLocalLyrics(context, track)
        if (local != null) {
            return LyricsLoadState.Success(local)
        }

        // 2. Automated Online LRCLIB Fetch (if enabled in settings)
        val prefs = LibraryPreferences.getInstance(context)
        if (prefs.isOnlineLyricsEnabled.value) {
            try {
                return when (val onlineResult = com.zuna.player.data.online.LrclibLyricsProvider.fetchLyricsWithResult(context, track)) {
                    is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.Success -> {
                        Log.d(TAG, "Found online lyrics for ${track.title} via LRCLIB")
                        LyricsLoadState.Success(onlineResult.lyrics)
                    }
                    is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.NotFound -> {
                        LyricsLoadState.NotFound("LRCLIB не содержит подходящего текста для этой композиции")
                    }
                    is com.zuna.player.data.online.LrclibLyricsProvider.LrclibResult.NetworkError -> {
                        LyricsLoadState.NetworkError(onlineResult.message)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching online lyrics for ${track.title}", e)
                return LyricsLoadState.NetworkError(e.message ?: "Ошибка соединения")
            }
        }

        return LyricsLoadState.NotFound("Локальный текст не найден")
    }

    private fun fetchLocalLyrics(context: Context, track: AudioFileMetadata): LyricsData? {
        // 1. Embedded Metadata (FLAC Vorbis comments / ID3 USLT / SYLT / M4A)
        try {
            val embeddedLyrics = EmbeddedLyricsExtractor.extract(context, track)
            if (embeddedLyrics != null) {
                Log.d(TAG, "Found embedded lyrics for ${track.title} via EmbeddedLyricsExtractor")
                return embeddedLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in EmbeddedLyricsExtractor for ${track.title}", e)
        }

        // 2. Sibling files in the same folder
        try {
            val sidecar = findSidecarLyrics(context, track)
            if (sidecar != null) {
                Log.d(TAG, "Found sidecar lyrics for ${track.title}")
                return sidecar
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding sidecar lyrics for ${track.title}", e)
        }

        // 3. Dedicated Custom Lyrics Folder
        try {
            val folderLyrics = findCustomFolderLyrics(context, track)
            if (folderLyrics != null) {
                Log.d(TAG, "Found custom folder lyrics for ${track.title}")
                return folderLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in custom lyrics folder for ${track.title}", e)
        }

        // 4. MediaMetadataRetriever fallback
        try {
            val mmr = MediaMetadataRetriever()
            val pfd = try { context.contentResolver.openFileDescriptor(track.uri, "r") } catch (_: Exception) { null }
            if (pfd != null) {
                pfd.use { mmr.setDataSource(it.fileDescriptor) }
            } else {
                mmr.setDataSource(context, track.uri)
            }

            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)?.takeIf { it.contains("\n") || it.length > 50 }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)?.takeIf { it.contains("\n") }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.contains("[") && it.contains("]") }

            if (!raw.isNullOrBlank()) {
                return LyricsParser.parse(raw, LyricsSource.EMBEDDED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting with MMR fallback", e)
        }

        return null
    }

    private fun findSidecarLyrics(context: Context, track: AudioFileMetadata): LyricsData? {
        val uri = track.uri
        if (uri.scheme == "file") {
            val audioFile = File(uri.path ?: return null)
            val parentDir = audioFile.parentFile ?: return null
            val baseName = audioFile.nameWithoutExtension

            val extensions = listOf("lrc", "txt", "LRC", "TXT")
            for (ext in extensions) {
                val f = File(parentDir, "$baseName.$ext")
                if (f.exists() && f.isFile) {
                    val bytes = f.readBytes()
                    val content = decodeText(bytes)
                    if (content.isNotBlank()) {
                        return LyricsParser.parse(content, LyricsSource.SIDECAR)
                    }
                }
            }
        } else {
            // SAF DocumentFile
            val docFile = DocumentFile.fromSingleUri(context, uri) ?: return null
            val parentDoc = docFile.parentFile
            val fileName = docFile.name ?: return null
            val baseName = fileName.substringBeforeLast(".")

            if (parentDoc != null && parentDoc.isDirectory) {
                for (sibling in parentDoc.listFiles()) {
                    val sibName = sibling.name ?: continue
                    val sibBase = sibName.substringBeforeLast(".")
                    val sibExt = sibName.substringAfterLast(".", "").lowercase()

                    if (sibBase.equals(baseName, ignoreCase = true) ||
                        sibBase.equals("${track.artist} - ${track.title}", ignoreCase = true) ||
                        sibBase.equals(track.title, ignoreCase = true)) {
                        if (sibExt == "lrc" || sibExt == "txt") {
                            val content = readUriText(context, sibling.uri)
                            if (!content.isNullOrBlank()) {
                                return LyricsParser.parse(content, LyricsSource.SIDECAR)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findCustomFolderLyrics(context: Context, track: AudioFileMetadata): LyricsData? {
        val prefs = LibraryPreferences.getInstance(context)
        val folderUriStr = prefs.lyricsFolderUri.value ?: return null
        val folderUri = try { Uri.parse(folderUriStr) } catch (_: Exception) { null } ?: return null

        val rootDir = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!rootDir.isDirectory) return null

        val targetNames = listOf(
            "${track.artist} - ${track.title}",
            "${track.title} - ${track.artist}",
            track.title,
            track.id
        )

        for (file in rootDir.listFiles()) {
            val fileName = file.name ?: continue
            val baseName = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "").lowercase()

            if (ext == "lrc" || ext == "txt") {
                for (target in targetNames) {
                    if (baseName.equals(target, ignoreCase = true) ||
                        baseName.replace("_", " ").equals(target, ignoreCase = true)) {
                        val content = readUriText(context, file.uri)
                        if (!content.isNullOrBlank()) {
                            return LyricsParser.parse(content, LyricsSource.CUSTOM_FOLDER)
                        }
                    }
                }
            }
        }
        return null
    }

    fun readUriText(context: Context, uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            decodeText(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read text from URI $uri", e)
            null
        }
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // Check UTF-8 BOM (EF BB BF)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // Check UTF-16 BE BOM (FE FF)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // Check UTF-16 LE BOM (FF FE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }

        // Try UTF-8 decoding
        try {
            val decoder = Charsets.UTF_8.newDecoder()
            val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (!decoded.contains('\uFFFD')) {
                return decoded
            }
        } catch (_: Exception) {}

        // Try Windows-1251 (Cyrillic)
        try {
            val cp1251 = Charset.forName("windows-1251")
            val decoded1251 = String(bytes, cp1251)
            if (decoded1251.any { it in '\u0400'..'\u04FF' }) {
                return decoded1251
            }
        } catch (_: Exception) {}

        return String(bytes, Charsets.UTF_8)
    }
}
