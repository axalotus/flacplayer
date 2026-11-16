package com.antigravity.audioplayer.ui.components

import androidx.compose.runtime.Immutable
import com.antigravity.audioplayer.data.model.AudioFileMetadata

enum class RepeatMode(val label: String) {
    OFF("Выкл"),
    ALL("Все"),
    ONE("Один")
}

@Immutable
data class AlbumGroup(
    val name: String,
    val artist: String,
    val tracks: List<AudioFileMetadata>,
    val representativeTrack: AudioFileMetadata
)

@Immutable
data class ArtistGroup(
    val name: String,
    val tracks: List<AudioFileMetadata>,
    val albumCount: Int,
    val representativeTrack: AudioFileMetadata
)

@Immutable
data class FolderGroup(
    val name: String,
    val pathKey: String,
    val tracks: List<AudioFileMetadata>
)

fun formatDurationMs(ms: Long): String {
    val safeSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = safeSec / 60L
    val secs = safeSec % 60L
    return "%d:%02d".format(mins, secs)
}
