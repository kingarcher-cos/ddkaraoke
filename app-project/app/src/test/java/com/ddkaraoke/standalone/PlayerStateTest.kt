package com.ddkaraoke.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    @Test
    fun keyIsLimitedAndResetsForNextSong() {
        val p = PlayerState()
        p.start("a.mp4", "A")
        repeat(10) { p.changeKey(1) }
        assertEquals(6, p.key())
        p.start("b.mp4", "B")
        assertEquals(0, p.key())
    }

    @Test
    fun playingAndStopping() {
        val p = PlayerState()
        assertFalse(p.isPlaying())
        p.start("a.mp4", "A")
        assertTrue(p.isPlaying())
        p.stop()
        assertFalse(p.isPlaying())
    }
}
