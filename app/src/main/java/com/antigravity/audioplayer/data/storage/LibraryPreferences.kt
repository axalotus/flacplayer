package com.antigravity.audioplayer.data.storage

import android.content.Context
import android.net.Uri
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    LIGHT, DARK, AMOLED
}

class LibraryPreferences private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("vibe_player_prefs", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "cached_tracks.json")

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        } catch (e: Exception) {
            ThemeMode.DARK
        }
    )
    val themeMode = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    private val _isBlurBackground = MutableStateFlow(prefs.getBoolean("is_blur_background", true))
    val isBlurBackground = _isBlurBackground.asStateFlow()

    fun setBlurBackground(value: Boolean) {
        prefs.edit().putBoolean("is_blur_background", value).apply()
        _isBlurBackground.value = value
    }

    private val _blurRadiusPercent = MutableStateFlow(prefs.getInt("blur_radius_percent", 80))
    val blurRadiusPercent = _blurRadiusPercent.asStateFlow()

    fun setBlurRadiusPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        prefs.edit().putInt("blur_radius_percent", clamped).apply()
        _blurRadiusPercent.value = clamped
    }

    // Audio Engine Mode: 0=AAudio (default on Android 8+), 1=OpenSL ES (default on Android < 8.0), 2=Hi-Res Direct
    private val defaultAudioEngineMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 0 else 1
    private val _audioEngineMode = MutableStateFlow(prefs.getInt("audio_engine_mode", defaultAudioEngineMode))
    val audioEngineMode = _audioEngineMode.asStateFlow()

    fun setAudioEngineMode(mode: Int) {
        prefs.edit().putInt("audio_engine_mode", mode).apply()
        _audioEngineMode.value = mode
    }

    // Safe Audio Buffer Mode (auto-adapted for Android < 8.0 or weak CPUs, with manual toggle in settings)
    private val defaultSafeBufferMode = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O
    private val _isSafeBufferMode = MutableStateFlow(prefs.getBoolean("is_safe_buffer_mode", defaultSafeBufferMode))
    val isSafeBufferMode = _isSafeBufferMode.asStateFlow()

    fun setSafeBufferMode(value: Boolean) {
        prefs.edit().putBoolean("is_safe_buffer_mode", value).apply()
        _isSafeBufferMode.value = value
    }

    // Embedded Covers toggle (default true)
    private val _isEmbeddedCoversEnabled = MutableStateFlow(prefs.getBoolean("is_embedded_covers", true))
    val isEmbeddedCoversEnabled = _isEmbeddedCoversEnabled.asStateFlow()

    fun setEmbeddedCoversEnabled(value: Boolean) {
        prefs.edit().putBoolean("is_embedded_covers", value).apply()
        _isEmbeddedCoversEnabled.value = value
    }

    // Crossfade duration in seconds (0 = disabled, 1..10s)
    private val _crossfadeSeconds = MutableStateFlow(prefs.getInt("crossfade_seconds", 0).coerceIn(0, 10))
    val crossfadeSeconds = _crossfadeSeconds.asStateFlow()

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 10)
        prefs.edit().putInt("crossfade_seconds", clamped).apply()
        _crossfadeSeconds.value = clamped
    }

    // ReplayGain loudness normalization toggle
    private val _isReplayGainEnabled = MutableStateFlow(prefs.getBoolean("replay_gain_enabled", false))
    val isReplayGainEnabled = _isReplayGainEnabled.asStateFlow()

    fun setReplayGainEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("replay_gain_enabled", enabled).apply()
        _isReplayGainEnabled.value = enabled
    }

    // Gapless playback toggle
    private val _isGaplessEnabled = MutableStateFlow(prefs.getBoolean("gapless_enabled", true))
    val isGaplessEnabled = _isGaplessEnabled.asStateFlow()

    fun setGaplessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("gapless_enabled", enabled).apply()
        _isGaplessEnabled.value = enabled
    }

    // Show track numbers in library lists
    private val _isShowTrackNumbers = MutableStateFlow(prefs.getBoolean("show_track_numbers", false))
    val isShowTrackNumbers = _isShowTrackNumbers.asStateFlow()

    fun setShowTrackNumbers(enabled: Boolean) {
        prefs.edit().putBoolean("show_track_numbers", enabled).apply()
        _isShowTrackNumbers.value = enabled
    }

    // Compact track list mode for large libraries
    private val _isCompactMode = MutableStateFlow(prefs.getBoolean("compact_mode", false))
    val isCompactMode = _isCompactMode.asStateFlow()

    fun setCompactMode(enabled: Boolean) {
        prefs.edit().putBoolean("compact_mode", enabled).apply()
        _isCompactMode.value = enabled
    }

    // Bluetooth / Headset auto-pause and auto-resume toggles
    private val _isAutoPauseOnDisconnect = MutableStateFlow(prefs.getBoolean("auto_pause_on_disconnect", true))
    val isAutoPauseOnDisconnect = _isAutoPauseOnDisconnect.asStateFlow()

    fun setAutoPauseOnDisconnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_pause_on_disconnect", enabled).apply()
        _isAutoPauseOnDisconnect.value = enabled
    }

    private val _isAutoResumeOnConnect = MutableStateFlow(prefs.getBoolean("auto_resume_on_connect", true))
    val isAutoResumeOnConnect = _isAutoResumeOnConnect.asStateFlow()

    fun setAutoResumeOnConnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_resume_on_connect", enabled).apply()
        _isAutoResumeOnConnect.value = enabled
    }

    // Dedicated Lyrics Folder Uri
    private val _lyricsFolderUri = MutableStateFlow<String?>(prefs.getString("lyrics_folder_uri", null))
    val lyricsFolderUri = _lyricsFolderUri.asStateFlow()

    fun setLyricsFolderUri(uri: Uri?) {
        if (uri != null) {
            prefs.edit().putString("lyrics_folder_uri", uri.toString()).apply()
            _lyricsFolderUri.value = uri.toString()
        } else {
            prefs.edit().remove("lyrics_folder_uri").apply()
            _lyricsFolderUri.value = null
        }
    }

    // Automated LRCLIB Online Lyrics
    private val _isOnlineLyricsEnabled = MutableStateFlow(prefs.getBoolean("is_online_lyrics_enabled", true))
    val isOnlineLyricsEnabled = _isOnlineLyricsEnabled.asStateFlow()

    fun setOnlineLyricsEnabled(value: Boolean) {
        prefs.edit().putBoolean("is_online_lyrics_enabled", value).apply()
        _isOnlineLyricsEnabled.value = value
    }

    // Manual vertical centering offset for active lyric line (-150dp to +150dp, default 0 = dead center)
    private val _lyricsVerticalOffsetDp = MutableStateFlow(prefs.getInt("lyrics_vertical_offset_dp", 0))
    val lyricsVerticalOffsetDp = _lyricsVerticalOffsetDp.asStateFlow()

    fun setLyricsVerticalOffsetDp(offset: Int) {
        val clamped = offset.coerceIn(-150, 150)
        prefs.edit().putInt("lyrics_vertical_offset_dp", clamped).apply()
        _lyricsVerticalOffsetDp.value = clamped
    }

    // OTA Update Settings
    private val _otaGithubRepo = MutableStateFlow(prefs.getString("ota_github_repo", "axalotus/flacplayer") ?: "axalotus/flacplayer")
    val otaGithubRepo = _otaGithubRepo.asStateFlow()

    fun setOtaGithubRepo(repo: String) {
        val trimmed = repo.trim()
        prefs.edit().putString("ota_github_repo", trimmed).apply()
        _otaGithubRepo.value = trimmed
    }

    private val _otaGithubToken = MutableStateFlow(prefs.getString("ota_github_token", "") ?: "")
    val otaGithubToken = _otaGithubToken.asStateFlow()

    fun setOtaGithubToken(token: String) {
        val trimmed = token.trim()
        prefs.edit().putString("ota_github_token", trimmed).apply()
        _otaGithubToken.value = trimmed
    }

    private val _otaAutoCheck = MutableStateFlow(prefs.getBoolean("ota_auto_check", true))
    val otaAutoCheck = _otaAutoCheck.asStateFlow()

    fun setOtaAutoCheck(value: Boolean) {
        prefs.edit().putBoolean("ota_auto_check", value).apply()
        _otaAutoCheck.value = value
    }

    fun getOrSetTrackFirstSeenTime(trackId: String): Long {
        val key = "first_seen_$trackId"
        val existing = prefs.getLong(key, 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        prefs.edit().putLong(key, now).apply()
        return now
    }

    fun getSavedFolderUris(): List<Uri> {
        val stringSet = prefs.getStringSet("folder_uris", emptySet()) ?: emptySet()
        return stringSet.mapNotNull {
            try { Uri.parse(it) } catch (e: Exception) { null }
        }
    }

    fun saveFolderUri(uri: Uri) {
        val current = getSavedFolderUris().map { it.toString() }.toMutableSet()
        current.add(uri.toString())
        prefs.edit().putStringSet("folder_uris", current).apply()
    }

    fun removeFolderUri(uri: Uri) {
        val current = getSavedFolderUris().map { it.toString() }.toMutableSet()
        current.remove(uri.toString())
        prefs.edit().putStringSet("folder_uris", current).apply()
    }

    // Recently Played Tracks History (Ordered by most recent)
    private val _recentlyPlayedIds = MutableStateFlow<List<String>>(
        try {
            val raw = prefs.getString("recently_played_ids", "") ?: ""
            if (raw.isNotBlank()) raw.split(",") else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    )
    val recentlyPlayedIds = _recentlyPlayedIds.asStateFlow()

    fun recordTrackPlayed(trackId: String) {
        if (trackId.isBlank()) return
        val current = _recentlyPlayedIds.value.toMutableList()
        current.remove(trackId)
        current.add(0, trackId)
        val trimmed = current.take(50)
        prefs.edit().putString("recently_played_ids", trimmed.joinToString(",")).apply()
        _recentlyPlayedIds.value = trimmed
    }

    // Hidden Tracks Management
    private val _hiddenTrackIds = MutableStateFlow<Set<String>>(
        prefs.getStringSet("hidden_track_ids", emptySet()) ?: emptySet()
    )
    val hiddenTrackIds = _hiddenTrackIds.asStateFlow()

    fun hideTrack(trackId: String) {
        if (trackId.isBlank()) return
        val current = _hiddenTrackIds.value.toMutableSet()
        current.add(trackId)
        prefs.edit().putStringSet("hidden_track_ids", current).apply()
        _hiddenTrackIds.value = current
    }

    fun unhideTrack(trackId: String) {
        if (trackId.isBlank()) return
        val current = _hiddenTrackIds.value.toMutableSet()
        current.remove(trackId)
        prefs.edit().putStringSet("hidden_track_ids", current).apply()
        _hiddenTrackIds.value = current
    }

    fun unhideAllTracks() {
        prefs.edit().putStringSet("hidden_track_ids", emptySet()).apply()
        _hiddenTrackIds.value = emptySet()
    }

    fun isTrackHidden(trackId: String): Boolean = _hiddenTrackIds.value.contains(trackId)

    // Last Played Playback State Persistence
    fun saveLastPlaybackState(trackId: String, progressMs: Long, queueIds: List<String>) {
        if (trackId.isBlank()) return
        prefs.edit()
            .putString("last_played_track_id", trackId)
            .putLong("last_played_progress_ms", progressMs)
            .putString("last_queue_track_ids", queueIds.joinToString(","))
            .apply()
    }

    fun saveLastProgress(progressMs: Long) {
        prefs.edit().putLong("last_played_progress_ms", progressMs).apply()
    }

    fun getLastPlayedTrackId(): String? = prefs.getString("last_played_track_id", null)

    fun getLastPlayedProgressMs(): Long = prefs.getLong("last_played_progress_ms", 0L)

    fun getLastQueueTrackIds(): List<String> {
        val raw = prefs.getString("last_queue_track_ids", "") ?: ""
        return if (raw.isNotBlank()) raw.split(",") else emptyList()
    }

    // Equalizer State Persistence
    fun saveEqualizerState(isEnabled: Boolean, bassBoostStrength: Int, presetName: String, bandGains: List<Float>) {
        prefs.edit()
            .putBoolean("eq_enabled", isEnabled)
            .putInt("eq_bass_boost", bassBoostStrength)
            .putString("eq_preset_name", presetName)
            .putString("eq_band_gains", bandGains.joinToString(","))
            .apply()
    }

    fun getEqualizerEnabled(): Boolean = prefs.getBoolean("eq_enabled", true)

    fun getEqualizerBassBoost(): Int = prefs.getInt("eq_bass_boost", 350)

    fun getEqualizerPresetName(): String = prefs.getString("eq_preset_name", "Flat") ?: "Flat"

    fun getEqualizerBandGains(): List<Float>? {
        val raw = prefs.getString("eq_band_gains", null) ?: return null
        return try {
            raw.split(",").map { it.toFloat() }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCustomEqPresets(json: String) {
        prefs.edit().putString("custom_eq_presets_json", json).apply()
    }

    fun getCustomEqPresetsJson(): String = prefs.getString("custom_eq_presets_json", "[]") ?: "[]"

    fun saveCachedTracks(tracks: List<AudioFileMetadata>) {
        try {
            val jsonArray = JSONArray()
            for (track in tracks) {
                val obj = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationMs", track.durationMs)
                    put("uri", track.uri.toString())
                    put("format", track.format.name)
                    put("sampleRateHz", track.sampleRateHz)
                    put("bitDepth", track.bitDepth)
                    put("bitrateKbps", track.bitrateKbps)
                    put("channels", track.channels)
                    put("trackNumber", track.trackNumber)
                    put("isStream", track.isStream)
                    put("streamServerUrl", track.streamServerUrl ?: "")
                    put("dateAdded", track.dateAdded)
                }
                jsonArray.put(obj)
            }
            cacheFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCachedTracks(): List<AudioFileMetadata> {
        if (!cacheFile.exists()) return emptyList()
        val list = mutableListOf<AudioFileMetadata>()
        try {
            val jsonString = cacheFile.readText()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val formatStr = obj.optString("format", "MP3")
                val formatEnum = try { AudioFormat.valueOf(formatStr) } catch (e: Exception) { AudioFormat.MP3 }

                list.add(
                    AudioFileMetadata(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        album = obj.getString("album"),
                        durationMs = obj.getLong("durationMs"),
                        uri = Uri.parse(obj.getString("uri")),
                        format = formatEnum,
                        sampleRateHz = obj.getInt("sampleRateHz"),
                        bitDepth = obj.getInt("bitDepth"),
                        bitrateKbps = obj.getInt("bitrateKbps"),
                        channels = obj.optInt("channels", 2),
                        trackNumber = obj.optInt("trackNumber", 0),
                        isStream = obj.optBoolean("isStream", false),
                        streamServerUrl = obj.optString("streamServerUrl").takeIf { it.isNotBlank() },
                        dateAdded = obj.optLong("dateAdded", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    companion object {
        @Volatile
        private var INSTANCE: LibraryPreferences? = null

        fun getInstance(context: Context): LibraryPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
