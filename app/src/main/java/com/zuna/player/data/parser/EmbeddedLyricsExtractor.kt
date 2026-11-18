package com.zuna.player.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.model.LyricsData
import com.zuna.player.data.model.LyricsSource
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

object EmbeddedLyricsExtractor {

    private const val TAG = "EmbeddedLyricsExtractor"

    fun extract(context: Context, track: AudioFileMetadata): LyricsData? {
        var inputStream: InputStream? = null
        try {
            inputStream = if (track.uri.scheme == "file") {
                File(track.uri.path ?: return null).inputStream()
            } else {
                context.contentResolver.openInputStream(track.uri)
            } ?: return null

            val header = ByteArray(12)
            val bytesRead = inputStream.read(header)
            if (bytesRead < 4) return null

            // Reopen stream for full parsing
            inputStream.close()
            inputStream = if (track.uri.scheme == "file") {
                File(track.uri.path ?: return null).inputStream()
            } else {
                context.contentResolver.openInputStream(track.uri)
            } ?: return null

            // 1. Check for FLAC ('fLaC')
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
                val flacLyrics = extractFromFlac(inputStream)
                if (flacLyrics != null) return flacLyrics
            }
            // 2. Check for ID3v2 ('ID3')
            else if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                val id3Lyrics = extractFromId3(inputStream)
                if (id3Lyrics != null) return id3Lyrics
            }
            // 3. Check for MP4/M4A ('ftyp' at offset 4)
            else if (bytesRead >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
                val m4aLyrics = extractFromM4a(inputStream)
                if (m4aLyrics != null) return m4aLyrics
            } else {
                // Fallback: try ID3 search (some MP3s or FLACs have ID3 tags prepended)
                val id3Fallback = extractFromId3(inputStream)
                if (id3Fallback != null) return id3Fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting embedded lyrics for ${track.title}", e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLAC VORBIS COMMENT PARSER
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractFromFlac(input: InputStream): LyricsData? {
        try {
            // Skip 4 bytes 'fLaC'
            input.skip(4)

            var isLastBlock = false
            while (!isLastBlock) {
                val blockHeader = ByteArray(4)
                if (input.readFully(blockHeader) != 4) break

                val firstByte = blockHeader[0].toInt() and 0xFF
                isLastBlock = (firstByte and 0x80) != 0
                val blockType = firstByte and 0x7F

                val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)

                if (blockType == 4) { // VORBIS_COMMENT
                    val blockData = ByteArray(length)
                    if (input.readFully(blockData) == length) {
                        return parseVorbisComments(blockData)
                    }
                    break
                } else {
                    // Skip other metadata blocks (STREAMINFO, PADDING, PICTURE, etc.)
                    input.skipBytes(length.toLong())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing FLAC metadata blocks", e)
        }
        return null
    }

    private fun parseVorbisComments(data: ByteArray): LyricsData? {
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 4) return null

            // Vendor string length
            val vendorLen = buf.int
            if (vendorLen < 0 || vendorLen > buf.remaining()) return null
            buf.position(buf.position() + vendorLen)

            // User comment list length
            if (buf.remaining() < 4) return null
            val numComments = buf.int

            for (i in 0 until numComments) {
                if (buf.remaining() < 4) break
                val commentLen = buf.int
                if (commentLen <= 0 || commentLen > buf.remaining()) break

                val commentBytes = ByteArray(commentLen)
                buf.get(commentBytes)
                val commentStr = String(commentBytes, Charsets.UTF_8)

                val eqIdx = commentStr.indexOf('=')
                if (eqIdx > 0) {
                    val key = commentStr.substring(0, eqIdx).trim().uppercase()
                    val value = commentStr.substring(eqIdx + 1).trim()

                    if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS" ||
                        key == "UNSYNCED_LYRICS" || key == "LYRIC" || key == "TEXT") {
                        if (value.isNotBlank()) {
                            Log.d(TAG, "Found FLAC Vorbis Comment lyrics (key=$key, length=${value.length})")
                            return LyricsParser.parse(value, LyricsSource.EMBEDDED)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding Vorbis comments", e)
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ID3v2 PARSER (USLT, SYLT, TXXX)
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractFromId3(input: InputStream): LyricsData? {
        try {
            val header = ByteArray(10)
            if (input.readFully(header) != 10) return null

            if (header[0] != 0x49.toByte() || header[1] != 0x44.toByte() || header[2] != 0x33.toByte()) {
                return null
            }

            val version = header[3].toInt() and 0xFF // 2=ID3v2.2, 3=ID3v2.3, 4=ID3v2.4
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)

            if (tagSize <= 0 || tagSize > 15 * 1024 * 1024) return null // Max 15MB tag buffer

            val tagBytes = ByteArray(tagSize)
            if (input.readFully(tagBytes) != tagSize) return null

            val buf = ByteBuffer.wrap(tagBytes).order(ByteOrder.BIG_ENDIAN)

            while (buf.remaining() >= 10) {
                val frameIdBytes = ByteArray(4)
                buf.get(frameIdBytes)
                val frameId = String(frameIdBytes, Charsets.US_ASCII)

                if (frameId.all { it == '\u0000' } || !frameId.all { it in 'A'..'Z' || it in '0'..'9' }) {
                    break // Padding or end of frames
                }

                val frameSize = if (version == 4) {
                    // Synchsafe integer
                    ((buf.get().toInt() and 0x7F) shl 21) or
                            ((buf.get().toInt() and 0x7F) shl 14) or
                            ((buf.get().toInt() and 0x7F) shl 7) or
                            (buf.get().toInt() and 0x7F)
                } else {
                    // Standard 32-bit big-endian
                    buf.int
                }

                // 2 bytes flags
                val flag1 = buf.get()
                val flag2 = buf.get()

                if (frameSize <= 0 || frameSize > buf.remaining()) break

                val frameData = ByteArray(frameSize)
                buf.get(frameData)

                // 1. Check for USLT (Unsynchronized lyrics)
                if (frameId == "USLT" || frameId == "ULT") {
                    val lyrics = parseUsltFrame(frameData)
                    if (!lyrics.isNullOrBlank()) {
                        Log.d(TAG, "Found ID3 $frameId lyrics (length=${lyrics.length})")
                        return LyricsParser.parse(lyrics, LyricsSource.EMBEDDED)
                    }
                }
                // 2. Check for SYLT (Synchronized lyrics)
                else if (frameId == "SYLT" || frameId == "SLT") {
                    val lyrics = parseSyltFrame(frameData)
                    if (lyrics != null) {
                        Log.d(TAG, "Found ID3 SYLT synchronized lyrics (${lyrics.lines.size} lines)")
                        return lyrics
                    }
                }
                // 3. Check for TXXX (User defined text frame)
                else if (frameId == "TXXX") {
                    val lyrics = parseTxxxLyrics(frameData)
                    if (!lyrics.isNullOrBlank()) {
                        Log.d(TAG, "Found ID3 TXXX lyrics (length=${lyrics.length})")
                        return LyricsParser.parse(lyrics, LyricsSource.EMBEDDED)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing ID3v2 frames", e)
        }
        return null
    }

    private fun parseUsltFrame(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = getCharsetForId3(encoding)

        // Skip 3 bytes language
        var pos = 4
        // Skip null-terminated descriptor
        if (encoding == 1 || encoding == 2) {
            // 2-byte null terminator
            while (pos + 1 < data.size && (data[pos] != 0.toByte() || data[pos + 1] != 0.toByte())) {
                pos += 2
            }
            pos += 2
        } else {
            // 1-byte null terminator
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }

        if (pos >= data.size) return null
        val lyricBytes = data.copyOfRange(pos, data.size)
        return String(lyricBytes, charset).trim()
    }

    private fun parseSyltFrame(data: ByteArray): LyricsData? {
        if (data.size < 6) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = getCharsetForId3(encoding)
        val timeFormat = data[4].toInt() and 0xFF // 1 = ms, 2 = MPEG frames
        val contentType = data[5].toInt() and 0xFF // 1 = lyrics

        var pos = 6
        // Skip descriptor
        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.size && (data[pos] != 0.toByte() || data[pos + 1] != 0.toByte())) {
                pos += 2
            }
            pos += 2
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }

        val lines = mutableListOf<com.zuna.player.data.model.LyricLine>()
        val buf = ByteBuffer.wrap(data, pos, data.size - pos).order(ByteOrder.BIG_ENDIAN)

        while (buf.remaining() > 4) {
            val textStart = buf.position()
            var nullPos = -1

            if (encoding == 1 || encoding == 2) {
                while (buf.remaining() >= 6) {
                    val b1 = buf.get()
                    val b2 = buf.get()
                    if (b1 == 0.toByte() && b2 == 0.toByte()) {
                        nullPos = buf.position() - 2
                        break
                    }
                }
            } else {
                while (buf.remaining() >= 5) {
                    if (buf.get() == 0.toByte()) {
                        nullPos = buf.position() - 1
                        break
                    }
                }
            }

            if (nullPos < 0 || buf.remaining() < 4) break
            val textLen = nullPos - textStart
            val textBytes = ByteArray(textLen)
            System.arraycopy(data, textStart, textBytes, 0, textLen)
            val text = String(textBytes, charset).trim()

            val timeMs = buf.int.toLong() and 0xFFFFFFFFL
            if (text.isNotBlank()) {
                lines.add(com.zuna.player.data.model.LyricLine(timestampMs = timeMs, text = text))
            }
        }

        return if (lines.isNotEmpty()) {
            LyricsData(
                isSynced = true,
                lines = lines.sortedBy { it.timestampMs },
                plainText = lines.joinToString("\n") { it.text },
                source = LyricsSource.EMBEDDED
            )
        } else null
    }

    private fun parseTxxxLyrics(data: ByteArray): String? {
        if (data.size < 3) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = getCharsetForId3(encoding)

        var pos = 1
        val descStart = pos
        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.size && (data[pos] != 0.toByte() || data[pos + 1] != 0.toByte())) {
                pos += 2
            }
            val desc = String(data.copyOfRange(descStart, pos), charset).trim().uppercase()
            pos += 2
            if (desc == "LYRICS" || desc == "UNSYNCEDLYRICS" || desc == "SYNCEDLYRICS") {
                if (pos < data.size) return String(data.copyOfRange(pos, data.size), charset).trim()
            }
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) {
                pos++
            }
            val desc = String(data.copyOfRange(descStart, pos), charset).trim().uppercase()
            pos++
            if (desc == "LYRICS" || desc == "UNSYNCEDLYRICS" || desc == "SYNCEDLYRICS") {
                if (pos < data.size) return String(data.copyOfRange(pos, data.size), charset).trim()
            }
        }
        return null
    }

    private fun getCharsetForId3(encoding: Int): Charset {
        return when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charset.forName("ISO-8859-1")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // M4A / MP4 ATOM PARSER ('©lyr')
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractFromM4a(input: InputStream): LyricsData? {
        try {
            val buf = ByteArray(4096)
            var bytesRead: Int
            val totalBytes = ArrayList<Byte>()
            // Read first 512KB where moov/udta/ilst usually resides
            var totalRead = 0
            while (totalRead < 512 * 1024) {
                bytesRead = input.read(buf)
                if (bytesRead <= 0) break
                for (i in 0 until bytesRead) totalBytes.add(buf[i])
                totalRead += bytesRead
            }

            val data = totalBytes.toByteArray()
            // Look for '\xa9lyr' (0xA9, 'l', 'y', 'r')
            for (i in 0 until data.size - 8) {
                if (data[i] == 0xA9.toByte() && data[i + 1] == 0x6C.toByte() &&
                    data[i + 2] == 0x79.toByte() && data[i + 3] == 0x72.toByte()) {
                    // Found lyrics atom! Look for 'data' subatom inside
                    for (j in i + 4 until (i + 200).coerceAtMost(data.size - 16)) {
                        if (data[j] == 0x64.toByte() && data[j + 1] == 0x61.toByte() &&
                            data[j + 2] == 0x74.toByte() && data[j + 3] == 0x61.toByte()) {
                            // 'data' atom header is 8 bytes length+type, then 8 bytes flags/version
                            val payloadStart = j + 12
                            if (payloadStart < data.size) {
                                val atomSize = ((data[j - 4].toInt() and 0xFF) shl 24) or
                                        ((data[j - 3].toInt() and 0xFF) shl 16) or
                                        ((data[j - 2].toInt() and 0xFF) shl 8) or
                                        (data[j - 1].toInt() and 0xFF)
                                val payloadLen = if (atomSize > 16) atomSize - 16 else (data.size - payloadStart).coerceAtMost(10000)
                                val text = String(data, payloadStart, payloadLen.coerceAtMost(data.size - payloadStart), Charsets.UTF_8).trim()
                                if (text.isNotBlank()) {
                                    Log.d(TAG, "Found M4A ©lyr lyrics (length=${text.length})")
                                    return LyricsParser.parse(text, LyricsSource.EMBEDDED)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing M4A lyrics", e)
        }
        return null
    }

    // Helper to read exactly length bytes
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = this.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun InputStream.skipBytes(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = this.skip(remaining)
            if (skipped <= 0) {
                if (this.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
