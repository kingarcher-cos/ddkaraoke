package com.ddkaraoke.standalone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    @Test
    fun onlyNewEventsAreReturned() {
        val e = Events()
        e.add("cheer", JSONObject().put("user", "a"))
        val firstLast = e.since(-1).second
        e.add("message", JSONObject().put("text", "hi"))
        val (list, last) = e.since(firstLast)
        assertEquals(1, list.size)
        assertEquals("message", list[0].getString("type"))
        assertEquals(2L, last)
    }

    @Test
    fun cooldownBlocksRepeatsFromSameDevice() {
        val e = Events()
        assertEquals(0, e.cooldownLeft("d1", 3))
        assertTrue(e.cooldownLeft("d1", 3) > 0)
        assertEquals(0, e.cooldownLeft("d2", 3))
    }
}
