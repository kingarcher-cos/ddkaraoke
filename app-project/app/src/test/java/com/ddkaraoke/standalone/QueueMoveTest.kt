package com.ddkaraoke.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMoveTest {
    private fun filled(): Pair<QueueManager, List<Long>> {
        val q = QueueManager(); q.fair = false
        val ids = listOf("a", "b", "c", "d").map { q.add("Ann", Song("$it.mp4", it)).id }
        return q to ids
    }

    private fun order(q: QueueManager) = q.snapshot().map { it.song.title }

    @Test
    fun playNextUpDownBottom() {
        val (q, ids) = filled()
        assertTrue(q.move(ids[3], "top")); assertEquals(listOf("d", "a", "b", "c"), order(q))
        assertTrue(q.move(ids[2], "up")); assertEquals(listOf("d", "a", "c", "b"), order(q))
        assertTrue(q.move(ids[0], "down")); assertEquals(listOf("d", "c", "a", "b"), order(q))
        assertTrue(q.move(ids[3], "bottom")); assertEquals("d", order(q).last())
    }

    @Test
    fun dragToAPlaceAndUnknownSongs() {
        val (q, ids) = filled()
        assertTrue(q.moveTo(ids[0], 2)); assertEquals(listOf("b", "c", "a", "d"), order(q))
        assertFalse(q.move(999, "top"))
        assertFalse(q.move(ids[1], "sideways"))
        assertEquals(4, order(q).size)
    }
}
