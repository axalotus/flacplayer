package com.antigravity.audioplayer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.desktop.audio.DesktopAudioPlayer
import com.antigravity.audioplayer.desktop.audio.DesktopRepeatMode
import com.antigravity.audioplayer.desktop.theme.AccentOrange
import com.antigravity.audioplayer.desktop.theme.AccentPrimary
import com.antigravity.audioplayer.desktop.theme.ObsidianSurface

@Composable
fun DesktopPlayerBar(
    onOpenLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrack by DesktopAudioPlayer.currentTrack.collectAsState()
    val isPlaying by DesktopAudioPlayer.isPlaying.collectAsState()
    val progressMs by DesktopAudioPlayer.progressMs.collectAsState()
    val volume by DesktopAudioPlayer.volume.collectAsState()
    val isMuted by DesktopAudioPlayer.isMuted.collectAsState()
    val isShuffleOn by DesktopAudioPlayer.isShuffleOn.collectAsState()
    val repeatMode by DesktopAudioPlayer.repeatMode.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth().height(90.dp),
        color = ObsidianSurface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── LEFT: TRACK INFO + HI-RES BADGE ──────────────────────────────
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = currentTrack?.title ?: "Выберите трек для воспроизведения",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentTrack?.artist ?: "FlacPlayer Hi-Res Engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    currentTrack?.let { track ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = if (track.isHiRes) AccentOrange.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${track.format} • ${track.sampleRateKHzFormatted} / ${track.bitDepth}-bit",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (track.isHiRes) AccentOrange else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── CENTER: CONTROLS & TIMELINE ──────────────────────────────────
            Column(
                modifier = Modifier.weight(2f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Control Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { DesktopAudioPlayer.toggleShuffle() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Случайно",
                            tint = if (isShuffleOn) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Prev
                    IconButton(
                        onClick = { DesktopAudioPlayer.skipToPrevious() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", modifier = Modifier.size(24.dp))
                    }

                    // Play/Pause
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { DesktopAudioPlayer.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Воспроизведение",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { DesktopAudioPlayer.skipToNext() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Вперед", modifier = Modifier.size(24.dp))
                    }

                    // Repeat
                    IconButton(
                        onClick = { DesktopAudioPlayer.toggleRepeat() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (repeatMode == DesktopRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Повтор",
                            tint = if (repeatMode != DesktopRepeatMode.OFF) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Timeline Seek Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val durationMs = (currentTrack?.durationMs ?: 1L).coerceAtLeast(1L)
                    val progressRatio = (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

                    Text(
                        text = formatMs(progressMs),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Slider(
                        value = progressRatio,
                        onValueChange = { ratio ->
                            DesktopAudioPlayer.seekTo((ratio * durationMs).toLong())
                        },
                        modifier = Modifier.weight(1f).height(18.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    Text(
                        text = formatMs(durationMs),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── RIGHT: VOLUME & QUICK SHORTCUTS ──────────────────────────────
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Lyrics Shortcut
                IconButton(onClick = onOpenLyrics) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Текст песни", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // EQ Shortcut
                IconButton(onClick = onOpenEqualizer) {
                    Icon(Icons.Default.Equalizer, contentDescription = "Эквалайзер", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Volume Mute Button
                IconButton(
                    onClick = { DesktopAudioPlayer.toggleMute() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (isMuted || volume == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Громкость",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Volume Slider
                Slider(
                    value = if (isMuted) 0f else volume,
                    onValueChange = { DesktopAudioPlayer.setVolume(it) },
                    modifier = Modifier.width(100.dp).height(18.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%d:%02d", min, sec)
}
