package com.zuna.player.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.model.LyricWord
import com.zuna.player.data.repository.LyricsLoadState
import com.zuna.player.data.repository.LyricsRepository
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.service.AudioPlayerService
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.LocalPowerSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    var loadedTrackId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track?.id) {
        loadedTrackId = null
        if (track != null) {
            LyricsRepository.loadLyricsForTrack(context, track)
            loadedTrackId = track.id
        }
    }
    val isSwitchingTrack = track != null && loadedTrackId != track.id

    val trackId = track?.id
    val offsetMs = remember(trackId, lyricsOffsets) {
        if (trackId != null) lyricsOffsets[trackId] ?: 0L else 0L
    }

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
                isLoading || isSwitchingTrack -> {
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
                    val hasWordSync = lyricsData!!.hasWordSync
                    val powerSaver = LocalPowerSaver.current
                    val isPlayingState by AudioPlayerService.isPlaying.collectAsState()
                    val reportedPosMs by AudioPlayerService.progressMs.collectAsState()

                    val smoothPosMs by produceState(
                        initialValue = reportedPosMs,
                        key1 = hasWordSync,
                        key2 = isPlayingState,
                        key3 = "$reportedPosMs|${powerSaver.reduceAnimations}|${powerSaver.limitUiRefresh}"
                    ) {
                        value = reportedPosMs
                        if (hasWordSync && isPlayingState && !powerSaver.reduceAnimations && !powerSaver.limitUiRefresh) {
                            val anchorWall = System.nanoTime() / 1_000_000L
                            val anchorAudio = reportedPosMs
                            while (true) {
                                withFrameMillis { _ ->
                                    val nowWall = System.nanoTime() / 1_000_000L
                                    value = anchorAudio + (nowWall - anchorWall).coerceIn(0L, 400L)
                                }
                            }
                        }
                    }

                    val activeIndex by remember(lines, offsetMs) {
                        AudioPlayerService.progressMs
                            .map { pos ->
                                val adjusted = (pos + offsetMs).coerceAtLeast(0L)
                                var idx = -1
                                for (i in lines.indices) {
                                    if (lines[i].timestampMs <= adjusted) idx = i else break
                                }
                                idx
                            }
                            .distinctUntilChanged()
                    }.collectAsState(
                        initial = remember(lines, offsetMs) {
                            val adjusted = (AudioPlayerService.progressMs.value + offsetMs).coerceAtLeast(0L)
                            var idx = -1
                            for (i in lines.indices) {
                                if (lines[i].timestampMs <= adjusted) idx = i else break
                            }
                            idx
                        }
                    )
                    val listState = rememberLazyListState()
                    var isManualScroll by remember(trackId) { mutableStateOf(false) }
                    var manualScrollTick by remember(trackId) { mutableIntStateOf(0) }

                    val manualScrollDetector = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput && abs(available.y) > 0.5f) {
                                    isManualScroll = true
                                    manualScrollTick++
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    LaunchedEffect(manualScrollTick, isManualScroll) {
                        if (isManualScroll) {
                            delay(3500L)
                            isManualScroll = false
                        }
                    }

                    LaunchedEffect(activeIndex, trackId, isManualScroll) {
                        if (!isManualScroll && activeIndex >= 0) {
                            smoothScrollToLyricIndex(listState, activeIndex)
                        }
                    }

                    val blurScale = tokens.config.lyricsBlurIntensity.coerceIn(0, 180) / 100f
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(manualScrollDetector),
                            contentPadding = PaddingValues(top = 64.dp, bottom = 140.dp, start = 14.dp, end = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(lines, key = { i, line -> "${i}_${line.timestampMs}" }) { index, line ->
                                val isActive = index == activeIndex
                                val dist = if (activeIndex >= 0) abs(index - activeIndex) else 0

                                val targetAlpha = when {
                                    isManualScroll -> if (isActive) 1f else 0.78f
                                    activeIndex < 0 -> 0.85f
                                    dist == 0 -> 1f
                                    else -> {
                                        val baseAlpha = when (dist) {
                                            1 -> 0.56f
                                            2 -> 0.38f
                                            3 -> 0.24f
                                            else -> (0.24f - (dist - 3) * 0.04f).coerceAtLeast(0.10f)
                                        }
                                        lerp(0.92f, baseAlpha, blurScale.coerceIn(0f, 1f))
                                    }
                                }

                                val supportsRowBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    !powerSaver.disableBlur && !powerSaver.reduceAnimations
                                val targetBlurDp = when {
                                    !supportsRowBlur || isManualScroll || activeIndex < 0 || dist == 0 || blurScale <= 0.01f -> 0.dp
                                    dist == 1 -> (1.2f * blurScale).coerceAtMost(2.5f).dp
                                    dist == 2 -> (2.2f * blurScale).coerceAtMost(4.0f).dp
                                    else -> (3.2f * blurScale).coerceAtMost(5.0f).dp
                                }

                                val targetScale = if (powerSaver.reduceAnimations || isActive || activeIndex < 0) 1.0f else 0.92f
                                val targetColor = if (isActive) tokens.fg0 else tokens.fg1.copy(alpha = targetAlpha)

                                val animDur = if (powerSaver.reduceAnimations) 1 else 380
                                val scale by animateFloatAsState(
                                    targetValue = targetScale,
                                    animationSpec = tween(durationMillis = animDur, easing = FastOutSlowInEasing),
                                    label = "lyricScale"
                                )
                                val textColor by animateColorAsState(
                                    targetValue = targetColor,
                                    animationSpec = tween(durationMillis = animDur, easing = FastOutSlowInEasing),
                                    label = "lyricColor"
                                )
                                val blurDp by if (supportsRowBlur && targetBlurDp > 0.dp) {
                                    animateDpAsState(
                                        targetValue = targetBlurDp,
                                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                                        label = "lyricBlur"
                                    )
                                } else {
                                    rememberUpdatedState(0.dp)
                                }
                                val rowModifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .then(
                                        if (supportsRowBlur && blurDp > 0.25.dp) {
                                            Modifier.blur(blurDp, BlurredEdgeTreatment.Unbounded)
                                        } else Modifier
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        isManualScroll = false
                                        onSeekTo((line.timestampMs - offsetMs).coerceAtLeast(0L))
                                    }

                                if (isActive && line.words.isNotEmpty()) {
                                    val wordAnnotated = buildEnhancedLineAnnotatedString(
                                        words = line.words,
                                        adjustedPosMs = (smoothPosMs + offsetMs).coerceAtLeast(0L),
                                        litColor = tokens.fg0,
                                        dimColor = tokens.fg0.copy(alpha = 0.34f)
                                    )
                                    Text(
                                        text = wordAnnotated,
                                        fontSize = 21.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Start,
                                        modifier = rowModifier
                                    )
                                } else {
                                    Text(
                                        text = line.text.ifBlank { "..." },
                                        color = textColor,
                                        fontSize = 21.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Start,
                                        modifier = rowModifier
                                    )
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isManualScroll && activeIndex >= 0,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(180)),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                        ) {
                            FlacPillButton(
                                text = "К текущей",
                                icon = Icons.Rounded.MyLocation,
                                accentFill = true,
                                onClick = {
                                    isManualScroll = false
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

        if (track != null && !isSwitchingTrack && !isLoading && lyricsData?.isSynced == true) {
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

private suspend fun smoothScrollToLyricIndex(
    listState: LazyListState,
    targetIndex: Int
) {
    if (targetIndex < 0) return
    val layoutInfo = listState.layoutInfo
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    val anchorOffset = (viewportHeight * 0.28f).toInt()
    val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (visibleItem != null) {
        val delta = (visibleItem.offset - anchorOffset).toFloat()
        if (abs(delta) > 1f) {
            listState.animateScrollBy(
                value = delta,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
        }
    } else {
        val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        val approachIndex = if (targetIndex > firstVisible) {
            (targetIndex - 3).coerceAtLeast(0)
        } else {
            (targetIndex - 1).coerceAtLeast(0)
        }
        listState.scrollToItem(approachIndex)
        withFrameNanos { }
        val updatedInfo = listState.layoutInfo
        val itemAfterJump = updatedInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (itemAfterJump != null) {
            val vh = (updatedInfo.viewportEndOffset - updatedInfo.viewportStartOffset).coerceAtLeast(1)
            val anchor = (vh * 0.28f).toInt()
            val delta = (itemAfterJump.offset - anchor).toFloat()
            if (abs(delta) > 1f) {
                listState.animateScrollBy(
                    value = delta,
                    animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                )
            }
        }
    }
}

private fun buildEnhancedLineAnnotatedString(
    words: List<LyricWord>,
    adjustedPosMs: Long,
    litColor: Color,
    dimColor: Color
): AnnotatedString = buildAnnotatedString {
    for (w in words) {
        val text = w.text
        if (text.isEmpty()) continue
        val dur = (w.endMs - w.startMs).coerceAtLeast(40L).toFloat()
        val p = ((adjustedPosMs - w.startMs).toFloat() / dur).coerceIn(0f, 1f)
        if (p <= 0f) {
            withStyle(SpanStyle(color = dimColor)) { append(text) }
        } else if (p >= 1f) {
            withStyle(SpanStyle(color = litColor)) { append(text) }
        } else {
            val visibleLen = text.trimEnd().length.coerceAtLeast(1)
            val cursor = p * visibleLen
            val fullChars = cursor.toInt().coerceIn(0, visibleLen)
            val frac = (cursor - fullChars).coerceIn(0f, 1f)
            if (fullChars > 0) {
                withStyle(SpanStyle(color = litColor)) { append(text.substring(0, fullChars)) }
            }
            if (fullChars < visibleLen) {
                withStyle(SpanStyle(color = colorLerp(dimColor, litColor, frac))) {
                    append(text.substring(fullChars, fullChars + 1))
                }
                if (fullChars + 1 < text.length) {
                    withStyle(SpanStyle(color = dimColor)) {
                        append(text.substring(fullChars + 1))
                    }
                }
            } else if (visibleLen < text.length) {
                withStyle(SpanStyle(color = dimColor)) { append(text.substring(visibleLen)) }
            }
        }
    }
}
