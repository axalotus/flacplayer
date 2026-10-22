package com.antigravity.audioplayer.desktop.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBackground = Color(0xFF0A0A0A)
val ObsidianSurface = Color(0xFF141414)
val ObsidianSurfaceVariant = Color(0xFF1E1E1E)
val ObsidianCard = Color(0xFF181818)
val ObsidianDivider = Color(0xFF282828)

val AccentPrimary = Color(0xFF00F5D4)
val AccentSecondary = Color(0xFF06D6A0)
val AccentOrange = Color(0xFFFF9F1C)
val AccentPink = Color(0xFFF72585)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00382E),
    onPrimaryContainer = AccentPrimary,
    secondary = AccentSecondary,
    onSecondary = Color.Black,
    background = ObsidianBackground,
    onBackground = Color(0xFFECECEC),
    surface = ObsidianSurface,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = ObsidianDivider
)

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
