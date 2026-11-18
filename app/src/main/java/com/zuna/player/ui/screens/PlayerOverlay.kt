package com.zuna.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zuna.player.audio.SleepTimerController
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.service.AudioPlayerService
import com.zuna.player.ui.components.*
import com.zuna.player.ui.theme.LocalFlacTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class ContainerRevealShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val safeRight = right.coerceAtLeast(left + 1f)
        val safeBottom = bottom.coerceAtLeast(top + 1f)
        return Outline.Rounded(
            RoundRect(
                left = left,
                top = top,
                right = safeRight,
                bottom = safeBottom,
                radiusX = cornerRadius,
                radiusY = cornerRadius
            )
        )
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

@Composable
fun PlayerOverlay(
    currentTrack: AudioFileMetadata?,
    isPlaying: Boolean,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    isFavorite: Boolean,
    isExpanded: Boolean = true,
    expandProgress: Animatable<Float, AnimationVector1D>? = null,
    swipeOffset: Animatable<Float, AnimationVector1D>? = null,
    swipeDragRaw: MutableFloatState? = null,
    miniPlayerBounds: Rect = Rect.Zero,
    backdropSourceState: FlacBackdropState? = null,
    recordBackdrop: Boolean = false,
    onCollapse: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onStopPlayback: () -> Unit,
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
    val rootTokens = LocalFlacTheme.current
    val tokens = remember(rootTokens) {
        rootTokens.forBackdrop(rootTokens.config.fsBgMode, rootTokens.config.fsBgSolidColor)
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showLyrics by remember { mutableStateOf(false) }
    val sleepActive by SleepTimerController.isActive.collectAsState()

    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val localVerticalOffset = remember { Animatable(0f) }
    val localDragRaw = remember { mutableFloatStateOf(0f) }
    val verticalOffset = swipeOffset ?: localVerticalOffset
    val dragRaw = swipeDragRaw ?: localDragRaw
    var nestedSwipeActive by remember { mutableStateOf(false) }

    val radiusMiniPx = with(density) { tokens.radiusMd.toPx() }
    val radiusDragPx = with(density) { 24.dp.toPx() }
    val fallbackBottomOffsetPx = with(density) { 64.dp.toPx() }
    val fallbackMiniHeightPx = with(density) { 54.dp.toPx() }
    val fallbackSideInsetPx = with(density) { 12.dp.toPx() }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            nestedSwipeActive = false
            dragRaw.floatValue = 0f
            if (swipeOffset == null) {
                verticalOffset.snapTo(0f)
            }
        }
    }

    val nestedDismissConnection = remember(dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val curTotal = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                if (source == NestedScrollSource.UserInput && curTotal > 0f && available.y < 0f) {
                    nestedSwipeActive = true
                    val consumedY = available.y.coerceAtLeast(-curTotal)
                    val target = (curTotal + consumedY).coerceAtLeast(0f)
                    dragRaw.floatValue = (target - verticalOffset.value)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    nestedSwipeActive = true
                    dragRaw.floatValue += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val offset = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                nestedSwipeActive = false
                if (offset > 0f) {
                    dragRaw.floatValue = 0f
                    verticalOffset.snapTo(offset)
                    if (offset > dismissThresholdPx || (available.y > 900f && offset > dismissThresholdPx * 0.35f)) {
                        onCollapse()
                    } else {
                        verticalOffset.animateTo(
                            0f,
                            spring(stiffness = Spring.StiffnessMediumLow)
                        )
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    CompositionLocalProvider(
        LocalFlacTheme provides tokens,
        LocalFrostedBg provides null,
        LocalScreenBackdrop provides null
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = (expandProgress?.value ?: 1f).coerceIn(0f, 1f)
                val dragY = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                if ((p < 0.999f || dragY > 0.5f) && size.width > 0f && size.height > 0f) {
                    val mLeft = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.left else fallbackSideInsetPx
                    val mRight = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.right else (size.width - fallbackSideInsetPx)
                    val mBottom = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.bottom else (size.height - fallbackBottomOffsetPx)
                    val mTop = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.top else (mBottom - fallbackMiniHeightPx)

                    val dragFrac = (dragY / (size.height * 0.45f)).coerceIn(0f, 1f)
                    val targetRadius = if (dragY > 1f) lerpFloat(0f, radiusDragPx, dragFrac) else 0f

                    shape = ContainerRevealShape(
                        left = lerpFloat(mLeft, 0f, p),
                        top = lerpFloat(mTop, dragY, p),
                        right = lerpFloat(mRight, size.width, p),
                        bottom = lerpFloat(mBottom, size.height + dragY, p),
                        cornerRadius = lerpFloat(radiusMiniPx, targetRadius, p)
                    )
                    clip = true
                } else {
                    clip = false
                }
                alpha = (p / 0.22f).coerceIn(0f, 1f)
            }
            .nestedScroll(nestedDismissConnection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { nestedSwipeActive = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (nestedSwipeActive) return@detectVerticalDragGestures
                        val cur = (verticalOffset.value + dragRaw.floatValue + dragAmount).coerceAtLeast(0f)
                        dragRaw.floatValue = cur - verticalOffset.value
                    },
                    onDragEnd = {
                        if (nestedSwipeActive) {
                            nestedSwipeActive = false
                            return@detectVerticalDragGestures
                        }
                        val finalOffset = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                        dragRaw.floatValue = 0f
                        scope.launch {
                            verticalOffset.snapTo(finalOffset)
                            if (finalOffset > dismissThresholdPx) {
                                onCollapse()
                            } else {
                                verticalOffset.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        if (!nestedSwipeActive) {
                            val cur = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                            dragRaw.floatValue = 0f
                            scope.launch {
                                verticalOffset.snapTo(cur)
                                verticalOffset.animateTo(0f)
                            }
                        }
                        nestedSwipeActive = false
                    }
                )
            }
            .then(
                if (recordBackdrop && backdropSourceState != null) {
                    Modifier.flacBackdropSource(backdropSourceState)
                } else Modifier
            )
    ) {
        FlacBackgroundLayer(
            mode = tokens.config.fsBgMode,
            colorMode = tokens.config.fsBgColorMode,
            customPath = tokens.config.fsBgCustomPath,
            isVideo = tokens.config.fsBgIsVideo,
            blurPx = tokens.config.fsBgBlurPx,
            dimPercent = tokens.config.fsBgDimPercent,
            currentTrack = currentTrack,
            isActive = isExpanded || (expandProgress?.value ?: 1f) > 0.01f,
            solidColor = tokens.config.fsBgSolidColor
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = (expandProgress?.value ?: 1f).coerceIn(0f, 1f)
                    val dragY = (verticalOffset.value + dragRaw.floatValue).coerceAtLeast(0f)
                    if ((p < 0.999f || dragY > 0.5f) && size.height > 0f) {
                        val mBottom = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.bottom else (size.height - fallbackBottomOffsetPx)
                        val mTop = if (miniPlayerBounds != Rect.Zero) miniPlayerBounds.top else (mBottom - fallbackMiniHeightPx)
                        val dragFade = (dragY / (size.height * 0.70f)).coerceIn(0f, 0.25f)
                        alpha = ((p - 0.10f) / 0.50f).coerceIn(0f, 1f) * (1f - dragFade)
                        translationY = lerpFloat(mTop * 0.34f, dragY, p)
                        val s = lerpFloat(0.93f, 1f, p)
                        scaleX = s
                        scaleY = s
                    } else {
                        alpha = 1f
                        translationY = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val compactHeight = maxHeight < 560.dp
            val vPad = if (compactHeight) 6.dp else 12.dp
            val artworkVPad = if (compactHeight) 6.dp else 18.dp
            val spacingMeta = if (compactHeight) 8.dp else 16.dp
            val spacingTransport = if (compactHeight) 10.dp else 18.dp
            val spacingBottom = if (compactHeight) 10.dp else 22.dp
            val playBtnSize = if (compactHeight) 56.dp else 68.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = vPad),
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
                    tint = tokens.panelFg0,
                    backgroundColor = tokens.bg1.copy(alpha = 0.55f),
                    borderColor = tokens.panelLine
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
                    tint = tokens.panelFg0,
                    backgroundColor = tokens.bg1.copy(alpha = 0.55f),
                    borderColor = tokens.panelLine
                )
            }

            // Spacious centered Artwork or Lyrics panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = artworkVPad),
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

            Spacer(modifier = Modifier.height(spacingMeta))

            // Isolated Progress Section (never recomposes parent screen!)
            PlayerSeekSection(
                track = currentTrack,
                onSeekTo = onSeekTo
            )

            Spacer(modifier = Modifier.height(spacingTransport))

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
                        .size(playBtnSize)
                        .clip(CircleShape)
                        .background(tokens.accent, CircleShape)
                        .clickable(onClick = onPlayPauseToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть",
                        tint = tokens.accentFg,
                        modifier = Modifier.size(if (compactHeight) 28.dp else 34.dp)
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

            Spacer(modifier = Modifier.height(spacingBottom))

            // Clean Bottom Utility Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (compactHeight) 2.dp else 6.dp),
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
                    icon = Icons.Rounded.Stop,
                    active = false,
                    onClick = onStopPlayback
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
    var rawDragX by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth * 0.9f, maxHeight).coerceAtLeast(72.dp)
    Box(
        modifier = Modifier
            .size(side)
            .graphicsLayer {
                val curX = rawDragX + dragX.value
                translationX = curX
                val ratio = (kotlin.math.abs(curX) / 500f).coerceIn(0f, 0.25f)
                alpha = 1f - ratio
            }
            .pointerInput(track?.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        rawDragX = (rawDragX + amount).coerceIn(-260f - dragX.value, 260f - dragX.value)
                    },
                    onDragEnd = {
                        val cur = (rawDragX + dragX.value).coerceIn(-260f, 260f)
                        rawDragX = 0f
                        if (cur < -95f) {
                            onNext()
                        } else if (cur > 95f) {
                            onPrevious()
                        }
                        scope.launch {
                            dragX.snapTo(cur)
                            dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        }
                    },
                    onDragCancel = {
                        val cur = (rawDragX + dragX.value).coerceIn(-260f, 260f)
                        rawDragX = 0f
                        scope.launch {
                            dragX.snapTo(cur)
                            dragX.animateTo(0f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        TrackArtwork(
            track = track,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = tokens.radiusLg,
            iconSize = if (side < 140.dp) 36.dp else 68.dp,
            hiRes = true
        )
    }
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
