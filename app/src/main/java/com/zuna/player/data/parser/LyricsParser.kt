package com.zuna.player.data.parser

import com.zuna.player.data.model.LyricLine
import com.zuna.player.data.model.LyricWord
import com.zuna.player.data.model.LyricsData
import com.zuna.player.data.model.LyricsSource
import java.util.regex.Pattern

object LyricsParser {

    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)
    private val HEADER_TAG_PATTERN = Pattern.compile("^\\[(ar|ti|al|by|length|offset|re|ve|id|tool|sign):.*\\]$", Pattern.CASE_INSENSITIVE)
    private val WORD_TIME_PATTERN = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.,:](\\d{1,3}))?>")

    private data class ParsedWordSegment(
        val startMs: Long,
        var text: String
    )

    private data class RawSyncedLine(
        val timestampMs: Long,
        val text: String,
        val words: List<ParsedWordSegment>,
        val trailingEndMs: Long?
    )

    private fun parseTagMillis(minStr: String?, secStr: String?, fracStr: String?): Long {
        val minutes = minStr?.toLongOrNull() ?: 0L
        val seconds = secStr?.toLongOrNull() ?: 0L
        val frac = fracStr ?: ""
        val millis = when (frac.length) {
            1 -> (frac.toLongOrNull() ?: 0L) * 100
            2 -> (frac.toLongOrNull() ?: 0L) * 10
            3 -> frac.toLongOrNull() ?: 0L
            else -> 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + millis
    }

    private fun parseEnhancedSegments(
        rawBody: String,
        fallbackStartMs: Long
    ): Triple<String, List<ParsedWordSegment>, Long?> {
        val matcher = WORD_TIME_PATTERN.matcher(rawBody)
        val segments = mutableListOf<ParsedWordSegment>()
        val plainBuilder = StringBuilder()
        var trailingEndMs: Long? = null
        var lastCursor = 0
        var curTs = fallbackStartMs
        var foundTag = false

        while (matcher.find()) {
            foundTag = true
            val slice = rawBody.substring(lastCursor, matcher.start())
            if (slice.isNotEmpty()) {
                plainBuilder.append(slice)
                if (slice.isNotBlank()) {
                    segments.add(ParsedWordSegment(curTs, slice))
                } else if (segments.isNotEmpty()) {
                    segments[segments.lastIndex].text += slice
                }
            }
            curTs = parseTagMillis(matcher.group(1), matcher.group(2), matcher.group(3))
            lastCursor = matcher.end()
        }

        if (!foundTag) {
            return Triple(rawBody.trim(), emptyList(), null)
        }

        val tail = rawBody.substring(lastCursor)
        if (tail.isNotEmpty()) {
            plainBuilder.append(tail)
            if (tail.isNotBlank()) {
                segments.add(ParsedWordSegment(curTs, tail))
            } else if (segments.isNotEmpty()) {
                segments[segments.lastIndex].text += tail
            }
        } else if (segments.isNotEmpty() && curTs > segments.last().startMs) {
            trailingEndMs = curTs
        }

        return Triple(plainBuilder.toString().trim(), segments, trailingEndMs)
    }

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
        val parsedLines = mutableListOf<RawSyncedLine>()
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
                val timestamp = parseTagMillis(timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3))
                timestamps.add(timestamp)
                lastTagEnd = timeMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val rawBody = trimmed.substring(lastTagEnd).trimStart()
                val firstTs = timestamps.first()
                val (cleanText, segments, trailingEndMs) = parseEnhancedSegments(rawBody, firstTs)
                for (ts in timestamps) {
                    val delta = ts - firstTs
                    val shiftedSegs = segments.map { ParsedWordSegment(it.startMs + delta, it.text) }
                    val shiftedTrail = trailingEndMs?.let { it + delta }
                    parsedLines.add(
                        RawSyncedLine(
                            timestampMs = ts,
                            text = cleanText,
                            words = shiftedSegs,
                            trailingEndMs = shiftedTrail
                        )
                    )
                }
                plainLines.add(cleanText)
            } else {
                val text = WORD_TIME_PATTERN.matcher(trimmed).replaceAll("").trim()
                plainLines.add(text)
            }
        }

        return if (parsedLines.isNotEmpty()) {
            val sortedRaw = parsedLines
                .map { rawLine ->
                    RawSyncedLine(
                        timestampMs = (rawLine.timestampMs + offsetMs).coerceAtLeast(0L),
                        text = rawLine.text,
                        words = rawLine.words.map { w ->
                            ParsedWordSegment((w.startMs + offsetMs).coerceAtLeast(0L), w.text)
                        },
                        trailingEndMs = rawLine.trailingEndMs?.let { (it + offsetMs).coerceAtLeast(0L) }
                    )
                }
                .sortedBy { it.timestampMs }

            var anyWords = false
            val adjustedLines = sortedRaw.mapIndexed { idx, rawLine ->
                val nextLine = sortedRaw.getOrNull(idx + 1)
                val finalWords = if (rawLine.words.isEmpty()) {
                    emptyList()
                } else {
                    anyWords = true
                    rawLine.words.mapIndexed { k, w ->
                        val nextWord = rawLine.words.getOrNull(k + 1)
                        val endMs = when {
                            nextWord != null -> nextWord.startMs.coerceAtLeast(w.startMs + 60L)
                            rawLine.trailingEndMs != null && rawLine.trailingEndMs > w.startMs -> rawLine.trailingEndMs
                            nextLine != null && nextLine.timestampMs > w.startMs ->
                                minOf(nextLine.timestampMs, w.startMs + 6000L)
                            else -> w.startMs + maxOf(500L, w.text.trim().length * 160L)
                        }
                        LyricWord(startMs = w.startMs, endMs = endMs, text = w.text)
                    }
                }
                LyricLine(
                    timestampMs = rawLine.timestampMs,
                    text = rawLine.text,
                    words = finalWords
                )
            }

            LyricsData(
                isSynced = true,
                lines = adjustedLines,
                plainText = adjustedLines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text },
                source = source,
                offsetMs = offsetMs,
                hasWordSync = anyWords
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
