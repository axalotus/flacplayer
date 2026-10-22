package com.antigravity.audioplayer.desktop.repository

import com.antigravity.audioplayer.desktop.model.DesktopPlaylist
import com.antigravity.audioplayer.desktop.model.DesktopTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.sound.sampled.AudioSystem

object DesktopLibraryRepository {

    private val _tracks = MutableStateFlow<List<DesktopTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _playlists = MutableStateFlow<List<DesktopPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds = _favoriteIds.asStateFlow()

    private val _customLyricsDir = MutableStateFlow<String?>(null)
    val customLyricsDir = _customLyricsDir.asStateFlow()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dataDir = File(System.getProperty("user.home"), ".flacplayer")

    init {
        if (!dataDir.exists()) dataDir.mkdirs()
        loadPersistedData()
    }

    suspend fun scanFolder(folder: File) = withContext(Dispatchers.IO) {
        if (!folder.exists() || !folder.isDirectory) return@withContext

        val currentFolders = _folders.value.toMutableSet()
        currentFolders.add(folder.absolutePath)
        _folders.value = currentFolders.toList()

        val scannedTracks = mutableListOf<DesktopTrack>()
        scanDirRecursive(folder, scannedTracks)

        val merged = (_tracks.value.filter { it.filePath !in scannedTracks.map { t -> t.filePath } } + scannedTracks)
            .sortedBy { it.title.lowercase() }
        _tracks.value = merged

        savePersistedData()
    }

    fun removeFolder(folderPath: String) {
        _folders.value = _folders.value.filter { it != folderPath }
        _tracks.value = _tracks.value.filterNot { it.filePath.startsWith(folderPath) }
        savePersistedData()
    }

    fun toggleFavorite(trackId: String) {
        val favs = _favoriteIds.value.toMutableSet()
        if (favs.contains(trackId)) {
            favs.remove(trackId)
        } else {
            favs.add(trackId)
        }
        _favoriteIds.value = favs
        savePersistedData()
    }

    fun isFavorite(trackId: String): Boolean = _favoriteIds.value.contains(trackId)

    fun createPlaylist(name: String, trackIds: List<String> = emptyList()) {
        val newPl = DesktopPlaylist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            trackIds = trackIds
        )
        _playlists.value = _playlists.value + newPl
        savePersistedData()
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId && trackId !in pl.trackIds) {
                pl.copy(trackIds = pl.trackIds + trackId)
            } else pl
        }
        savePersistedData()
    }

    fun removePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filter { it.id != playlistId }
        savePersistedData()
    }

    fun setCustomLyricsDir(dirPath: String?) {
        _customLyricsDir.value = dirPath
        savePersistedData()
    }

    fun importSyncData(importedPlaylists: List<DesktopPlaylist>, importedFavorites: List<String>) {
        if (importedPlaylists.isNotEmpty()) {
            val existingIds = _playlists.value.map { it.id }.toSet()
            val newPlaylists = _playlists.value + importedPlaylists.filter { it.id !in existingIds }
            _playlists.value = newPlaylists
        }
        if (importedFavorites.isNotEmpty()) {
            _favoriteIds.value = _favoriteIds.value + importedFavorites
        }
        savePersistedData()
    }

    private fun scanDirRecursive(dir: File, result: MutableList<DesktopTrack>) {
        val files = dir.listFiles() ?: return
        val validExts = setOf("flac", "mp3", "wav", "ogg", "aac", "m4a")

        for (file in files) {
            if (file.isDirectory) {
                scanDirRecursive(file, result)
            } else if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext in validExts) {
                    val track = parseTrackInfo(file)
                    result.add(track)
                }
            }
        }
    }

    private fun parseTrackInfo(file: File): DesktopTrack {
        val fileNameWithoutExt = file.nameWithoutExtension
        var title = fileNameWithoutExt
        var artist = "Неизвестный исполнитель"
        var album = file.parentFile?.name ?: "Неизвестный альбом"
        var durationMs = 0L
        var sampleRate = 44100
        var bitDepth = 16
        var bitrate = 320

        // Parse artist - title pattern (e.g. "Daft Punk - Get Lucky")
        if (fileNameWithoutExt.contains(" - ")) {
            val parts = fileNameWithoutExt.split(" - ", limit = 2)
            artist = parts[0].trim()
            title = parts[1].trim()
        }

        // Try extracting metadata from AudioSystem
        try {
            val stream = AudioSystem.getAudioInputStream(file)
            val format = stream.format
            sampleRate = format.sampleRate.toInt().takeIf { it > 0 } ?: 44100
            bitDepth = format.sampleSizeInBits.takeIf { it > 0 } ?: 16

            val frameLength = stream.frameLength
            if (frameLength > 0 && sampleRate > 0) {
                durationMs = (frameLength * 1000L) / sampleRate
            }
            stream.close()
        } catch (_: Exception) {
            // Rough fallback by file size
            if (file.extension.equals("mp3", true)) {
                durationMs = (file.length() * 8 / 320)
            } else if (file.extension.equals("flac", true)) {
                durationMs = (file.length() * 8 / 800)
                bitDepth = 24
                sampleRate = 48000
            }
        }

        return DesktopTrack(
            id = file.absolutePath.hashCode().toString(),
            filePath = file.absolutePath,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs.coerceAtLeast(1000L),
            format = file.extension.uppercase(),
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrate
        )
    }

    private fun loadPersistedData() {
        try {
            val libraryFile = File(dataDir, "library.json")
            if (libraryFile.exists()) {
                val list = json.decodeFromString<List<DesktopTrack>>(libraryFile.readText())
                _tracks.value = list
            }

            val foldersFile = File(dataDir, "folders.json")
            if (foldersFile.exists()) {
                val list = json.decodeFromString<List<String>>(foldersFile.readText())
                _folders.value = list
            }

            val plFile = File(dataDir, "playlists.json")
            if (plFile.exists()) {
                val list = json.decodeFromString<List<DesktopPlaylist>>(plFile.readText())
                _playlists.value = list
            }

            val favFile = File(dataDir, "favorites.json")
            if (favFile.exists()) {
                val list = json.decodeFromString<Set<String>>(favFile.readText())
                _favoriteIds.value = list
            }

            val cfgFile = File(dataDir, "config.json")
            if (cfgFile.exists()) {
                _customLyricsDir.value = cfgFile.readText().trim().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun savePersistedData() {
        try {
            File(dataDir, "library.json").writeText(json.encodeToString(_tracks.value))
            File(dataDir, "folders.json").writeText(json.encodeToString(_folders.value))
            File(dataDir, "playlists.json").writeText(json.encodeToString(_playlists.value))
            File(dataDir, "favorites.json").writeText(json.encodeToString(_favoriteIds.value))
            File(dataDir, "config.json").writeText(_customLyricsDir.value ?: "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
