package com.antigravity.audioplayer.data.model

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

enum class LyricsSource(val label: String) {
    SIDECAR("Файл рядом с треком"),
    CUSTOM_FOLDER("Папка с текстами"),
    EMBEDDED("Метаданные трека"),
    MANUAL("Выбран вручную"),
    NONE("Не найден")
}

data class LyricsData(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String,
    val source: LyricsSource,
    val offsetMs: Long = 0L
)
