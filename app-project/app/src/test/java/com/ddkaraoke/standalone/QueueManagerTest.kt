package com.ddkaraoke.standalone

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueManagerTest {
    private fun song(n: String) = Song("$n.mp4", n)

    @Test
    fun singersTakeTurns() {
        val q = QueueManager()
        q.add("Ann", song("a1")); q.add("Ann", song("a2")); q.add("Ann", song("a3"))
        q.add("Bob", song("b1"))
        q.add("Cy", song("c1")); q.add("Bob", song("b2"))
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "a3"), q.snapshot().map { it.song.title })
    }

    @Test
    fun removeAndTakeNext() {
        val q = QueueManager()
        val a = q.add("Ann", song("a1")); q.add("Bob", song("b1"))
        assertEquals("a1", q.takeNext()?.song?.title)
        q.remove(q.snapshot().first().id)
        assertEquals(0, q.snapshot().size)
        assertEquals(1L, a.id)
    }
}
