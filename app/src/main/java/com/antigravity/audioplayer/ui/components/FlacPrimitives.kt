package com.antigravity.audioplayer.ui.components

import android.graphics.Matrix
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme
import java.io.File

@Composable
fun FlacGlassCard(
    modifier: Modifier = Modifier,
    color: Color = LocalFlacTheme.current.cardBg,
    borderColor: Color = LocalFlacTheme.current.line,
    cornerRadius: Dp = LocalFlacTheme.current.radiusMd,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalFlacTheme.current
    val shape = RoundedCornerShape(cornerRadius)
    val frostedBg = if (tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null
    val base = modifier
        .flacCardFrostedGlass(
            frostedBg = frostedBg,
            tintColor = color,
            shape = shape
        )
        .border(1.dp, borderColor, shape)
    val clickableModifier = if (onClick != null) {
        base.clickable(onClick = onClick)
    } else {
        base
    }
    Column(modifier = clickableModifier, content = content)
}

@Composable
fun FlacSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onSeeAllClick: (() -> Unit)? = null
) {
    val tokens = LocalFlacTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onSeeAllClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSeeAllClick
                ) else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = tokens.fg0,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp
            )
            if (onSeeAllClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = tokens.fg2,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tokens.bg2)
                    .border(1.dp, tokens.line, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = count.toString(),
                    color = tokens.fg2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun FlacPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    active: Boolean = false,
    accentFill: Boolean = false
) {
    val tokens = LocalFlacTheme.current
    val bg = when {
        accentFill -> tokens.accent
        active -> tokens.accent.copy(alpha = 0.16f)
        else -> tokens.bg2
    }
    val border = when {
        accentFill -> tokens.accent
        active -> tokens.accent.copy(alpha = 0.7f)
        else -> tokens.line
    }
    val fg = when {
        accentFill -> tokens.accentFg
        active -> tokens.accent
        else -> tokens.fg0
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (accentFill || active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FlacIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp,
    tint: Color = LocalFlacTheme.current.fg0,
    backgroundColor: Color = Color.Transparent,
    borderColor: Color = Color.Transparent
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor, shape)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.dp, borderColor, shape)
                } else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun FlacToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalFlacTheme.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) tokens.accent else tokens.bg3,
        animationSpec = tween(140),
        label = "flacToggleTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 2.dp,
        animationSpec = tween(140),
        label = "flacToggleThumb"
    )
    val thumbColor = if (checked) tokens.accentFg else tokens.fg1

    Box(
        modifier = modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.dp, if (checked) tokens.accent else tokens.lineStrong, CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
fun FlacFlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 6.dp
) {
    val tokens = LocalFlacTheme.current
    var widthPx by remember { mutableIntStateOf(1) }
    val minVal = valueRange.start
    val maxVal = valueRange.endInclusive
    val span = (maxVal - minVal).coerceAtLeast(0.0001f)
    val fraction = ((value - minVal) / span).coerceIn(0f, 1f)

    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)

    fun pxToVal(x: Float): Float {
        val f = (x / widthPx.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return minVal + f * span
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, minVal, maxVal) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    latestOnChange(pxToVal(offset.x))
                    latestOnFinished?.invoke()
                }
            }
            .pointerInput(enabled, minVal, maxVal) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> latestOnChange(pxToVal(offset.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latestOnChange(pxToVal(change.position.x))
                    },
                    onDragEnd = { latestOnFinished?.invoke() },
                    onDragCancel = { latestOnFinished?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
            val cy = size.height / 2f
            val h = trackHeight.toPx()
            val tRad = thumbRadius.toPx()
            val fillW = (size.width * fraction).coerceIn(0f, size.width)

            drawRoundRect(
                color = tokens.lineStrong.copy(alpha = 0.45f),
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
            if (fillW > 0.5f) {
                drawRoundRect(
                    color = tokens.accent,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(fillW, h),
                    cornerRadius = CornerRadius(h / 2f, h / 2f)
                )
            }
            val cx = fillW.coerceIn(tRad, (size.width - tRad).coerceAtLeast(tRad))
            drawCircle(
                color = tokens.accent,
                radius = tRad,
                center = Offset(cx, cy)
            )
        }
    }
}

@Composable
fun FlacVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -12f..12f,
    centerZeroMarker: Boolean = true
) {
    val tokens = LocalFlacTheme.current
    var heightPx by remember { mutableIntStateOf(1) }
    val minVal = valueRange.start
    val maxVal = valueRange.endInclusive
    val span = (maxVal - minVal).coerceAtLeast(0.0001f)
    val fractionFromBottom = ((value - minVal) / span).coerceIn(0f, 1f)

    val latestOnChange by rememberUpdatedState(onValueChange)

    fun yToValue(y: Float): Float {
        val f = (1f - (y / heightPx.toFloat().coerceAtLeast(1f))).coerceIn(0f, 1f)
        return minVal + f * span
    }

    Box(
        modifier = modifier
            .width(30.dp)
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .pointerInput(minVal, maxVal) {
                detectTapGestures { offset -> latestOnChange(yToValue(offset.y)) }
            }
            .pointerInput(minVal, maxVal) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> latestOnChange(yToValue(offset.y)) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        latestOnChange(yToValue(change.position.y))
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxHeight().width(18.dp)) {
            val cx = size.width / 2f
            val trackW = 4.dp.toPx()
            val tRad = 6.dp.toPx()
            val yThumb = ((1f - fractionFromBottom) * size.height).coerceIn(tRad, (size.height - tRad).coerceAtLeast(tRad))

            drawRoundRect(
                color = tokens.lineStrong.copy(alpha = 0.45f),
                topLeft = Offset(cx - trackW / 2f, 0f),
                size = Size(trackW, size.height),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
            )

            if (centerZeroMarker && minVal < 0f && maxVal > 0f) {
                val zeroFraction = ((0f - minVal) / span).coerceIn(0f, 1f)
                val zeroY = (1f - zeroFraction) * size.height
                drawLine(
                    color = tokens.fg2.copy(alpha = 0.55f),
                    start = Offset(cx - 7.dp.toPx(), zeroY),
                    end = Offset(cx + 7.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx()
                )
                val topFill = minOf(yThumb, zeroY)
                val botFill = maxOf(yThumb, zeroY)
                if (botFill - topFill > 1f) {
                    drawRoundRect(
                        color = tokens.accent,
                        topLeft = Offset(cx - trackW / 2f, topFill),
                        size = Size(trackW, botFill - topFill),
                        cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
                    )
                }
            } else {
                val fillH = size.height - yThumb
                if (fillH > 1f) {
                    drawRoundRect(
                        color = tokens.accent,
                        topLeft = Offset(cx - trackW / 2f, yThumb),
                        size = Size(trackW, fillH),
                        cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
                    )
                }
            }

            drawCircle(
                color = tokens.accent,
                radius = tRad,
                center = Offset(cx, yThumb)
            )
        }
    }
}

@Composable
fun TrackRow(
    track: AudioFileMetadata,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    indexNumber: Int? = null,
    showQualityBadge: Boolean = false,
    compact: Boolean = false
) {
    val tokens = LocalFlacTheme.current
    val artSize = if (compact) 38.dp else 44.dp
    val vPad = if (compact) 7.dp else 9.dp
    val rowBg = if (isCurrent) tokens.accent.copy(alpha = 0.12f) else Color.Transparent
    val borderCol = if (isCurrent) tokens.accent.copy(alpha = 0.35f) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radiusSm))
            .background(rowBg, RoundedCornerShape(tokens.radiusSm))
            .then(
                if (borderCol != Color.Transparent) {
                    Modifier.border(1.dp, borderCol, RoundedCornerShape(tokens.radiusSm))
                } else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        if (indexNumber != null) {
            Text(
                text = indexNumber.toString(),
                color = if (isCurrent) tokens.accent else tokens.fg2,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.width(24.dp)
            )
        }
        TrackArtwork(
            track = track,
            modifier = Modifier.size(artSize),
            cornerRadius = tokens.radiusSm,
            iconSize = 18.dp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isCurrent) tokens.accent else tokens.fg0,
                fontSize = if (compact) 13.5.sp else 14.5.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                append(track.artist)
                if (track.album.isNotBlank()) {
                    append(" • ")
                    append(track.album)
                }
            }
            Text(
                text = sub,
                color = tokens.fg2,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showQualityBadge) {
                Text(
                    text = track.qualityBadgeText,
                    color = tokens.fg2.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = formatDurationMs(track.durationMs),
            color = tokens.fg2,
            fontSize = 11.5.sp
        )
        FlacIconButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = "Действия",
            onClick = onMoreClick,
            size = 32.dp,
            iconSize = 18.dp,
            tint = tokens.fg2
        )
    }
}

/**
 * Full-HD background renderer: uses native GPU continuous RenderEffect blur + hardware monochrome
 * ColorMatrix on API 31+ (smooth 0..90 slider with crisp HD source), falling back to software blur on older APIs.
 */
@Composable
fun FlacBackgroundLayer(
    mode: String,
    colorMode: String,
    customPath: String,
    isVideo: Boolean,
    blurPx: Int,
    dimPercent: Int,
    currentTrack: AudioFileMetadata?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val isMono = colorMode != "color"
    val dimAlpha = (dimPercent.coerceIn(15, 95)) / 100f
    val isApi31Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val bgBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = mode,
        key2 = currentTrack?.id,
        key3 = if (isApi31Plus) "$customPath|$isVideo" else "$customPath|$isVideo|$blurPx|$isMono"
    ) {
        value = when (mode) {
            "cover" -> {
                val track = currentTrack
                if (track != null) {
                    val src = loadTrackArtworkHiRes(context, track)
                    if (isApi31Plus) src?.asImageBitmap()
                    else getLegacyBlurredTexture("cover:${track.id}", src, blurPx, isMono)?.asImageBitmap()
                } else null
            }
            "custom" -> {
                if (!isVideo && customPath.isNotBlank()) {
                    val src = loadCustomBackgroundBitmap(customPath)
                    if (isApi31Plus) src?.asImageBitmap()
                    else getLegacyBlurredTexture("custom:$customPath", src, blurPx, isMono)?.asImageBitmap()
                } else null
            }
            else -> null
        }
    }

    val monoColorFilter = remember(isMono) {
        if (isMono) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bg0)
    ) {
        if (mode == "custom" && isVideo && customPath.isNotBlank() && File(customPath).exists()) {
            LoopingMutedVideoBackground(
                path = customPath,
                blurPx = blurPx,
                monochrome = isMono,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val bmp = bgBitmap
            if (bmp != null) {
                val blurDp = if (isApi31Plus && blurPx > 0) (blurPx * 0.55f).dp else 0.dp
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (isApi31Plus) monoColorFilter else null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .then(
                            if (blurDp > 0.dp) {
                                Modifier.blur(blurDp, BlurredEdgeTreatment.Rectangle)
                            } else Modifier
                        )
                )
            }
        }
        if (mode != "none") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha))
            )
        }
    }
}

@Composable
private fun LoopingMutedVideoBackground(
    path: String,
    blurPx: Int,
    monochrome: Boolean,
    modifier: Modifier = Modifier
) {
    val mediaPlayerHolder = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(path) {
        onDispose {
            mediaPlayerHolder.value?.runCatching {
                stop()
                release()
            }
            mediaPlayerHolder.value = null
        }
    }

    AndroidView(
        modifier = modifier,
        update = { tv ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val r = (blurPx * 0.9f).coerceIn(0f, 90f)
                tv.setRenderEffect(
                    if (r > 0.5f) AndroidRenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                    else null
                )
            }
            if (monochrome) {
                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { setSaturation(0f) }
                    )
                }
                tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                tv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        },
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var mp: MediaPlayer? = null

                    private fun applyCenterCrop(vw: Int, vh: Int, tw: Int, th: Int) {
                        if (vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return
                        val sx = vw.toFloat() / tw
                        val sy = vh.toFloat() / th
                        val scaleX: Float
                        val scaleY: Float
                        if (sx > sy) {
                            scaleX = 1f
                            scaleY = (tw * sy / (vw)).coerceAtLeast(1f)
                        } else {
                            scaleY = 1f
                            scaleX = (th * sx / (vh)).coerceAtLeast(1f)
                        }
                        val mat = Matrix()
                        mat.setScale(scaleX, scaleY, tw / 2f, th / 2f)
                        setTransform(mat)
                    }

                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        try {
                            val player = MediaPlayer().apply {
                                setDataSource(path)
                                isLooping = true
                                setVolume(0f, 0f)
                                setSurface(Surface(surface))
                                setOnPreparedListener { ready ->
                                    applyCenterCrop(ready.videoWidth, ready.videoHeight, width, height)
                                    ready.start()
                                }
                                prepareAsync()
                            }
                            mp = player
                            mediaPlayerHolder.value = player
                        } catch (_: Exception) {
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        mp?.let { applyCenterCrop(it.videoWidth, it.videoHeight, width, height) }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        mp?.runCatching {
                            stop()
                            release()
                        }
                        mp = null
                        mediaPlayerHolder.value = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        }
    )
}
