package com.antigravity.audioplayer.data.online

import com.antigravity.audioplayer.data.model.LyricsSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LrclibLyricsProviderTest {

    @Test
    fun queryCreate_stripsAudioExtensionAndUnderscores() {
        val q = LrclibLyricsProvider.Query.create(
            artist = "Queen",
            title = "Bohemian_Rhapsody.flac",
            album = "A Night at the Opera",
            durationMs = 354000L
        )

        assertEquals("Queen", q.artist)
        assertEquals("Bohemian Rhapsody", q.title)
        assertEquals(354, q.durationSec)
        assertTrue(q.isValid())
    }

    @Test
    fun queryCreate_splitsArtistAndTitleWhenArtistMissing() {
        val q = LrclibLyricsProvider.Query.create(
            artist = null,
            title = "Pink Floyd - Comfortably Numb.mp3",
            album = null,
            durationMs = 382000L
        )

        assertEquals("Pink Floyd", q.artist)
        assertEquals("Comfortably Numb", q.title)
        assertEquals(382, q.durationSec)
    }

    @Test
    fun queryCreate_stripsDecorationsFromTitle() {
        val q1 = LrclibLyricsProvider.Query.create(
            artist = "Nirvana",
            title = "Smells Like Teen Spirit (Official Music Video)",
            album = "Nevermind",
            durationMs = 301000L
        )
        assertEquals("Smells Like Teen Spirit", q1.title)

        val q2 = LrclibLyricsProvider.Query.create(
            artist = "The Beatles",
            title = "Come Together [2019 Remaster]",
            album = "Abbey Road",
            durationMs = 259000L
        )
        assertEquals("Come Together", q2.title)

        val q3 = LrclibLyricsProvider.Query.create(
            artist = "Radiohead",
            title = "Creep (Visualizer)",
            album = "Pablo Honey",
            durationMs = 238000L
        )
        assertEquals("Creep", q3.title)
    }

    @Test
    fun queryKey_isConsistentCaseInsensitive() {
        val q1 = LrclibLyricsProvider.Query.create(
            artist = "Coldplay",
            title = "Yellow",
            album = null,
            durationMs = 269000L
        )
        val q2 = LrclibLyricsProvider.Query.create(
            artist = "COLDPLAY",
            title = "yellow",
            album = null,
            durationMs = 269000L
        )

        assertEquals(q1.key, q2.key)
    }

    @Test
    fun parseLrclibJson_instrumental() {
        val json = JSONObject("""{"instrumental": true}""")
        val result = LrclibLyricsProvider.parseLrclibJson(json)

        assertNotNull(result)
        assertTrue(result!!.isInstrumental)
        assertEquals(LyricsSource.LRCLIB, result.source)
    }

    @Test
    fun parseLrclibJson_syncedLyrics() {
        val json = JSONObject("""
            {
                "instrumental": false,
                "syncedLyrics": "[00:12.30]Line one\n[00:15.50]Line two",
                "plainLyrics": "Line one\nLine two"
            }
        """.trimIndent())

        val result = LrclibLyricsProvider.parseLrclibJson(json)

        assertNotNull(result)
        assertTrue(result!!.isSynced)
        assertFalse(result.isInstrumental)
        assertEquals(2, result.lines.size)
        assertEquals(12300L, result.lines[0].timestampMs)
        assertEquals("Line one", result.lines[0].text)
        assertEquals(LyricsSource.LRCLIB, result.source)
    }

    @Test
    fun parseLrclibJson_plainLyricsFallback() {
        val json = JSONObject("""
            {
                "instrumental": false,
                "syncedLyrics": null,
                "plainLyrics": "Only plain lyrics available\nSecond line"
            }
        """.trimIndent())

        val result = LrclibLyricsProvider.parseLrclibJson(json)

        assertNotNull(result)
        assertFalse(result!!.isSynced)
        assertFalse(result.isInstrumental)
        assertEquals(2, result.lines.size)
        assertEquals("Only plain lyrics available", result.lines[0].text)
    }

    @Test
    fun parseLrclibJson_emptyReturnsNull() {
        val json = JSONObject("""
            {
                "instrumental": false,
                "syncedLyrics": null,
                "plainLyrics": null
            }
        """.trimIndent())

        val result = LrclibLyricsProvider.parseLrclibJson(json)
        assertNull(result)
    }
}
