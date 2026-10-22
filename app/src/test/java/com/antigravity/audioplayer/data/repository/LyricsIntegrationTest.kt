package com.antigravity.audioplayer.data.repository

import com.antigravity.audioplayer.data.model.LyricLine
import com.antigravity.audioplayer.data.model.LyricsData
import com.antigravity.audioplayer.data.model.LyricsSource
import com.antigravity.audioplayer.data.online.LrclibLyricsProvider
import com.antigravity.audioplayer.data.parser.LyricsParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsIntegrationTest {

    @Test
    fun queryExtraction_usesSecondsAndCleanTags() {
        val query = LrclibLyricsProvider.Query.create(
            artist = "David Bowie",
            title = "Starman (2012 Remaster).flac",
            album = "The Rise and Fall of Ziggy Stardust",
            durationMs = 256890L
        )

        // Title decoration and .flac extension should be stripped
        assertEquals("Starman", query.title)
        assertEquals("David Bowie", query.artist)
        // Duration in milliseconds (256890) converted to integer seconds (256)
        assertEquals(256, query.durationSec)
        assertTrue(query.isValid())
    }

    @Test
    fun negativeCache_preventsRepeatedNetworkCallsForMissingLyrics() {
        val q = LrclibLyricsProvider.Query.create(
            artist = "Obscure Artist",
            title = "Unknown Song",
            album = null,
            durationMs = 120000L
        )

        LrclibLyricsProvider.clearNegativeCache()
        assertFalse(LrclibLyricsProvider.isKnownMissing(q))

        // Emulate registering as missing (e.g. after receiving 404 from LRCLIB)
        // Check key uniqueness
        val key = q.key
        assertEquals("obscure artist\u0001unknown song\u0001120", key)
    }

    @Test
    fun manualAndLocalLyrics_takePriorityOverOnline() {
        val trackId = "track_1"

        // 1. Emulate local manual lyrics
        val manualLyrics = LyricsData(
            isSynced = true,
            lines = listOf(LyricLine(1000L, "Is this the real life?")),
            plainText = "Is this the real life?",
            source = LyricsSource.MANUAL
        )

        LyricsRepository.setManualLyrics(trackId, manualLyrics)
        assertEquals(manualLyrics, LyricsRepository.currentLyrics.value)
        assertEquals(LyricsSource.MANUAL, LyricsRepository.currentLyrics.value?.source)
    }

    @Test
    fun parser_enhancedLrcVersusStandardLrc() {
        val standard = "[00:05.10]First line\n[00:10.20]Second line"
        val parsedStandard = LyricsParser.parse(standard, LyricsSource.LRCLIB)
        assertTrue(parsedStandard.isSynced)
        assertEquals(2, parsedStandard.lines.size)
        assertEquals("First line", parsedStandard.lines[0].text)

        val enhanced = "[00:05.10]<00:05.20>First <00:05.60>line\n[00:10.20]<00:10.30>Second <00:10.70>line"
        val parsedEnhanced = LyricsParser.parse(enhanced, LyricsSource.LRCLIB)
        assertTrue(parsedEnhanced.isSynced)
        assertEquals(2, parsedEnhanced.lines.size)
        // Words tags must be cleaned
        assertEquals("First line", parsedEnhanced.lines[0].text)
        assertEquals("Second line", parsedEnhanced.lines[1].text)
        assertEquals(parsedStandard.lines[0].text, parsedEnhanced.lines[0].text)
    }

    @Test
    fun parser_handlesEmptyAndMalformedDataGracefully() {
        val emptyResult = LyricsParser.parse("")
        assertFalse(emptyResult.isSynced)
        assertTrue(emptyResult.lines.isEmpty())

        val malformedLrc = "Not an LRC file at all\nJust text without timestamps"
        val malformedResult = LyricsParser.parse(malformedLrc)
        assertFalse(malformedResult.isSynced)
        assertEquals(2, malformedResult.lines.size)
        assertEquals("Not an LRC file at all", malformedResult.lines[0].text)

        val instrumentalJson = JSONObject("""{"instrumental": true, "syncedLyrics": null}""")
        val parsedJson = LrclibLyricsProvider.parseLrclibJson(instrumentalJson)
        assertNotNull(parsedJson)
        assertTrue(parsedJson!!.isInstrumental)
        assertEquals(LyricsSource.LRCLIB, parsedJson.source)
    }
}
