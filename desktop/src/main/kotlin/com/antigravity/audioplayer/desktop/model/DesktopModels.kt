package com.antigravity.audioplayer.desktop.model

import kotlinx.serialization.Serializable
import java.io.File

enum class DesktopAudioFormat {
    FLAC, MP3, WAV, AAC, OGG, UNKNOWN
}

@Serializable
data class DesktopTrack(
    val id: String,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val trackNumber: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
) {
    val sampleRateKHzFormatted: String
        get() = String.format("%.1f kHz", sampleRateHz / 1000.0)

    val isHiRes: Boolean
        get() = (format.equals("FLAC", true) || format.equals("WAV", true)) && (bitDepth >= 24 || sampleRateHz >= 88200)

    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

@Serializable
data class DesktopPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class LyricLine(
    val timestampMs: Long,
    val text: String
)

@Serializable
data class LyricsData(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String,
    val source: String,
    val offsetMs: Long = 0L
)

@Serializable
data class EqPreset(
    val name: String,
    val gains: List<Float>, // 10 bands (-12dB .. +12dB)
    val isCustom: Boolean = false
)

@Serializable
data class SyncPayload(
    val playlists: List<DesktopPlaylist> = emptyList(),
    val favoriteIds: List<String> = emptyList(),
    val customPresets: List<EqPreset> = emptyList(),
    val currentTrackTitle: String? = null,
    val currentTrackArtist: String? = null,
    val playbackProgressMs: Long = 0L
)
