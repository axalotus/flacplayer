package com.antigravity.audioplayer.desktop.lyrics

import com.antigravity.audioplayer.desktop.model.DesktopTrack
import com.antigravity.audioplayer.desktop.model.LyricLine
import com.antigravity.audioplayer.desktop.model.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.regex.Pattern

object DesktopLyrics {

    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)
    private val HEADER_TAG_PATTERN = Pattern.compile("^\\[(ar|ti|al|by|length|offset|re|ve|id|tool|sign):.*\\]$", Pattern.CASE_INSENSITIVE)

    suspend fun getLyrics(track: DesktopTrack, customLyricsDir: File? = null): LyricsData? = withContext(Dispatchers.IO) {
        val audioFile = File(track.filePath)

        // 1. Sidecar file beside audio track
        val parent = audioFile.parentFile
        if (parent != null && parent.exists()) {
            val baseName = audioFile.nameWithoutExtension
            for (ext in listOf("lrc", "txt", "LRC", "TXT")) {
                val sidecar = File(parent, "$baseName.$ext")
                if (sidecar.exists() && sidecar.isFile) {
                    val text = decodeText(sidecar.readBytes())
                    if (text.isNotBlank()) {
                        return@withContext parse(text, "Файл рядом с треком ($ext)")
                    }
                }
            }
        }

        // 2. Custom dedicated lyrics folder
        if (customLyricsDir != null && customLyricsDir.exists() && customLyricsDir.isDirectory) {
            val targets = listOf(
                "${track.artist} - ${track.title}",
                "${track.title} - ${track.artist}",
                track.title,
                audioFile.nameWithoutExtension
            )

            for (ext in listOf("lrc", "txt", "LRC", "TXT")) {
                for (target in targets) {
                    val f = File(customLyricsDir, "$target.$ext")
                    if (f.exists() && f.isFile) {
                        val text = decodeText(f.readBytes())
                        if (text.isNotBlank()) {
                            return@withContext parse(text, "Папка текстов ($ext)")
                        }
                    }
                }
            }
        }

        // 3. Embedded metadata (Vorbis Comments / ID3)
        try {
            val embedded = extractEmbedded(audioFile)
            if (embedded != null) {
                return@withContext parse(embedded, "Встроенные теги трека")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        null
    }

    fun parse(rawContent: String, source: String = "Файл"): LyricsData {
        if (rawContent.isBlank()) {
            return LyricsData(false, emptyList(), "", source)
        }

        var offsetMs = 0L
        val parsedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()

        for (line in rawContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                plainLines.add("")
                continue
            }

            val offsetMatcher = OFFSET_PATTERN.matcher(trimmed)
            if (offsetMatcher.find()) {
                offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            if (HEADER_TAG_PATTERN.matcher(trimmed).matches()) {
                continue
            }

            val timeMatcher = TIME_TAG_PATTERN.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastTagEnd = 0

            while (timeMatcher.find()) {
                val minutes = timeMatcher.group(1)?.toLongOrNull() ?: 0L
                val seconds = timeMatcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = timeMatcher.group(3) ?: "0"

                val millis = when (msStr.length) {
                    1 -> (msStr.toLongOrNull() ?: 0L) * 100
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }

                timestamps.add((minutes * 60 * 1000) + (seconds * 1000) + millis)
                lastTagEnd = timeMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val text = trimmed.substring(lastTagEnd).trim()
                for (ts in timestamps) {
                    parsedLines.add(LyricLine(ts, text))
                }
                plainLines.add(text)
            } else {
                plainLines.add(trimmed)
            }
        }

        return if (parsedLines.isNotEmpty()) {
            val adjusted = parsedLines.map { line ->
                LyricLine((line.timestampMs + offsetMs).coerceAtLeast(0L), line.text)
            }.sortedBy { it.timestampMs }

            LyricsData(
                isSynced = true,
                lines = adjusted,
                plainText = adjusted.filter { it.text.isNotBlank() }.joinToString("\n") { it.text },
                source = source,
                offsetMs = offsetMs
            )
        } else {
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

    private fun extractEmbedded(file: File): String? {
        if (!file.exists()) return null
        val bytes = file.inputStream().use { stream ->
            val buf = ByteArray(128 * 1024)
            val read = stream.read(buf)
            if (read > 0) buf.copyOf(read) else null
        } ?: return null

        // Check FLAC Vorbis Comment
        if (bytes.size >= 4 && bytes[0] == 0x66.toByte() && bytes[1] == 0x4C.toByte() && bytes[2] == 0x61.toByte() && bytes[3] == 0x43.toByte()) {
            val str = String(bytes, Charsets.ISO_8859_1)
            for (key in listOf("LYRICS=", "UNSYNCEDLYRICS=", "SYNCEDLYRICS=", "TEXT=")) {
                val idx = str.indexOf(key, ignoreCase = true)
                if (idx >= 0) {
                    val start = idx + key.length
                    val end = str.indexOf("\u0000", start).takeIf { it > start } ?: (start + 2000).coerceAtMost(str.length)
                    val rawVal = str.substring(start, end)
                    val decoded = String(rawVal.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim()
                    if (decoded.isNotBlank()) return decoded
                }
            }
        }

        // Check ID3 USLT
        val str = String(bytes, Charsets.ISO_8859_1)
        val usltIdx = str.indexOf("USLT")
        if (usltIdx >= 0) {
            val start = usltIdx + 10 // Skip header
            val end = (start + 5000).coerceAtMost(str.length)
            val raw = str.substring(start, end)
            val clean = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            if (clean.isNotBlank()) return clean
        }

        return null
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }

        try {
            val decoder = Charsets.UTF_8.newDecoder()
            val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (!decoded.contains('\uFFFD')) {
                return decoded
            }
        } catch (_: Exception) {}

        try {
            val cp1251 = Charset.forName("windows-1251")
            val decoded1251 = String(bytes, cp1251)
            if (decoded1251.any { it in '\u0400'..'\u04FF' }) {
                return decoded1251
            }
        } catch (_: Exception) {}

        return String(bytes, Charsets.UTF_8)
    }
}
