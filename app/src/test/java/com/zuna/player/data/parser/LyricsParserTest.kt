package com.zuna.player.data.parser

import com.zuna.player.data.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    @Test
    fun parse_standardLrc_parsesTimestampsCorrectly() {
        val lrc = """
            [00:01.50]Line 1
            [00:04.200]Line 2
            [01:10.05]Line 3
        """.trimIndent()

        val data = LyricsParser.parse(lrc, LyricsSource.SIDECAR)

        assertTrue(data.isSynced)
        assertFalse(data.isInstrumental)
        assertEquals(3, data.lines.size)

        assertEquals(1500L, data.lines[0].timestampMs)
        assertEquals("Line 1", data.lines[0].text)

        assertEquals(4200L, data.lines[1].timestampMs)
        assertEquals("Line 2", data.lines[1].text)

        assertEquals(70050L, data.lines[2].timestampMs)
        assertEquals("Line 3", data.lines[2].text)
    }

    @Test
    fun parse_enhancedLrcWithWordTimestamps_stripsWordTags() {
        val enhancedLrc = """
            [00:10.00]<00:10.10>Never <00:10.40>gonna <00:10.70>give <00:11.00>you <00:11.30>up
            [00:14.50]<00:14.60>Never <00:14.90>gonna <00:15.20>let <00:15.50>you <00:15.80>down
        """.trimIndent()

        val data = LyricsParser.parse(enhancedLrc, LyricsSource.LRCLIB)

        assertTrue(data.isSynced)
        assertEquals(2, data.lines.size)
        assertEquals(10000L, data.lines[0].timestampMs)
        assertEquals("Never gonna give you up", data.lines[0].text)
        assertEquals(14500L, data.lines[1].timestampMs)
        assertEquals("Never gonna let you down", data.lines[1].text)
    }

    @Test
    fun parse_offsetTag_adjustsTimestamps() {
        val lrcWithOffset = """
            [offset: 500]
            [00:02.00]Delayed by 500ms
        """.trimIndent()

        val data = LyricsParser.parse(lrcWithOffset, LyricsSource.SIDECAR)

        assertTrue(data.isSynced)
        assertEquals(1, data.lines.size)
        assertEquals(2500L, data.lines[0].timestampMs)
        assertEquals(500L, data.offsetMs)
    }

    @Test
    fun parse_multipleTimestampsOnSingleLine_createsMultipleLines() {
        val lrc = """
            [00:05.00][00:25.00]Repeating phrase
        """.trimIndent()

        val data = LyricsParser.parse(lrc)

        assertTrue(data.isSynced)
        assertEquals(2, data.lines.size)
        assertEquals(5000L, data.lines[0].timestampMs)
        assertEquals("Repeating phrase", data.lines[0].text)
        assertEquals(25000L, data.lines[1].timestampMs)
        assertEquals("Repeating phrase", data.lines[1].text)
    }

    @Test
    fun parse_metadataHeaders_areIgnored() {
        val lrc = """
            [ti:Song Title]
            [ar:Artist Name]
            [al:Album Name]
            [by:LrcMaker]
            [length:03:30]
            [00:05.00]Actual lyrics
        """.trimIndent()

        val data = LyricsParser.parse(lrc)

        assertTrue(data.isSynced)
        assertEquals(1, data.lines.size)
        assertEquals("Actual lyrics", data.lines[0].text)
    }

    @Test
    fun parse_plainTextLyrics_returnsUnsyncedData() {
        val plain = """
            First verse line
            Second verse line
            Chorus starts here
        """.trimIndent()

        val data = LyricsParser.parse(plain, LyricsSource.EMBEDDED)

        assertFalse(data.isSynced)
        assertFalse(data.isInstrumental)
        assertEquals(3, data.lines.size)
        assertEquals("First verse line", data.lines[0].text)
        assertEquals(0L, data.lines[0].timestampMs)
    }

    @Test
    fun parse_emptyOrBlank_returnsEmptyData() {
        val emptyData = LyricsParser.parse("")
        assertFalse(emptyData.isSynced)
        assertTrue(emptyData.lines.isEmpty())
        assertTrue(emptyData.plainText.isEmpty())

        val whitespaceData = LyricsParser.parse("   \n\t  \n  ")
        assertFalse(whitespaceData.isSynced)
        assertTrue(whitespaceData.lines.isEmpty())
    }

    @Test
    fun parse_instrumental_returnsInstrumentalData() {
        val data = LyricsParser.parse("", source = LyricsSource.LRCLIB, isInstrumental = true)
        assertTrue(data.isInstrumental)
        assertFalse(data.isSynced)
        assertTrue(data.lines.isEmpty())
        assertEquals(LyricsSource.LRCLIB, data.source)
    }

    @Test
    fun parse_corruptedTags_handlesGracefully() {
        val corrupted = """
            [invalid:tag]
            [9999:9999] Out of bounds
            [00:02.00] Valid line
            random string without brackets
        """.trimIndent()

        val data = LyricsParser.parse(corrupted)
        assertTrue(data.isSynced)
        assertEquals(1, data.lines.size)
        assertEquals("Valid line", data.lines[0].text)
    }

    @Test
    fun parse_enhancedLrc_extractsWordTimingsAndTrailingEndTag() {
        val enhanced = """
            [00:10.00]<00:10.00>Ко<00:10.25>гда <00:10.50>гаснет <00:11.00>свет<00:11.60>
            [00:12.50]Обычная строка
        """.trimIndent()

        val data = LyricsParser.parse(enhanced, LyricsSource.SIDECAR)
        assertTrue(data.isSynced)
        assertTrue(data.hasWordSync)
        assertEquals(2, data.lines.size)
        assertEquals("Когда гаснет свет", data.lines[0].text)
        assertEquals(4, data.lines[0].words.size)

        val w0 = data.lines[0].words[0]
        assertEquals("Ко", w0.text)
        assertEquals(10000L, w0.startMs)
        assertEquals(10250L, w0.endMs)

        val w3 = data.lines[0].words[3]
        assertEquals("свет", w3.text)
        assertEquals(11000L, w3.startMs)
        assertEquals(11600L, w3.endMs)

        assertTrue(data.lines[1].words.isEmpty())
    }
}
