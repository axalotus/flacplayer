package com.zuna.player.data.repository

import com.zuna.player.data.model.LyricLine
import com.zuna.player.data.model.LyricsData
import com.zuna.player.data.model.LyricsSource
import com.zuna.player.data.online.LrclibLyricsProvider
import com.zuna.player.data.parser.LyricsParser
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
        LrclibLyricsProvider.markKnownMissing(q)
        assertTrue(LrclibLyricsProvider.isKnownMissing(q))

        // Check key uniqueness
        val key = q.key
        assertEquals("obscure artist\u0001unknown song\u0001120", key)

        // Clear negative cache
        LrclibLyricsProvider.clearNegativeCache()
        assertFalse(LrclibLyricsProvider.isKnownMissing(q))
    }

    @Test
    fun lrclibResult_sealedSubclasses_representStateCorrectly() {
        val dummyData = LyricsData(
            isSynced = true,
            lines = listOf(LyricLine(5000L, "Sample")),
            plainText = "Sample",
            source = LyricsSource.LRCLIB
        )
        val success = LrclibLyricsProvider.LrclibResult.Success(dummyData)
        val notFound = LrclibLyricsProvider.LrclibResult.NotFound
        val netError = LrclibLyricsProvider.LrclibResult.NetworkError("Connect timed out", 408)

        assertEquals(dummyData, success.lyrics)
        assertTrue(notFound is LrclibLyricsProvider.LrclibResult.NotFound)
        assertEquals("Connect timed out", netError.message)
        assertEquals(408, netError.statusCode)
    }

    @Test
    fun lyricsLoadState_sealedSubclasses_representUiStateCorrectly() {
        val dummyData = LyricsData(
            isSynced = false,
            lines = emptyList(),
            plainText = "Instrumental",
            isInstrumental = true,
            source = LyricsSource.LRCLIB
        )

        val idle: LyricsLoadState = LyricsLoadState.Idle
        val loading: LyricsLoadState = LyricsLoadState.Loading
        val success: LyricsLoadState = LyricsLoadState.Success(dummyData)
        val notFound: LyricsLoadState = LyricsLoadState.NotFound("Текст не найден")
        val error: LyricsLoadState = LyricsLoadState.NetworkError("Host unreachable")

        assertTrue(idle is LyricsLoadState.Idle)
        assertTrue(loading is LyricsLoadState.Loading)
        assertEquals(dummyData, (success as LyricsLoadState.Success).lyrics)
        assertTrue(notFound is LyricsLoadState.NotFound)
        assertEquals("Host unreachable", (error as LyricsLoadState.NetworkError).message)
    }

    @Test
    fun libraryCategoryTypes_containHistoryAndRecentlyAdded() {
        val categories = com.zuna.player.ui.screens.LibraryCategoryType.values().map { it.name }
        assertTrue("Categories must include HISTORY", categories.contains("HISTORY"))
        assertTrue("Categories must include RECENTLY_ADDED", categories.contains("RECENTLY_ADDED"))
        assertTrue("Categories must include ALL_TRACKS", categories.contains("ALL_TRACKS"))
        assertTrue("Categories must include ALBUMS", categories.contains("ALBUMS"))
        assertTrue("Categories must include ARTISTS", categories.contains("ARTISTS"))
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
