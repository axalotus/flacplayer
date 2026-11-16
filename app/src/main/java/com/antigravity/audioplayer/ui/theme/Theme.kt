package com.antigravity.audioplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.audioplayer.data.storage.FlacThemeConfig
import com.antigravity.audioplayer.data.storage.ThemeMode
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
    val accent: Color,
    val accentFg: Color,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val fontFamily: FontFamily
)

val LocalFlacTheme = staticCompositionLocalOf { computeFlacThemeTokens(FlacThemeConfig()) }

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
    val lum = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (lum > 0.54f) Color.Black else Color.White
}

fun computeFlacThemeTokens(config: FlacThemeConfig): FlacThemeTokens {
    val accent = parseHexColor(config.accentColor, Color.White)
    val accentFg = contrastForeground(accent)

    val pBase = parseHexColor(config.panelColor, Color(0xFF0A0A0A))
    val bright = (config.panelBrightness.coerceIn(40, 220)) / 100f

    fun scaleChannel(ch: Float): Int = (ch * 255f * bright).roundToInt().coerceIn(0, 255)
    val pr = scaleChannel(pBase.red)
    val pg = scaleChannel(pBase.green)
    val pb = scaleChannel(pBase.blue)

    fun lift(c: Int, delta: Int, alpha: Float): Color = Color(
        red = (c + delta).coerceIn(0, 255) / 255f,
        green = (c + delta).coerceIn(0, 255) / 255f,
        blue = (c + delta).coerceIn(0, 255) / 255f,
        alpha = alpha.coerceIn(0.18f, 1f)
    )

    val pAlpha = (config.panelOpacity.coerceIn(20, 100)) / 100f
    val cAlpha = (config.cardOpacity.coerceIn(20, 100)) / 100f
    val bAlpha = (config.borderOpacity.coerceIn(0, 35)) / 100f

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
        line = Color.White.copy(alpha = bAlpha),
        lineStrong = Color.White.copy(alpha = (bAlpha * 2.2f).coerceAtMost(0.55f)),
        fg0 = Color.White,
        fg1 = Color(0xFFC8C8C8),
        fg2 = Color(0xFF828282),
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
    themeMode: ThemeMode = ThemeMode.AMOLED,
    themeConfig: FlacThemeConfig = FlacThemeConfig(),
    content: @Composable () -> Unit
) {
    val tokens = remember(themeConfig) { computeFlacThemeTokens(themeConfig) }

    val colorScheme = remember(tokens) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.accentFg,
            background = Color.Transparent,
            onBackground = tokens.fg0,
            surface = tokens.bg1,
            onSurface = tokens.fg0,
            surfaceVariant = tokens.cardBg,
            onSurfaceVariant = tokens.fg1,
            outline = tokens.lineStrong
        )
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

    CompositionLocalProvider(LocalFlacTheme provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
