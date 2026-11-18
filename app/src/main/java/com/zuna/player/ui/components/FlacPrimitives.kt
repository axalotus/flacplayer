package com.zuna.player.ui.components

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zuna.player.data.storage.LibraryPreferences
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.LocalPowerSaver
import com.zuna.player.ui.theme.parseHexColor
import java.io.File
import kotlin.math.abs

@Composable
fun FlacGlassCard(
    modifier: Modifier = Modifier,
    color: Color = LocalFlacTheme.current.cardBg,
    borderColor: Color = LocalFlacTheme.current.panelLine,
    cornerRadius: Dp = LocalFlacTheme.current.radiusMd,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalFlacTheme.current
    val panelTokens = remember(tokens) { tokens.forPanel() }
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
    CompositionLocalProvider(LocalFlacTheme provides panelTokens) {
        Column(modifier = clickableModifier, content = content)
    }
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
                    .border(1.dp, tokens.panelLine, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = count.toString(),
                    color = tokens.panelFg2,
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
    val isFilled = accentFill || active
    val bg = if (isFilled) tokens.accent else tokens.cardBg
    val border = if (isFilled) tokens.accent else tokens.panelLine
    val fg = if (isFilled) tokens.accentFg else tokens.panelFg1
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
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
            fontWeight = if (isFilled) FontWeight.SemiBold else FontWeight.Medium,
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
    val thumbColor = if (checked) tokens.accentFg else tokens.panelFg1

    Box(
        modifier = modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.dp, if (checked) tokens.accent else tokens.panelLineStrong, CircleShape)
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

/**
 * Single unified gesture recognizer for horizontal and vertical sliders so tap and drag
 * never register competing pointer inputs or clash with parent scroll containers.
 */
private suspend fun PointerInputScope.detectAxisSliderGesture(
    isVertical: Boolean,
    onValueAtOffset: (Offset) -> Unit,
    onFinished: (() -> Unit)? = null
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val lockThreshold = if (isVertical) slop * 0.45f else slop
        var dragLocked = false
        var maxDist = 0f

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) {
                if (dragLocked) onFinished?.invoke()
                break
            }
            if (!change.pressed) {
                if (!dragLocked && maxDist < slop) {
                    change.consume()
                    onValueAtOffset(change.position)
                    onFinished?.invoke()
                } else if (dragLocked) {
                    onFinished?.invoke()
                }
                break
            }
            val dx = abs(change.position.x - down.position.x)
            val dy = abs(change.position.y - down.position.y)
            maxDist = maxOf(maxDist, dx, dy)
            val primaryDelta = if (isVertical) dy else dx
            val crossDelta = if (isVertical) dx else dy

            if (!dragLocked) {
                if (crossDelta > slop && crossDelta > primaryDelta) {
                    break
                }
                if (primaryDelta >= lockThreshold) {
                    dragLocked = true
                }
            }
            if (dragLocked) {
                change.consume()
                onValueAtOffset(change.position)
            }
        }
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
                detectAxisSliderGesture(
                    isVertical = false,
                    onValueAtOffset = { offset -> latestOnChange(pxToVal(offset.x)) },
                    onFinished = { latestOnFinished?.invoke() }
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
                detectAxisSliderGesture(
                    isVertical = true,
                    onValueAtOffset = { offset -> latestOnChange(yToValue(offset.y)) }
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
    val panelTokens = remember(tokens) { tokens.forPanel() }
    val artSize = if (compact) 42.dp else 50.dp
    val artCorner = if (compact) 8.dp else 10.dp
    val vPad = if (compact) 6.dp else 9.dp
    val rowShape = remember(tokens.radiusMd) { RoundedCornerShape(tokens.radiusMd) }
    val frostedBg = if (!isCurrent && tokens.config.panelBlurPx > 0) LocalFrostedBg.current else null

    val rowBoxModifier = if (isCurrent) {
        modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(tokens.accent, rowShape)
            .border(1.dp, tokens.accent, rowShape)
    } else {
        modifier
            .fillMaxWidth()
            .flacCardFrostedGlass(
                frostedBg = frostedBg,
                tintColor = tokens.cardBg,
                shape = rowShape
            )
            .border(1.dp, tokens.panelLine, rowShape)
    }

    val primaryTextColor = if (isCurrent) tokens.accentFg else panelTokens.fg0
    val secondaryTextColor = if (isCurrent) tokens.accentFg.copy(alpha = 0.78f) else panelTokens.fg2

    Row(
        modifier = rowBoxModifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (indexNumber != null) {
            Text(
                text = indexNumber.toString(),
                color = secondaryTextColor,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.width(22.dp)
            )
        }
        TrackArtwork(
            track = track,
            modifier = Modifier.size(artSize),
            cornerRadius = artCorner,
            iconSize = 19.dp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = track.title,
                color = primaryTextColor,
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = secondaryTextColor,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showQualityBadge) {
                Text(
                    text = track.qualityBadgeText,
                    color = secondaryTextColor.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        FlacIconButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = "Действия",
            onClick = onMoreClick,
            size = 32.dp,
            iconSize = 18.dp,
            tint = secondaryTextColor
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
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    solidColor: String = "#000000"
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val powerSaver = LocalPowerSaver.current
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val embeddedCovers by prefs.isEmbeddedCoversEnabled.collectAsState()
    val artGen by artworkGeneration.collectAsState()
    val isMono = colorMode != "color"
    val dimAlpha = (dimPercent.coerceIn(15, 95)) / 100f
    val isApi31Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveMode = if (powerSaver.disableDynamicBg && mode == "custom" && isVideo) "cover" else mode

    val bgBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = "$effectiveMode|$embeddedCovers|$artGen|${powerSaver.useThumbCovers}",
        key2 = currentTrack?.id,
        key3 = if (isApi31Plus) "$customPath|$isVideo|${powerSaver.disableDynamicBg}" else "$customPath|$isVideo|$blurPx|$isMono|${powerSaver.disableDynamicBg}"
    ) {
        value = when (effectiveMode) {
            "cover" -> {
                val track = currentTrack
                if (track != null && embeddedCovers) {
                    val src = if (powerSaver.useThumbCovers) {
                        loadTrackArtwork(context, track) ?: loadTrackArtworkHiRes(context, track)
                    } else {
                        loadTrackArtworkHiRes(context, track)
                    }
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

    val baseBgColor = remember(effectiveMode, solidColor, tokens.bg0) {
        if (effectiveMode == "none") parseHexColor(solidColor, tokens.bg0) else tokens.bg0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBgColor)
    ) {
        if (!powerSaver.disableDynamicBg && effectiveMode == "custom" && isVideo && customPath.isNotBlank() && File(customPath).exists()) {
            LoopingMutedVideoBackground(
                path = customPath,
                blurPx = blurPx,
                monochrome = isMono,
                isActive = isActive,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val bmp = bgBitmap
            if (bmp != null) {
                val blurDp = if (isApi31Plus && blurPx > 0 && !powerSaver.disableBlur) (blurPx * 0.55f).dp else 0.dp
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

private class VideoBgController(
    private val textureView: TextureView
) : TextureView.SurfaceTextureListener {
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var currentPath: String = ""
    private var isPrepared = false
    private var shouldPlay = true
    private var lastMono: Boolean? = null
    private var lastBlurPx: Int = -1

    fun updateVisuals(blurPx: Int, monochrome: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lastBlurPx != blurPx) {
            lastBlurPx = blurPx
            val r = (blurPx * 0.9f).coerceIn(0f, 90f)
            textureView.setRenderEffect(
                if (r > 0.5f) AndroidRenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                else null
            )
        }
        if (lastMono != monochrome) {
            lastMono = monochrome
            val paint = if (monochrome) {
                android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { setSaturation(0f) }
                    )
                }
            } else null
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    fun updatePlayback(path: String, active: Boolean) {
        shouldPlay = active
        if (path != currentPath) {
            currentPath = path
            if (surface != null) {
                preparePlayer()
            }
            return
        }
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        try {
            if (shouldPlay && !mp.isPlaying) {
                mp.start()
            } else if (!shouldPlay && mp.isPlaying) {
                mp.pause()
            }
        } catch (_: Exception) {
        }
    }

    private fun applyCenterCrop(vw: Int, vh: Int, tw: Int, th: Int) {
        if (vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return
        val sx = tw.toFloat() / vw
        val sy = th.toFloat() / vh
        val scaleX: Float
        val scaleY: Float
        if (sx > sy) {
            scaleX = 1f
            scaleY = (sx / sy).coerceAtLeast(1f)
        } else {
            scaleY = 1f
            scaleX = (sy / sx).coerceAtLeast(1f)
        }
        val mat = Matrix()
        mat.setScale(scaleX, scaleY, tw / 2f, th / 2f)
        textureView.setTransform(mat)
    }

    private fun preparePlayer() {
        releasePlayer()
        val targetSurface = surface ?: return
        val path = currentPath
        if (path.isBlank()) return
        isPrepared = false
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(path)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setSurface(targetSurface)
            mp.setOnPreparedListener { ready ->
                if (mediaPlayer !== ready) {
                    ready.runCatching { release() }
                    return@setOnPreparedListener
                }
                isPrepared = true
                applyCenterCrop(ready.videoWidth, ready.videoHeight, surfaceWidth, surfaceHeight)
                if (shouldPlay) {
                    ready.start()
                }
            }
            mp.setOnVideoSizeChangedListener { ready, width, height ->
                if (mediaPlayer === ready) {
                    applyCenterCrop(width, height, surfaceWidth, surfaceHeight)
                }
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        isPrepared = false
        val mp = mediaPlayer
        mediaPlayer = null
        mp?.runCatching {
            if (isPlaying) stop()
        }
        mp?.runCatching {
            release()
        }
    }

    fun releaseAll() {
        releasePlayer()
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        surface = Surface(st)
        surfaceWidth = width
        surfaceHeight = height
        preparePlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            applyCenterCrop(mp.videoWidth, mp.videoHeight, width, height)
        }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releaseAll()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}

@Composable
private fun LoopingMutedVideoBackground(
    path: String,
    blurPx: Int,
    monochrome: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_PAUSE -> isLifecycleResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shouldPlay = isActive && isLifecycleResumed
    val controllerHolder = remember { mutableStateOf<VideoBgController?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            controllerHolder.value?.releaseAll()
            controllerHolder.value = null
        }
    }

    AndroidView(
        modifier = modifier,
        update = { tv ->
            val ctrl = (tv.tag as? VideoBgController) ?: controllerHolder.value
            ctrl?.updateVisuals(blurPx, monochrome)
            ctrl?.updatePlayback(path, shouldPlay)
        },
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                val ctrl = VideoBgController(tv)
                tv.tag = ctrl
                tv.surfaceTextureListener = ctrl
                controllerHolder.value = ctrl
                ctrl.updateVisuals(blurPx, monochrome)
                ctrl.updatePlayback(path, shouldPlay)
            }
        }
    )
}
