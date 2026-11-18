package com.zuna.player.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

enum class LyricsSource(val label: String) {
    SIDECAR("Файл рядом с треком"),
    CUSTOM_FOLDER("Папка с текстами"),
    EMBEDDED("Метаданные трека"),
    MANUAL("Выбран вручную"),
    LRCLIB("LRCLIB (Онлайн)"),
    NONE("Не найден")
}

@Immutable
data class LyricsData(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String,
    val source: LyricsSource,
    val offsetMs: Long = 0L,
    val isInstrumental: Boolean = false,
    val hasWordSync: Boolean = false
) {
    companion object {
        fun instrumental(source: LyricsSource = LyricsSource.LRCLIB): LyricsData {
            return LyricsData(
                isSynced = false,
                lines = emptyList(),
                plainText = "",
                source = source,
                offsetMs = 0L,
                isInstrumental = true
            )
        }
    }
}

