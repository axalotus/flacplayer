package com.antigravity.audioplayer.desktop

import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.antigravity.audioplayer.desktop.audio.DesktopAudioPlayer
import com.antigravity.audioplayer.desktop.theme.DesktopTheme
import com.antigravity.audioplayer.desktop.ui.DesktopMainWindow

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(1100.dp, 720.dp)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "FlacPlayer Desktop • Hi-Res Audio Player",
        state = windowState,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    Key.Spacebar -> {
                        DesktopAudioPlayer.togglePlayPause()
                        true
                    }
                    Key.DirectionRight -> {
                        if (keyEvent.isCtrlPressed) {
                            DesktopAudioPlayer.skipToNext()
                        } else {
                            val cur = DesktopAudioPlayer.progressMs.value
                            DesktopAudioPlayer.seekTo(cur + 5000L)
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (keyEvent.isCtrlPressed) {
                            DesktopAudioPlayer.skipToPrevious()
                        } else {
                            val cur = DesktopAudioPlayer.progressMs.value
                            DesktopAudioPlayer.seekTo((cur - 5000L).coerceAtLeast(0L))
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        DesktopAudioPlayer.setVolume((DesktopAudioPlayer.volume.value + 0.05f).coerceAtMost(1f))
                        true
                    }
                    Key.DirectionDown -> {
                        DesktopAudioPlayer.setVolume((DesktopAudioPlayer.volume.value - 0.05f).coerceAtLeast(0f))
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    ) {
        DesktopTheme {
            DesktopMainWindow()
        }
    }
}
