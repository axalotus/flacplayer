package com.antigravity.audioplayer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.audioplayer.audio.SleepTimerController
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.service.AudioPlayerService
import com.antigravity.audioplayer.ui.components.*
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlayerOverlay(
    currentTrack: AudioFileMetadata?,
    isPlaying: Boolean,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    isFavorite: Boolean,
    onCollapse: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlaylistPicker: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenTrackMenu: () -> Unit,
    onGoToArtist: (String) -> Unit
) {
    val tokens = LocalFlacTheme.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showLyrics by remember { mutableStateOf(false) }
    val sleepActive by SleepTimerController.isActive.collectAsState()

    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val verticalOffset = remember { Animatable(0f) }
    val context = LocalContext.current

    var playerSize by remember { mutableStateOf(IntSize.Zero) }
    var playerPos by remember { mutableStateOf(Offset.Zero) }
    val playerFrostedBmp by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = "${tokens.config.fsBgMode}|${tokens.config.fsBgCustomPath}|${tokens.config.fsBgIsVideo}",
        key2 = currentTrack?.id,
        key3 = "${tokens.config.fsBgBlurPx}|${tokens.config.panelBlurPx}|${tokens.config.fsBgColorMode}"
    ) {
        value = getPanelFrostedBg(
            context = context,
            mode = tokens.config.fsBgMode,
            customPath = tokens.config.fsBgCustomPath,
            isVideo = tokens.config.fsBgIsVideo,
            bgBlurPx = tokens.config.fsBgBlurPx,
            panelBlurPx = tokens.config.panelBlurPx,
            monochrome = tokens.config.fsBgColorMode != "color",
            track = currentTrack
        )?.asImageBitmap()
    }
    val playerFrostedInfo = remember(playerFrostedBmp, playerSize, playerPos, tokens.config.fsBgDimPercent, tokens.config.fsBgMode) {
        FrostedBgInfo(
            bitmap = playerFrostedBmp,
            rootWidthPx = playerSize.width,
            rootHeightPx = playerSize.height,
            rootOffset = playerPos,
            dimAlpha = if (tokens.config.fsBgMode != "none") (tokens.config.fsBgDimPercent.coerceIn(15, 95)) / 100f else 0f
        )
    }

    CompositionLocalProvider(
        LocalFrostedBg provides playerFrostedInfo,
        LocalScreenBackdrop provides null
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { playerSize = it }
            .onGloballyPositioned { playerPos = it.positionInRoot() }
            .offset { IntOffset(0, verticalOffset.value.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        val next = (verticalOffset.value + dragAmount).coerceAtLeast(0f)
                        scope.launch { verticalOffset.snapTo(next) }
                    },
                    onDragEnd = {
                        if (verticalOffset.value > dismissThresholdPx) {
                            onCollapse()
                        } else {
                            scope.launch {
                                verticalOffset.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { verticalOffset.animateTo(0f) }
                    }
                )
            }
    ) {
        FlacBackgroundLayer(
            mode = tokens.config.fsBgMode,
            colorMode = tokens.config.fsBgColorMode,
            customPath = tokens.config.fsBgCustomPath,
            isVideo = tokens.config.fsBgIsVideo,
            blurPx = tokens.config.fsBgBlurPx,
            dimPercent = tokens.config.fsBgDimPercent,
            currentTrack = currentTrack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Minimal top bar: collapse + menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlacIconButton(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Свернуть",
                    onClick = onCollapse,
                    size = 40.dp,
                    iconSize = 26.dp,
                    tint = tokens.fg0,
                    backgroundColor = tokens.bg1.copy(alpha = 0.55f),
                    borderColor = tokens.line
                )
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(tokens.fg2.copy(alpha = 0.45f))
                )
                FlacIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = "Меню трека",
                    onClick = onOpenTrackMenu,
                    size = 40.dp,
                    iconSize = 22.dp,
                    tint = tokens.fg0,
                    backgroundColor = tokens.bg1.copy(alpha = 0.55f),
                    borderColor = tokens.line
                )
            }

            // Spacious centered Artwork or Lyrics panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(150))
                    },
                    label = "artworkLyricsSwitch"
                ) { lyricsVisible ->
                    if (lyricsVisible) {
                        LyricsPanel(
                            track = currentTrack,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        SwipeableArtworkStage(
                            track = currentTrack,
                            onNext = onNextTrack,
                            onPrevious = onPreviousTrack
                        )
                    }
                }
            }

            // Track Metadata + Favorite
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = currentTrack?.title ?: "Трек не выбран",
                        color = tokens.fg0,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentTrack?.artist ?: "—",
                        color = tokens.fg1,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = currentTrack != null && currentTrack.artist.isNotBlank()
                        ) {
                            currentTrack?.artist?.let(onGoToArtist)
                        }
                    )
                }
                FlacIconButton(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Избранное",
                    onClick = onToggleFavorite,
                    size = 42.dp,
                    iconSize = 24.dp,
                    tint = if (isFavorite) tokens.accent else tokens.fg2
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Isolated Progress Section (never recomposes parent screen!)
            PlayerSeekSection(
                track = currentTrack,
                onSeekTo = onSeekTo
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Primary Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlacIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Перемешать",
                    onClick = onToggleShuffle,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (isShuffleOn) tokens.accent else tokens.fg2
                )
                FlacIconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = "Предыдущий",
                    onClick = onPreviousTrack,
                    size = 52.dp,
                    iconSize = 34.dp,
                    tint = tokens.fg0
                )
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(tokens.accent, CircleShape)
                        .clickable(onClick = onPlayPauseToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть",
                        tint = tokens.accentFg,
                        modifier = Modifier.size(34.dp)
                    )
                }
                FlacIconButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = "Следующий",
                    onClick = onNextTrack,
                    size = 52.dp,
                    iconSize = 34.dp,
                    tint = tokens.fg0
                )
                FlacIconButton(
                    icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Повтор",
                    onClick = onToggleRepeat,
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (repeatMode != RepeatMode.OFF) tokens.accent else tokens.fg2
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Clean Bottom Utility Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerUtilityButton(
                    icon = Icons.Rounded.Lyrics,
                    active = showLyrics,
                    onClick = { showLyrics = !showLyrics }
                )
                PlayerUtilityButton(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    active = false,
                    onClick = onOpenQueue
                )
                PlayerUtilityButton(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    active = false,
                    onClick = onOpenPlaylistPicker
                )
                PlayerUtilityButton(
                    icon = Icons.Rounded.Bedtime,
                    active = sleepActive,
                    onClick = onOpenSleepTimer
                )
            }
        }
    }
    }
}

@Composable
private fun SwipeableArtworkStage(
    track: AudioFileMetadata?,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = dragX.value
                val ratio = (kotlin.math.abs(dragX.value) / 500f).coerceIn(0f, 0.25f)
                alpha = 1f - ratio
            }
            .pointerInput(track?.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dragX.snapTo((dragX.value + amount).coerceIn(-260f, 260f)) }
                    },
                    onDragEnd = {
                        val cur = dragX.value
                        if (cur < -95f) {
                            onNext()
                        } else if (cur > 95f) {
                            onPrevious()
                        }
                        scope.launch { dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                    },
                    onDragCancel = {
                        scope.launch { dragX.animateTo(0f) }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        TrackArtwork(
            track = track,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = tokens.radiusLg,
            iconSize = 68.dp,
            hiRes = true
        )
    }
}

@Composable
private fun PlayerSeekSection(
    track: AudioFileMetadata?,
    onSeekTo: (Long) -> Unit
) {
    val tokens = LocalFlacTheme.current
    val progressMs by AudioPlayerService.progressMs.collectAsState()
    val serviceDurationMs by AudioPlayerService.durationMs.collectAsState()
    val durationMs = (serviceDurationMs.takeIf { it > 0L } ?: track?.durationMs ?: 0L).coerceAtLeast(1L)

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    val displayPos = if (isScrubbing) scrubMs else progressMs.coerceIn(0L, durationMs)

    Column(modifier = Modifier.fillMaxWidth()) {
        FlacFlatSlider(
            value = displayPos.toFloat(),
            valueRange = 0f..durationMs.toFloat(),
            onValueChange = { next ->
                isScrubbing = true
                scrubMs = next.toLong().coerceIn(0L, durationMs)
            },
            onValueChangeFinished = {
                isScrubbing = false
                onSeekTo(scrubMs)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDurationMs(displayPos),
                color = tokens.fg2,
                fontSize = 11.5.sp
            )
            Text(
                text = formatDurationMs(durationMs),
                color = tokens.fg2,
                fontSize = 11.5.sp
            )
        }
    }
}

@Composable
private fun PlayerUtilityButton(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val tokens = LocalFlacTheme.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(tokens.radiusSm))
            .background(if (active) tokens.accent.copy(alpha = 0.16f) else Color.Transparent)
            .then(
                if (active) Modifier.border(1.dp, tokens.accent.copy(alpha = 0.55f), RoundedCornerShape(tokens.radiusSm))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) tokens.accent else tokens.fg2,
            modifier = Modifier.size(21.dp)
        )
    }
}
