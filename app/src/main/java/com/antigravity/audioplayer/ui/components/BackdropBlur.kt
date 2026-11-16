package com.antigravity.audioplayer.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

@Stable
class FlacBackdropState(
    val layer: GraphicsLayer
) {
    var positionInRoot by mutableStateOf(Offset.Zero)
    var isRecorded by mutableStateOf(false)
}

@Stable
data class FrostedBgInfo(
    val bitmap: ImageBitmap?,
    val rootWidthPx: Int,
    val rootHeightPx: Int,
    val rootOffset: Offset,
    val dimAlpha: Float
)

val LocalBgBackdrop = staticCompositionLocalOf<FlacBackdropState?> { null }
val LocalScreenBackdrop = staticCompositionLocalOf<FlacBackdropState?> { null }
val LocalFrostedBg = staticCompositionLocalOf<FrostedBgInfo?> { null }

@Composable
fun rememberFlacBackdropState(): FlacBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { FlacBackdropState(layer) }
}

/**
 * Records scrolling foreground content (WITHOUT background) into [state.layer]
 * so floating chrome can blur items passing underneath it.
 */
fun Modifier.flacBackdropSource(state: FlacBackdropState): Modifier = this
    .onGloballyPositioned { coords ->
        state.positionInRoot = coords.positionInRoot()
    }
    .drawWithContent {
        state.layer.record {
            this@drawWithContent.drawContent()
        }
        state.isRecorded = state.layer.size.width > 0 && state.layer.size.height > 0
        drawLayer(state.layer)
    }

private fun DrawScope.drawFrostedBgSlice(
    frostedBg: FrostedBgInfo?,
    myPositionInRoot: Offset
) {
    val bmp = frostedBg?.bitmap ?: return
    val rootW = frostedBg.rootWidthPx.toFloat()
    val rootH = frostedBg.rootHeightPx.toFloat()
    val w = size.width
    val h = size.height
    if (rootW <= 0f || rootH <= 0f || w <= 0f || h <= 0f) return

    val bmpW = bmp.width.toFloat()
    val bmpH = bmp.height.toFloat()
    val cropScale = max(rootW / bmpW, rootH / bmpH)
    val drawnW = bmpW * cropScale
    val drawnH = bmpH * cropScale
    val originX = (rootW - drawnW) / 2f + frostedBg.rootOffset.x
    val originY = (rootH - drawnH) / 2f + frostedBg.rootOffset.y

    val relX = (myPositionInRoot.x - originX) / cropScale
    val relY = (myPositionInRoot.y - originY) / cropScale
    val relW = w / cropScale
    val relH = h / cropScale

    val srcX = relX.roundToInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
    val srcY = relY.roundToInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
    val maxW = (bmp.width - srcX).coerceAtLeast(1)
    val maxH = (bmp.height - srcY).coerceAtLeast(1)
    val srcW = relW.roundToInt().coerceIn(1, maxW)
    val srcH = relH.roundToInt().coerceIn(1, maxH)

    drawImage(
        image = bmp,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    )
    if (frostedBg.dimAlpha > 0.01f) {
        drawRect(Color.Black.copy(alpha = frostedBg.dimAlpha))
    }
}

/**
 * Zero-shader per-card frosted glass over the pre-blurred background texture (locked 60/120 FPS in LazyColumns).
 */
fun Modifier.flacCardFrostedGlass(
    frostedBg: FrostedBgInfo?,
    tintColor: Color,
    shape: Shape
): Modifier = composed {
    val clipped = this.clip(shape)
    if (frostedBg?.bitmap == null) {
        return@composed clipped.drawBehind {
            drawRect(tintColor)
        }
    }

    var myPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    clipped
        .onGloballyPositioned { coords ->
            myPositionInRoot = coords.positionInRoot()
        }
        .drawBehind {
            drawFrostedBgSlice(frostedBg, myPositionInRoot)
            drawRect(tintColor)
        }
}

/**
 * High-FPS frosted glass for floating chrome (Mini-Player, Bottom Dock, Overlays) sampling
 * foreground scrolling content at 0.25x resolution (~64x less GPU fill-rate) over frosted bg.
 */
fun Modifier.flacGlassBackdrop(
    source: FlacBackdropState?,
    frostedBg: FrostedBgInfo?,
    blurRadius: Dp,
    tintColor: Color,
    shape: Shape
): Modifier = composed {
    val clipped = this.clip(shape)
    if (source == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurRadius <= 0.5.dp) {
        return@composed if (frostedBg?.bitmap != null) {
            Modifier.flacCardFrostedGlass(frostedBg, tintColor, shape)
        } else {
            clipped.drawBehind { drawRect(tintColor) }
        }
    }

    val density = LocalDensity.current
    val downscale = 0.25f
    val scaledRadiusPx = with(density) { (blurRadius.toPx() * downscale).coerceIn(0.5f, 22f) }
    val localBlurLayer = rememberGraphicsLayer()
    var myPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    clipped
        .onGloballyPositioned { coords ->
            myPositionInRoot = coords.positionInRoot()
        }
        .drawBehind {
            drawFrostedBgSlice(frostedBg, myPositionInRoot)

            val w = size.width
            val h = size.height
            val sw = (w * downscale).roundToInt().coerceAtLeast(1)
            val sh = (h * downscale).roundToInt().coerceAtLeast(1)
            if (source.isRecorded && w > 0f && h > 0f) {
                val dx = myPositionInRoot.x - source.positionInRoot.x
                val dy = myPositionInRoot.y - source.positionInRoot.y
                localBlurLayer.renderEffect = BlurEffect(
                    radiusX = scaledRadiusPx,
                    radiusY = scaledRadiusPx,
                    edgeTreatment = TileMode.Clamp
                )
                localBlurLayer.record(size = IntSize(sw, sh)) {
                    scale(scaleX = downscale, scaleY = downscale, pivot = Offset.Zero) {
                        translate(left = -dx, top = -dy) {
                            drawLayer(source.layer)
                        }
                    }
                }
                scale(scaleX = 1f / downscale, scaleY = 1f / downscale, pivot = Offset.Zero) {
                    drawLayer(localBlurLayer)
                }
            }
            drawRect(tintColor)
        }
}
