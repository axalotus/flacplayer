package com.antigravity.audioplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun VisualizerView(
    isPlaying: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    // Disabled to prevent canvas animation lag during scrolling
}
