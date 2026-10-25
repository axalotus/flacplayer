package com.antigravity.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.service.AudioPlayerService

@Composable
fun MiniPlayerBar(
    currentTrack: AudioFileMetadata?,
    isPlaying: Boolean,
    onPlayPauseToggle: () -> Unit,
    onNextTrack: () -> Unit,
    onClickExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (currentTrack == null) return

    Surface(
        onClick = onClickExpand,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Cover Art Thumbnail
                AlbumArtView(
                    track = currentTrack,
                    modifier = Modifier.size(42.dp),
                    cornerRadius = 10
                )

                // Track Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentTrack.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = currentTrack.artist,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        QualityBadge(metadata = currentTrack)
                    }
                }

                // Controls: Play/Pause & Next Track
                IconButton(onClick = onPlayPauseToggle) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onNextTrack) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Slim Bottom Progress Bar (isolated from MiniPlayerBar recomposition; reads progress in draw phase only)
            MiniPlayerProgressBar(fallbackDurationMs = currentTrack.durationMs)
        }
    }
}

@Composable
private fun MiniPlayerProgressBar(fallbackDurationMs: Long) {
    val progressState = AudioPlayerService.progressMs.collectAsState()
    val durationState = AudioPlayerService.durationMs.collectAsState()
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp)
    ) {
        val totalMs = durationState.value.takeIf { it > 0L } ?: fallbackDurationMs.coerceAtLeast(1L)
        val fraction = (progressState.value.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        drawRect(color = trackColor, size = size)
        if (fraction > 0f) {
            drawRect(
                color = activeColor,
                size = size.copy(width = size.width * fraction)
            )
        }
    }
}
