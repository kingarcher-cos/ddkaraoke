package com.ddkaraoke.standalone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HistoryTest {
    @Test
    fun rankingsCountOnlyPlayedSongsAndCanBeFilteredBySession() {
        val h = HistoryStore(null)
        h.record("Ann", "Song A", "a.mp4", null, skipped = false, now = 1)
        h.record("Ann", "Song A", "a.mp4", null, skipped = false, now = 2)
        h.record("Bob", "Song B", "b.mp4", null, skipped = true, now = 3)
        h.newSession("Friday", now = 10)
        h.record("Bob", "Song B", "b.mp4", null, skipped = false, now = 11)

        assertEquals(listOf("Song A" to 2, "Song B" to 1), h.topSongs(null, 10).map { it.name to it.plays })
        assertEquals(listOf("Song B" to 1), h.topSongs(2, 10).map { it.name to it.plays })
        assertEquals(listOf("Ann" to 2, "Bob" to 1), h.topPerformers(null, 10).map { it.name to it.plays })
        assertEquals(listOf("Song A"), h.topSongs(null, 1).map { it.name })
    }

    @Test
    fun listIsNewestFirstAndSessionsAreNamed() {
        val h = HistoryStore(null)
        h.record("Ann", "One", "1.mp4", null, false, now = 5)
        h.record("Ann", "Two", "2.mp4", null, true, now = 9)
        assertEquals(listOf("Two", "One"), h.list(null).map { it.title })
        assertEquals("", h.currentSession().name)
        h.newSession("Party")
        assertEquals("Party", h.currentSession().name)
        assertEquals(2, h.sessionList().size)
    }

    @Test
    fun historySurvivesARestart() {
        val f = File.createTempFile("history", ".json")
        try {
            val a = HistoryStore(f)
            a.record("Ann", "Kept", "k.mp4", null, false, now = 1)
            a.newSession("Night")
            val b = HistoryStore(f)
            assertEquals(listOf("Kept"), b.list(null).map { it.title })
            assertEquals("Night", b.currentSession().name)
        } finally {
            f.delete()
        }
    }
}
