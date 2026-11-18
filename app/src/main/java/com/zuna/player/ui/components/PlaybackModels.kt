package com.zuna.player.ui.components

import androidx.compose.runtime.Immutable
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.model.AudioFormat
import kotlin.math.abs

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

@Immutable
data class DuplicateTrackEntry(
    val track: AudioFileMetadata,
    val primaryTrack: AudioFileMetadata
)

data class DeduplicationResult(
    val visibleTracks: List<AudioFileMetadata>,
    val duplicateEntries: List<DuplicateTrackEntry>
)

private fun formatQualityRank(format: AudioFormat): Int = when (format) {
    AudioFormat.FLAC, AudioFormat.ALAC -> 4
    AudioFormat.WAV, AudioFormat.AIFF -> 3
    AudioFormat.OPUS, AudioFormat.AAC, AudioFormat.OGG -> 2
    AudioFormat.MP3 -> 1
}

fun deduplicateTracks(
    tracks: List<AudioFileMetadata>,
    exemptIds: Set<String> = emptySet()
): DeduplicationResult {
    if (tracks.size <= 1) return DeduplicationResult(tracks, emptyList())

    val groups = tracks.groupBy {
        it.title.trim().lowercase() to it.artist.trim().lowercase()
    }
    val duplicateIds = HashSet<String>()
    val duplicates = mutableListOf<DuplicateTrackEntry>()

    for ((key, group) in groups) {
        if (group.size <= 1 || key.first.isBlank()) continue
        val ranked = group.sortedWith(
            compareByDescending<AudioFileMetadata> { formatQualityRank(it.format) }
                .thenByDescending { it.bitDepth }
                .thenByDescending { it.sampleRateHz }
                .thenByDescending { it.bitrateKbps }
                .thenBy { it.dateAdded }
                .thenBy { it.id }
        )
        val kept = mutableListOf<AudioFileMetadata>()
        for (candidate in ranked) {
            if (candidate.id in exemptIds) {
                kept.add(candidate)
                continue
            }
            val matchPrimary = kept.firstOrNull { primary ->
                candidate.durationMs == 0L ||
                    primary.durationMs == 0L ||
                    abs(candidate.durationMs - primary.durationMs) <= 3000L
            }
            if (matchPrimary != null) {
                duplicateIds.add(candidate.id)
                duplicates.add(DuplicateTrackEntry(track = candidate, primaryTrack = matchPrimary))
            } else {
                kept.add(candidate)
            }
        }
    }

    val visible = tracks.filter { it.id !in duplicateIds }
    return DeduplicationResult(visibleTracks = visible, duplicateEntries = duplicates)
}
