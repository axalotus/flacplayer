package com.antigravity.audioplayer.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class AudioFormat {
    FLAC, MP3, WAV, AAC, OGG
}

enum class AudioQualityType(val label: String, val badgeColorHex: Long) {
    HI_RES_LOSSLESS("HI-RES LOSSLESS", 0xFFFFB703),
    LOSSLESS("LOSSLESS", 0xFF00B4D8),
    HIGH_QUALITY("HIGH QUALITY", 0xFF06D6A0),
    STANDARD("STANDARD", 0xFF8D99AE)
}

@Immutable
data class AudioFileMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val artworkUri: Uri? = null,
    val format: AudioFormat,
    val sampleRateHz: Int,         // e.g. 96000, 44100
    val bitDepth: Int,             // e.g. 24, 16, 32
    val bitrateKbps: Int,          // e.g. 2840, 320
    val channels: Int = 2,         // e.g. 2 for Stereo
    val trackNumber: Int = 0,      // Track number in album (e.g. 1, 2, 3)
    val isStream: Boolean = false, // True for Navidrome / Remote
    val streamServerUrl: String? = null,
    val dateAdded: Long = 0L       // Timestamp in milliseconds
) {
    val sampleRateKHzFormatted: String
        get() = String.format("%.1f kHz", sampleRateHz / 1000.0)

    val audioQualityType: AudioQualityType
        get() = when {
            format == AudioFormat.FLAC && (bitDepth >= 24 || sampleRateHz >= 88200) -> AudioQualityType.HI_RES_LOSSLESS
            format == AudioFormat.WAV && (bitDepth >= 24 || sampleRateHz >= 88200) -> AudioQualityType.HI_RES_LOSSLESS
            format == AudioFormat.FLAC || format == AudioFormat.WAV -> AudioQualityType.LOSSLESS
            bitrateKbps >= 320 -> AudioQualityType.HIGH_QUALITY
            else -> AudioQualityType.STANDARD
        }

    val qualityBadgeText: String
        get() = when (format) {
            AudioFormat.FLAC, AudioFormat.WAV -> "${format.name} • ${bitDepth}-bit / $sampleRateKHzFormatted"
            else -> "${format.name} • ${bitrateKbps} kbps / $sampleRateKHzFormatted"
        }
}
