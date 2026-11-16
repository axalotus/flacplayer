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

data class FlacThemeConfig(
    val fontFamily: String = "jakarta",
    val visualizerEnabled: Boolean = true,
    val visualizerStyle: String = "bars",
    val accentColor: String = "#ffffff",
    val panelColor: String = "#0a0a0a",
    val panelBrightness: Int = 100,
    val panelOpacity: Int = 70,
    val cardOpacity: Int = 68,
    val panelBlurPx: Int = 20,
    val borderRadiusPx: Int = 12,
    val borderOpacity: Int = 10,
    val lyricsBlurIntensity: Int = 100,
    val appBgMode: String = "none", // "none" | "cover" | "custom"
    val appBgColorMode: String = "mono", // "mono" | "color"
    val appBgCustomPath: String = "",
    val appBgIsVideo: Boolean = false,
    val appBgBlurPx: Int = 48,
    val appBgDimPercent: Int = 68,
    val fsBgMode: String = "cover", // "none" | "cover" | "custom"
    val fsBgColorMode: String = "mono", // "mono" | "color"
    val fsBgCustomPath: String = "",
    val fsBgIsVideo: Boolean = false,
    val fsBgBlurPx: Int = 52,
    val fsBgDimPercent: Int = 64
) {
    fun toThemeJson(): JSONObject = JSONObject().apply {
        put("fontFamily", fontFamily)
        put("visualizerEnabled", visualizerEnabled)
        put("visualizerStyle", visualizerStyle)
        put("accentColor", accentColor)
        put("panelColor", panelColor)
        put("panelBrightness", panelBrightness)
        put("panelOpacity", panelOpacity)
        put("cardOpacity", cardOpacity)
        put("panelBlurPx", panelBlurPx)
        put("borderRadiusPx", borderRadiusPx)
        put("borderOpacity", borderOpacity)
        put("lyricsBlurIntensity", lyricsBlurIntensity)
        put("appBgMode", appBgMode)
        put("appBgColorMode", appBgColorMode)
        put("appBgIsVideo", appBgIsVideo)
        put("appBgBlurPx", appBgBlurPx)
        put("appBgDimPercent", appBgDimPercent)
        put("fsBgMode", fsBgMode)
        put("fsBgColorMode", fsBgColorMode)
        put("fsBgIsVideo", fsBgIsVideo)
        put("fsBgBlurPx", fsBgBlurPx)
        put("fsBgDimPercent", fsBgDimPercent)
    }

    companion object {
        fun fromJson(obj: JSONObject, existing: FlacThemeConfig = FlacThemeConfig()): FlacThemeConfig {
            return existing.copy(
                fontFamily = obj.optString("fontFamily", existing.fontFamily),
                visualizerEnabled = obj.optBoolean("visualizerEnabled", existing.visualizerEnabled),
                visualizerStyle = obj.optString("visualizerStyle", existing.visualizerStyle),
                accentColor = obj.optString("accentColor", existing.accentColor),
                panelColor = obj.optString("panelColor", existing.panelColor),
                panelBrightness = obj.optInt("panelBrightness", existing.panelBrightness).coerceIn(40, 220),
                panelOpacity = obj.optInt("panelOpacity", existing.panelOpacity).coerceIn(20, 100),
                cardOpacity = obj.optInt("cardOpacity", existing.cardOpacity).coerceIn(20, 100),
                panelBlurPx = obj.optInt("panelBlurPx", existing.panelBlurPx).coerceIn(0, 40),
                borderRadiusPx = obj.optInt("borderRadiusPx", existing.borderRadiusPx).coerceIn(2, 22),
                borderOpacity = obj.optInt("borderOpacity", existing.borderOpacity).coerceIn(0, 35),
                lyricsBlurIntensity = obj.optInt("lyricsBlurIntensity", existing.lyricsBlurIntensity).coerceIn(0, 180),
                appBgMode = obj.optString("appBgMode", existing.appBgMode),
                appBgColorMode = obj.optString("appBgColorMode", existing.appBgColorMode),
                appBgCustomPath = obj.optString("appBgCustomPath", existing.appBgCustomPath),
                appBgIsVideo = obj.optBoolean("appBgIsVideo", existing.appBgIsVideo),
                appBgBlurPx = obj.optInt("appBgBlurPx", existing.appBgBlurPx).coerceIn(0, 90),
                appBgDimPercent = obj.optInt("appBgDimPercent", existing.appBgDimPercent).coerceIn(15, 95),
                fsBgMode = obj.optString("fsBgMode", existing.fsBgMode),
                fsBgColorMode = obj.optString("fsBgColorMode", existing.fsBgColorMode),
                fsBgCustomPath = obj.optString("fsBgCustomPath", existing.fsBgCustomPath),
                fsBgIsVideo = obj.optBoolean("fsBgIsVideo", existing.fsBgIsVideo),
                fsBgBlurPx = obj.optInt("fsBgBlurPx", existing.fsBgBlurPx).coerceIn(0, 90),
                fsBgDimPercent = obj.optInt("fsBgDimPercent", existing.fsBgDimPercent).coerceIn(15, 95)
            )
        }
    }
}

data class ThemePresetSpec(
    val id: String,
    val name: String,
    val accentColor: String,
    val panelColor: String,
    val panelBrightness: Int,
    val panelOpacity: Int,
    val cardOpacity: Int,
    val panelBlurPx: Int,
    val borderRadiusPx: Int,
    val borderOpacity: Int
)

val DESKTOP_THEME_PRESETS = listOf(
    ThemePresetSpec("monochrome", "Monochrome", "#ffffff", "#0a0a0a", 100, 70, 68, 20, 12, 10),
    ThemePresetSpec("obsidian-crimson", "Crimson Dark", "#ff3b5c", "#140b0e", 110, 84, 80, 18, 14, 12),
    ThemePresetSpec("nordic-frost", "Nordic Frost", "#7dd3fc", "#0c131d", 115, 82, 78, 20, 14, 13),
    ThemePresetSpec("amber-studio", "Amber Studio", "#f59e0b", "#141008", 112, 88, 84, 14, 10, 12),
    ThemePresetSpec("emerald-glass", "Emerald Glass", "#34d399", "#081410", 110, 80, 76, 18, 16, 14)
)

class LibraryPreferences private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("vibe_player_prefs", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "cached_tracks.json")

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.AMOLED.name) ?: ThemeMode.AMOLED.name)
        } catch (e: Exception) {
            ThemeMode.AMOLED
        }
    )
    val themeMode = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    // Desktop-unified FlacThemeConfig state
    private val _themeConfig = MutableStateFlow(loadThemeConfig())
    val themeConfig = _themeConfig.asStateFlow()

    private fun loadThemeConfig(): FlacThemeConfig {
        val raw = prefs.getString("desktop_theme_config_json", null) ?: return FlacThemeConfig()
        return try {
            FlacThemeConfig.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            FlacThemeConfig()
        }
    }

    fun updateThemeConfig(transform: (FlacThemeConfig) -> FlacThemeConfig) {
        val next = transform(_themeConfig.value)
        _themeConfig.value = next
        val json = next.toThemeJson().apply {
            put("appBgCustomPath", next.appBgCustomPath)
            put("fsBgCustomPath", next.fsBgCustomPath)
        }
        prefs.edit().putString("desktop_theme_config_json", json.toString()).apply()
    }

    fun setThemeConfig(config: FlacThemeConfig) {
        updateThemeConfig { config }
    }

    // Optional Quality Badges (hidden by default like Desktop)
    private val _showQualityBadges = MutableStateFlow(prefs.getBoolean("show_quality_badges", false))
    val showQualityBadges = _showQualityBadges.asStateFlow()

    fun setShowQualityBadges(show: Boolean) {
        prefs.edit().putBoolean("show_quality_badges", show).apply()
        _showQualityBadges.value = show
    }

    // Per-track Synced Lyrics Offset (ms)
    private val _lyricsOffsets = MutableStateFlow(loadLyricsOffsets())
    val lyricsOffsets = _lyricsOffsets.asStateFlow()

    private fun loadLyricsOffsets(): Map<String, Long> {
        val raw = prefs.getString("per_track_lyrics_offsets", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optLong(k, 0L)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getTrackLyricOffsetMs(trackId: String?): Long {
        if (trackId.isNullOrBlank()) return 0L
        return _lyricsOffsets.value[trackId] ?: 0L
    }

    fun setTrackLyricOffsetMs(trackId: String, offsetMs: Long) {
        if (trackId.isBlank()) return
        val next = _lyricsOffsets.value.toMutableMap()
        if (kotlin.math.abs(offsetMs) < 10L) {
            next.remove(trackId)
        } else {
            next[trackId] = offsetMs.coerceIn(-30000L, 30000L)
        }
        _lyricsOffsets.value = next
        val obj = JSONObject()
        next.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("per_track_lyrics_offsets", obj.toString()).apply()
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

    // Recently Searched Tracks History (Ordered by most recent selected tracks from Search)
    private val _recentSearchedTrackIds = MutableStateFlow<List<String>>(
        try {
            val raw = prefs.getString("recent_searched_track_ids", "") ?: ""
            if (raw.isNotBlank()) raw.split(",") else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    )
    val recentSearchedTrackIds = _recentSearchedTrackIds.asStateFlow()

    fun recordTrackSearched(trackId: String) {
        if (trackId.isBlank()) return
        val current = _recentSearchedTrackIds.value.toMutableList()
        current.remove(trackId)
        current.add(0, trackId)
        val trimmed = current.take(24)
        prefs.edit().putString("recent_searched_track_ids", trimmed.joinToString(",")).apply()
        _recentSearchedTrackIds.value = trimmed
    }

    fun clearRecentSearchedTracks() {
        prefs.edit().remove("recent_searched_track_ids").apply()
        _recentSearchedTrackIds.value = emptyList()
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
