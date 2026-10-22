package com.antigravity.audioplayer.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.LyricsData
import com.antigravity.audioplayer.data.model.LyricsSource
import com.antigravity.audioplayer.data.repository.LyricsRepository
import kotlinx.coroutines.delay

@Composable
fun LyricsView(
    track: AudioFileMetadata?,
    progressMs: Long,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    onPickLyricsFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lyricsData by LyricsRepository.currentLyrics.collectAsState()
    val isLoading by LyricsRepository.isLoading.collectAsState()

    LaunchedEffect(track?.id) {
        LyricsRepository.loadLyricsForTrack(context, track)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp
                )
            }

            lyricsData == null || (lyricsData?.lines?.isEmpty() == true && lyricsData?.plainText.isNullOrBlank()) -> {
                // Empty state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Lyrics,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Text(
                        text = "Текст песни не найден",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Поместите файл с именем трека .lrc или .txt рядом с аудиофайлом, либо выберите файл вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onPickLyricsFile,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать файл .lrc/.txt", fontSize = 13.sp)
                    }
                }
            }

            lyricsData!!.isSynced -> {
                val lines = lyricsData!!.lines
                val listState = rememberLazyListState()
                val isDragged by listState.interactionSource.collectIsDraggedAsState()
                var userInteracted by remember { mutableStateOf(false) }

                // Find active line index
                val activeIndex = remember(progressMs, lines) {
                    var found = -1
                    for (i in lines.indices) {
                        if (progressMs >= lines[i].timestampMs) {
                            found = i
                        } else {
                            break
                        }
                    }
                    found
                }

                // If user dragged, pause auto-scroll for 3.5s
                LaunchedEffect(isDragged) {
                    if (isDragged) {
                        userInteracted = true
                        delay(3500)
                        userInteracted = false
                    }
                }

                // Auto-scroll to active index centered
                LaunchedEffect(activeIndex, userInteracted) {
                    if (!userInteracted && activeIndex >= 0 && activeIndex < lines.size) {
                        val targetIndex = (activeIndex - 2).coerceAtLeast(0)
                        listState.animateScrollToItem(targetIndex)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Header with format badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(
                                "СИНХРОНИЗИРОВАНО (LRC)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                        }

                        Text(
                            lyricsData!!.source.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val isActive = index == activeIndex
                            val colorAnim by animateColorAsState(
                                targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.38f),
                                animationSpec = tween(durationMillis = 250),
                                label = "lyricColor"
                            )
                            val scaleAnim by animateFloatAsState(
                                targetValue = if (isActive) 1.05f else 0.98f,
                                animationSpec = tween(durationMillis = 250),
                                label = "lyricScale"
                            )
                            val alphaAnim by animateFloatAsState(
                                targetValue = if (isActive) 1.0f else 0.45f,
                                animationSpec = tween(durationMillis = 250),
                                label = "lyricAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .scale(scaleAnim)
                                    .alpha(alphaAnim)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSeekTo(line.timestampMs) }
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                            ) {
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 20.sp else 16.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = colorAnim,
                                    textAlign = TextAlign.Start,
                                    lineHeight = if (isActive) 26.sp else 22.sp
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Plain Text Mode
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ТЕКСТ ПЕСНИ (TXT)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            lyricsData!!.source.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    val plainLines = lyricsData!!.lines.ifEmpty {
                        lyricsData!!.plainText.lines().map { com.antigravity.audioplayer.data.model.LyricLine(0L, it) }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(plainLines) { _, line ->
                            Text(
                                text = line.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White.copy(alpha = 0.85f),
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
