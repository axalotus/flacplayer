package com.antigravity.audioplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.AudioFormat

@Composable
fun QualityBadge(
    metadata: AudioFileMetadata,
    modifier: Modifier = Modifier
) {
    val sampleRateKhz = if (metadata.sampleRateHz % 1000 == 0) {
        "${metadata.sampleRateHz / 1000}k"
    } else {
        String.format("%.1fk", metadata.sampleRateHz / 1000.0)
    }

    val badgeText = when (metadata.format) {
        AudioFormat.FLAC -> "FLAC ${metadata.bitDepth}b/$sampleRateKhz"
        AudioFormat.WAV -> "WAV ${metadata.bitDepth}b/$sampleRateKhz"
        AudioFormat.MP3 -> "MP3 ${metadata.bitrateKbps}k"
        else -> "${metadata.format.name} $sampleRateKhz"
    }

    val badgeColor = when {
        metadata.bitDepth >= 24 || metadata.sampleRateHz >= 88200 -> Color(0xFFFFB703) // Gold for Hi-Res
        metadata.format == AudioFormat.FLAC || metadata.format == AudioFormat.WAV -> Color(0xFF00B4D8) // Cyan for Lossless
        else -> Color(0xFF8D99AE) // Subtle gray for MP3
    }

    Surface(
        color = badgeColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = badgeText,
            color = badgeColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
