package com.antigravity.audioplayer.data.parser

import com.antigravity.audioplayer.data.model.LyricLine
import com.antigravity.audioplayer.data.model.LyricsData
import com.antigravity.audioplayer.data.model.LyricsSource
import java.util.regex.Pattern

object LyricsParser {

    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)
    private val HEADER_TAG_PATTERN = Pattern.compile("^\\[(ar|ti|al|by|length|offset|re|ve|id|tool|sign):.*\\]$", Pattern.CASE_INSENSITIVE)
    private val WORD_TIME_PATTERN = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    fun parse(rawContent: String, source: LyricsSource = LyricsSource.SIDECAR, isInstrumental: Boolean = false): LyricsData {
        if (isInstrumental) {
            return LyricsData.instrumental(source)
        }

        if (rawContent.isBlank()) {
            return LyricsData(
                isSynced = false,
                lines = emptyList(),
                plainText = "",
                source = source
            )
        }

        var offsetMs = 0L
        val parsedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()

        val rawLines = rawContent.lines()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                plainLines.add("")
                continue
            }

            // Check for offset tag
            val offsetMatcher = OFFSET_PATTERN.matcher(trimmed)
            if (offsetMatcher.find()) {
                offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            // Skip file metadata header tags like [ar:Artist], [ti:Title]
            if (HEADER_TAG_PATTERN.matcher(trimmed).matches()) {
                continue
            }

            // Check for time tags
            val timeMatcher = TIME_TAG_PATTERN.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastTagEnd = 0

            while (timeMatcher.find()) {
                val minStr = timeMatcher.group(1) ?: "0"
                val secStr = timeMatcher.group(2) ?: "0"
                val msStr = timeMatcher.group(3) ?: "0"

                val minutes = minStr.toLongOrNull() ?: 0L
                val seconds = secStr.toLongOrNull() ?: 0L
                val millis = when (msStr.length) {
                    1 -> (msStr.toLongOrNull() ?: 0L) * 100
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }

                val timestamp = (minutes * 60 * 1000) + (seconds * 1000) + millis
                timestamps.add(timestamp)
                lastTagEnd = timeMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val rawText = trimmed.substring(lastTagEnd).trim()
                val text = WORD_TIME_PATTERN.matcher(rawText).replaceAll("").trim()
                for (ts in timestamps) {
                    parsedLines.add(LyricLine(timestampMs = ts, text = text))
                }
                plainLines.add(text)
            } else {
                val text = WORD_TIME_PATTERN.matcher(trimmed).replaceAll("").trim()
                plainLines.add(text)
            }
        }

        return if (parsedLines.isNotEmpty()) {
            // Apply offset and sort by timestamp
            val adjustedLines = parsedLines.map { line ->
                LyricLine(
                    timestampMs = (line.timestampMs + offsetMs).coerceAtLeast(0L),
                    text = line.text
                )
            }.sortedBy { it.timestampMs }

            LyricsData(
                isSynced = true,
                lines = adjustedLines,
                plainText = adjustedLines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text },
                source = source,
                offsetMs = offsetMs
            )
        } else {
            // Plain text (no timestamps)
            val lines = plainLines.filter { it.isNotBlank() }.map { LyricLine(0L, it) }
            LyricsData(
                isSynced = false,
                lines = lines,
                plainText = plainLines.joinToString("\n"),
                source = source,
                offsetMs = 0L
            )
        }
    }
}
