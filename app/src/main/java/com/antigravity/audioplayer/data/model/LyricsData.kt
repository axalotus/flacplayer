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
    LRCLIB("LRCLIB (Онлайн)"),
    NONE("Не найден")
}

data class LyricsData(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String,
    val source: LyricsSource,
    val offsetMs: Long = 0L,
    val isInstrumental: Boolean = false
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

