package com.antigravity.audioplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.repository.LyricsLoadState
import com.antigravity.audioplayer.data.repository.LyricsRepository
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.service.AudioPlayerService
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme
import kotlinx.coroutines.launch

@Composable
fun LyricsPanel(
    track: AudioFileMetadata?,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()

    val lyricsData by LyricsRepository.currentLyrics.collectAsState()
    val loadState by LyricsRepository.loadState.collectAsState()
    val isLoading by LyricsRepository.isLoading.collectAsState()
    val lyricsOffsets by prefs.lyricsOffsets.collectAsState()
    val progressMs by AudioPlayerService.progressMs.collectAsState()

    LaunchedEffect(track?.id) {
        if (track != null) {
            LyricsRepository.loadLyricsForTrack(context, track)
        }
    }

    val trackId = track?.id
    val offsetMs = remember(trackId, lyricsOffsets) {
        if (trackId != null) lyricsOffsets[trackId] ?: 0L else 0L
    }
    val adjustedPos = (progressMs + offsetMs).coerceAtLeast(0L)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                track == null -> {
                    LyricsEmptyState(
                        title = "Нет активного трека",
                        subtitle = null
                    )
                }
                isLoading -> {
                    LyricsEmptyState(
                        title = "Поиск текста...",
                        subtitle = null
                    )
                }
                lyricsData?.isInstrumental == true -> {
                    LyricsEmptyState(
                        title = "Инструментальная композиция",
                        subtitle = "Вокальная партия отсутствует"
                    )
                }
                lyricsData != null && lyricsData!!.isSynced && lyricsData!!.lines.isNotEmpty() -> {
                    val lines = lyricsData!!.lines
                    val activeIndex = remember(lines, adjustedPos) {
                        var idx = -1
                        for (i in lines.indices) {
                            if (lines[i].timestampMs <= adjustedPos) idx = i else break
                        }
                        idx
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(activeIndex, trackId) {
                        if (activeIndex >= 0) {
                            val target = (activeIndex - 2).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    }

                    val blurIntensity = tokens.config.lyricsBlurIntensity.coerceIn(0, 180) / 100f
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 36.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(lines, key = { i, line -> "${i}_${line.timestampMs}" }) { index, line ->
                            val isActive = index == activeIndex
                            val isPast = activeIndex >= 0 && index < activeIndex
                            val inactiveAlpha = when {
                                isActive -> 1f
                                isPast -> (0.34f / blurIntensity.coerceAtLeast(0.55f)).coerceIn(0.16f, 0.5f)
                                else -> (0.48f / blurIntensity.coerceAtLeast(0.6f)).coerceIn(0.22f, 0.65f)
                            }
                            val scale by animateFloatAsState(
                                targetValue = if (isActive) 1.03f else 0.96f,
                                animationSpec = tween(200),
                                label = "lyricScale"
                            )
                            Text(
                                text = line.text.ifBlank { "..." },
                                color = if (isActive) tokens.fg0 else tokens.fg1.copy(alpha = inactiveAlpha),
                                fontSize = if (isActive) 21.sp else 18.sp,
                                lineHeight = 27.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onSeekTo((line.timestampMs - offsetMs).coerceAtLeast(0L))
                                    }
                            )
                        }
                    }
                }
                lyricsData != null && lyricsData!!.plainText.isNotBlank() -> {
                    val plainLines = remember(lyricsData?.plainText) {
                        lyricsData?.plainText?.lines().orEmpty()
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 24.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(plainLines) { _, line ->
                            Text(
                                text = line,
                                color = tokens.fg0.copy(alpha = 0.88f),
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            tint = tokens.fg2,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (loadState is LyricsLoadState.NotFound) "Текст не найден" else "Нет текста для трека",
                            color = tokens.fg1,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        FlacPillButton(
                            text = "Найти онлайн (LRCLIB)",
                            icon = Icons.Rounded.CloudDownload,
                            onClick = {
                                scope.launch {
                                    LyricsRepository.fetchOnlineLyricsForced(context, track)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (track != null && lyricsData?.isSynced == true) {
            LyricsOffsetBar(
                offsetMs = offsetMs,
                onAdjust = { delta ->
                    prefs.setTrackLyricOffsetMs(track.id, offsetMs + delta)
                },
                onReset = {
                    prefs.setTrackLyricOffsetMs(track.id, 0L)
                }
            )
        }
    }
}

@Composable
private fun LyricsOffsetBar(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val secFormatted = "%+.1fs".format(offsetMs / 1000.0)
    Row(
        modifier = Modifier
            .padding(top = 6.dp, bottom = 2.dp)
            .clip(CircleShape)
            .background(tokens.bg1, CircleShape)
            .border(1.dp, tokens.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OffsetChip("-0.5") { onAdjust(-500L) }
        OffsetChip("-0.1") { onAdjust(-100L) }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReset)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (offsetMs == 0L) "0.0s" else secFormatted,
                color = if (offsetMs == 0L) tokens.fg1 else tokens.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        OffsetChip("+0.1") { onAdjust(100L) }
        OffsetChip("+0.5") { onAdjust(500L) }
    }
}

@Composable
private fun OffsetChip(label: String, onClick: () -> Unit) {
    val tokens = LocalFlacTheme.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tokens.bg2, CircleShape)
            .border(1.dp, tokens.line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = tokens.fg0,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LyricsEmptyState(title: String, subtitle: String?) {
    val tokens = LocalFlacTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = tokens.fg1,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = tokens.fg2,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
