package com.antigravity.audioplayer.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.antigravity.audioplayer.data.storage.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = M3DarkPrimary,
    onPrimary = M3DarkOnPrimary,
    primaryContainer = M3DarkPrimaryContainer,
    onPrimaryContainer = M3DarkOnPrimaryContainer,
    secondary = M3DarkSecondary,
    onSecondary = M3DarkOnSecondary,
    secondaryContainer = M3DarkSecondaryContainer,
    onSecondaryContainer = M3DarkOnSecondaryContainer,
    tertiary = M3DarkTertiary,
    onTertiary = M3DarkOnTertiary,
    background = M3DarkBackground,
    onBackground = M3DarkOnBackground,
    surface = M3DarkSurface,
    onSurface = M3DarkOnSurface,
    surfaceVariant = M3DarkSurfaceVariant,
    onSurfaceVariant = M3DarkOnSurfaceVariant,
    outline = M3DarkOutline
)

private val AmoledColorScheme = darkColorScheme(
    primary = M3DarkPrimary,
    onPrimary = M3DarkOnPrimary,
    primaryContainer = M3DarkPrimaryContainer,
    onPrimaryContainer = M3DarkOnPrimaryContainer,
    secondary = M3DarkSecondary,
    onSecondary = M3DarkOnSecondary,
    secondaryContainer = M3DarkSecondaryContainer,
    onSecondaryContainer = M3DarkOnSecondaryContainer,
    tertiary = M3DarkTertiary,
    onTertiary = M3DarkOnTertiary,
    background = AmoledBackground,
    onBackground = AmoledOnSurface,
    surface = AmoledSurface,
    onSurface = AmoledOnSurface,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = AmoledOnSurfaceVariant,
    outline = M3DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = M3LightPrimary,
    onPrimary = M3LightOnPrimary,
    primaryContainer = M3LightPrimaryContainer,
    onPrimaryContainer = M3LightOnPrimaryContainer,
    secondary = M3LightSecondary,
    onSecondary = M3LightOnSecondary,
    secondaryContainer = M3LightSecondaryContainer,
    onSecondaryContainer = M3LightOnSecondaryContainer,
    background = M3LightBackground,
    onBackground = M3LightOnBackground,
    surface = M3LightSurface,
    onSurface = M3LightOnSurface,
    surfaceVariant = M3LightSurfaceVariant,
    onSurfaceVariant = M3LightOnSurfaceVariant
)

@Composable
fun AntigravityPlayerTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isAndroid12Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when (themeMode) {
        ThemeMode.AMOLED -> {
            if (dynamicColor && isAndroid12Plus) {
                dynamicDarkColorScheme(context).copy(
                    background = AmoledBackground,
                    surface = AmoledSurface,
                    surfaceVariant = AmoledSurfaceVariant,
                    onBackground = AmoledOnSurface,
                    onSurface = AmoledOnSurface
                )
            } else {
                AmoledColorScheme
            }
        }
        ThemeMode.LIGHT -> {
            if (dynamicColor && isAndroid12Plus) {
                dynamicLightColorScheme(context)
            } else {
                LightColorScheme
            }
        }
        ThemeMode.DARK -> {
            if (dynamicColor && isAndroid12Plus) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
