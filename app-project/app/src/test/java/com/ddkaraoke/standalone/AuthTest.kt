package com.ddkaraoke.standalone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    @Test
    fun rightPinGivesHostAccessAndTvIsAlwaysHost() {
        val a = Auth { "1234" }
        val (token, _) = a.login("1234")
        assertNotNull(token)
        assertTrue(a.isHost(token, "192.168.0.50"))
        assertFalse(a.isHost(null, "192.168.0.50"))
        assertFalse(a.isHost("bogus", "192.168.0.50"))
        assertTrue(a.isHost(null, "127.0.0.1"))
    }

    @Test
    fun fiveWrongTriesLockThePinBox() {
        val a = Auth { "1234" }
        repeat(5) { assertNull(a.login("0000").first) }
        assertNull(a.login("1234").first)   // locked, even with the right PIN
    }

    @Test
    fun loggingOutEndsHostAccess() {
        val a = Auth { "1234" }
        val token = a.login("1234").first
        assertTrue(a.isHost(token, "192.168.0.50"))
        assertTrue(a.hostActive(0L))
        a.logout(token)
        assertFalse(a.isHost(token, "192.168.0.50"))
        assertFalse(a.hostActive(0L))
    }
}
