package com.zuna.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zuna.player.data.storage.FlacThemeConfig
import com.zuna.player.data.storage.PowerSaverEffectiveConfig
import kotlin.math.roundToInt

@Immutable
data class FlacThemeTokens(
    val config: FlacThemeConfig,
    val bg0: Color,
    val bg1: Color,
    val cardBg: Color,
    val bg2: Color,
    val bg3: Color,
    val bgHover: Color,
    val line: Color,
    val lineStrong: Color,
    val fg0: Color,
    val fg1: Color,
    val fg2: Color,
    val panelLine: Color,
    val panelLineStrong: Color,
    val panelFg0: Color,
    val panelFg1: Color,
    val panelFg2: Color,
    val isLightCanvas: Boolean,
    val isLightPanel: Boolean,
    val accent: Color,
    val accentFg: Color,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val fontFamily: FontFamily
) {
    /** Returns tokens where fg0/fg1/fg2 and border lines match the panel surface instead of screen canvas. */
    fun forPanel(): FlacThemeTokens = if (
        fg0 == panelFg0 && fg1 == panelFg1 && fg2 == panelFg2 &&
        line == panelLine && lineStrong == panelLineStrong
    ) {
        this
    } else {
        copy(
            fg0 = panelFg0,
            fg1 = panelFg1,
            fg2 = panelFg2,
            line = panelLine,
            lineStrong = panelLineStrong,
            isLightCanvas = isLightPanel
        )
    }

    /** Recomputes canvas & panel contrast tokens against an alternate solid backdrop (e.g. PlayerOverlay). */
    fun forBackdrop(mode: String, solidHex: String): FlacThemeTokens {
        val canvasColor = if (mode == "none") parseHexColor(solidHex, Color.Black) else Color(0xFF0A0A0A)
        val lightCanvas = isLightSurface(canvasColor)
        val canvasInk = inkForSurface(lightCanvas, config.borderOpacity)
        val panelSurface = computeScaledPanelColor(config)
        val pAlpha = (config.panelOpacity.coerceIn(20, 100)) / 100f
        val effectivePanel = lerp(canvasColor, panelSurface, pAlpha)
        val lightPanel = isLightSurface(effectivePanel)
        val panelInk = inkForSurface(lightPanel, config.borderOpacity)
        return copy(
            fg0 = canvasInk.fg0,
            fg1 = canvasInk.fg1,
            fg2 = canvasInk.fg2,
            line = canvasInk.line,
            lineStrong = canvasInk.lineStrong,
            panelFg0 = panelInk.fg0,
            panelFg1 = panelInk.fg1,
            panelFg2 = panelInk.fg2,
            panelLine = panelInk.line,
            panelLineStrong = panelInk.lineStrong,
            isLightCanvas = lightCanvas,
            isLightPanel = lightPanel
        )
    }
}

private data class SurfaceInk(
    val fg0: Color,
    val fg1: Color,
    val fg2: Color,
    val line: Color,
    val lineStrong: Color
)

private fun relativeLuminance(color: Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

private fun isLightSurface(color: Color): Boolean = relativeLuminance(color) > 0.50f

private fun inkForSurface(isLight: Boolean, borderOpacity: Int): SurfaceInk {
    val bAlpha = (borderOpacity.coerceIn(0, 35)) / 100f
    return if (isLight) {
        val lineAlpha = if (bAlpha <= 0f) 0f else (bAlpha * 1.3f).coerceAtMost(0.32f)
        val strongAlpha = if (bAlpha <= 0f) 0f else (bAlpha * 2.4f).coerceAtMost(0.55f)
        SurfaceInk(
            fg0 = Color(0xFF111111),
            fg1 = Color(0xFF3E3E3E),
            fg2 = Color(0xFF6B6B6B),
            line = Color.Black.copy(alpha = lineAlpha),
            lineStrong = Color.Black.copy(alpha = strongAlpha)
        )
    } else {
        SurfaceInk(
            fg0 = Color.White,
            fg1 = Color(0xFFC8C8C8),
            fg2 = Color(0xFF828282),
            line = Color.White.copy(alpha = bAlpha),
            lineStrong = Color.White.copy(alpha = (bAlpha * 2.2f).coerceAtMost(0.55f))
        )
    }
}

private fun computeScaledPanelColor(config: FlacThemeConfig): Color {
    val pBase = parseHexColor(config.panelColor, Color(0xFF0A0A0A))
    val bright = (config.panelBrightness.coerceIn(40, 220)) / 100f
    return Color(
        red = (pBase.red * bright).coerceIn(0f, 1f),
        green = (pBase.green * bright).coerceIn(0f, 1f),
        blue = (pBase.blue * bright).coerceIn(0f, 1f),
        alpha = 1f
    )
}

val LocalFlacTheme = staticCompositionLocalOf { computeFlacThemeTokens(FlacThemeConfig()) }
val LocalPowerSaver = staticCompositionLocalOf { PowerSaverEffectiveConfig() }

fun parseHexColor(hex: String, fallback: Color = Color(0xFF0A0A0A)): Color {
    val clean = hex.trim().removePrefix("#")
    val full = if (clean.length == 3) clean.map { "$it$it" }.joinToString("") else clean
    if (full.length != 6) return fallback
    return try {
        val rgb = full.toLong(16).toInt()
        Color(0xFF000000.toInt() or rgb)
    } catch (_: Exception) {
        fallback
    }
}

fun contrastForeground(color: Color): Color {
    return if (relativeLuminance(color) > 0.54f) Color.Black else Color.White
}

fun computeFlacThemeTokens(config: FlacThemeConfig): FlacThemeTokens {
    val accent = parseHexColor(config.accentColor, Color.White)
    val accentFg = contrastForeground(accent)

    val panelSurface = computeScaledPanelColor(config)
    val pr = (panelSurface.red * 255f).roundToInt().coerceIn(0, 255)
    val pg = (panelSurface.green * 255f).roundToInt().coerceIn(0, 255)
    val pb = (panelSurface.blue * 255f).roundToInt().coerceIn(0, 255)
    val lightPanelSurface = isLightSurface(panelSurface)

    fun lift(c: Int, delta: Int, alpha: Float): Color {
        val signedDelta = if (lightPanelSurface) -delta else delta
        return Color(
            red = (c + signedDelta).coerceIn(0, 255) / 255f,
            green = (c + signedDelta).coerceIn(0, 255) / 255f,
            blue = (c + signedDelta).coerceIn(0, 255) / 255f,
            alpha = alpha.coerceIn(0.18f, 1f)
        )
    }

    val pAlpha = (config.panelOpacity.coerceIn(20, 100)) / 100f
    val cAlpha = (config.cardOpacity.coerceIn(20, 100)) / 100f

    val appCanvas = if (config.appBgMode == "none") {
        parseHexColor(config.appBgSolidColor, Color.Black)
    } else {
        Color(0xFF0A0A0A)
    }
    val lightCanvas = isLightSurface(appCanvas)
    val canvasInk = inkForSurface(lightCanvas, config.borderOpacity)
    val effectivePanel = lerp(appCanvas, panelSurface, pAlpha)
    val lightPanel = isLightSurface(effectivePanel)
    val panelInk = inkForSurface(lightPanel, config.borderOpacity)

    val rad = config.borderRadiusPx.coerceIn(2, 22)
    val ff = when (config.fontFamily) {
        "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }

    return FlacThemeTokens(
        config = config,
        bg0 = Color.Black,
        bg1 = lift(pr, 0, pAlpha),
        cardBg = lift(pr, 4, cAlpha),
        bg2 = lift(pr, 16, (pAlpha + 0.05f).coerceAtMost(1f)),
        bg3 = lift(pr, 28, 0.92f),
        bgHover = lift(pr, 38, 0.96f),
        line = canvasInk.line,
        lineStrong = canvasInk.lineStrong,
        fg0 = canvasInk.fg0,
        fg1 = canvasInk.fg1,
        fg2 = canvasInk.fg2,
        panelLine = panelInk.line,
        panelLineStrong = panelInk.lineStrong,
        panelFg0 = panelInk.fg0,
        panelFg1 = panelInk.fg1,
        panelFg2 = panelInk.fg2,
        isLightCanvas = lightCanvas,
        isLightPanel = lightPanel,
        accent = accent,
        accentFg = accentFg,
        radiusSm = (rad * 0.68f).roundToInt().coerceAtLeast(2).dp,
        radiusMd = rad.dp,
        radiusLg = (rad * 1.25f).roundToInt().coerceAtLeast(4).dp,
        fontFamily = ff
    )
}

@Composable
fun AntigravityPlayerTheme(
    themeConfig: FlacThemeConfig = FlacThemeConfig(),
    powerSaver: PowerSaverEffectiveConfig = PowerSaverEffectiveConfig(),
    content: @Composable () -> Unit
) {
    val effectiveThemeConfig = remember(themeConfig, powerSaver) {
        when {
            powerSaver.disableBlur -> themeConfig.copy(
                panelBlurPx = 0,
                appBgBlurPx = 0,
                fsBgBlurPx = 0,
                lyricsBlurIntensity = 0
            )
            powerSaver.isBalanced -> themeConfig.copy(
                panelBlurPx = (themeConfig.panelBlurPx / 2).coerceAtMost(10),
                appBgBlurPx = (themeConfig.appBgBlurPx * 0.45f).roundToInt(),
                fsBgBlurPx = (themeConfig.fsBgBlurPx * 0.45f).roundToInt(),
                lyricsBlurIntensity = (themeConfig.lyricsBlurIntensity / 2)
            )
            else -> themeConfig
        }
    }
    val tokens = remember(effectiveThemeConfig) { computeFlacThemeTokens(effectiveThemeConfig) }

    val colorScheme = remember(tokens) {
        if (tokens.isLightCanvas) {
            lightColorScheme(
                primary = tokens.accent,
                onPrimary = tokens.accentFg,
                background = Color.Transparent,
                onBackground = tokens.fg0,
                surface = tokens.bg1,
                onSurface = tokens.panelFg0,
                surfaceVariant = tokens.cardBg,
                onSurfaceVariant = tokens.panelFg1,
                outline = tokens.lineStrong
            )
        } else {
            darkColorScheme(
                primary = tokens.accent,
                onPrimary = tokens.accentFg,
                background = Color.Transparent,
                onBackground = tokens.fg0,
                surface = tokens.bg1,
                onSurface = tokens.panelFg0,
                surfaceVariant = tokens.cardBg,
                onSurfaceVariant = tokens.panelFg1,
                outline = tokens.lineStrong
            )
        }
    }

    val baseTypo = MaterialTheme.typography
    val typography = remember(tokens.fontFamily) {
        Typography(
            displayLarge = baseTypo.displayLarge.copy(fontFamily = tokens.fontFamily),
            headlineLarge = baseTypo.headlineLarge.copy(fontFamily = tokens.fontFamily),
            headlineMedium = baseTypo.headlineMedium.copy(fontFamily = tokens.fontFamily),
            headlineSmall = baseTypo.headlineSmall.copy(fontFamily = tokens.fontFamily),
            titleLarge = baseTypo.titleLarge.copy(fontFamily = tokens.fontFamily),
            titleMedium = baseTypo.titleMedium.copy(fontFamily = tokens.fontFamily),
            titleSmall = baseTypo.titleSmall.copy(fontFamily = tokens.fontFamily),
            bodyLarge = baseTypo.bodyLarge.copy(fontFamily = tokens.fontFamily),
            bodyMedium = baseTypo.bodyMedium.copy(fontFamily = tokens.fontFamily),
            bodySmall = baseTypo.bodySmall.copy(fontFamily = tokens.fontFamily),
            labelLarge = baseTypo.labelLarge.copy(fontFamily = tokens.fontFamily),
            labelMedium = baseTypo.labelMedium.copy(fontFamily = tokens.fontFamily),
            labelSmall = baseTypo.labelSmall.copy(fontFamily = tokens.fontFamily)
        )
    }

    CompositionLocalProvider(
        LocalFlacTheme provides tokens,
        LocalPowerSaver provides powerSaver
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
