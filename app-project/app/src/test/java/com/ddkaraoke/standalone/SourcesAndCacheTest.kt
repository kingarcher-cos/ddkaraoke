package com.ddkaraoke.standalone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesAndCacheTest {
    @Test
    fun sameLinkIgnoresSpellingDifferences() {
        assertEquals(Km.sameLink("https://www.Example.com/"), Km.sameLink("http://example.com"))
        assertFalse(Km.sameLink("http://example.com/a") == Km.sameLink("http://example.com/b"))
    }

    @Test
    fun addressCheck() {
        assertTrue(Km.looksLikeAddress("https://songs.example.org"))
        assertFalse(Km.looksLikeAddress("songs.example.org"))
        assertFalse(Km.looksLikeAddress("javascript:alert(1)"))
    }

    @Test
    fun fileNameFromSongData() {
        val k = JSONObject(
            """{"kid":"abc","titles":{"eng":"Cool Song"},"series":[{"name":"Some Show"}],
               "songtypes":[{"name":"OP"}],"songorder":2,"singers":[{"name":"Ann"},{"name":"Bob"},{"name":"Cy"}]}"""
        )
        assertEquals("Some Show - OP2 - Cool Song (Ann, Bob).mp4", Km.fileName(k))
        val r = Km.toRemote("https://x.example", k)
        assertEquals("abc", r.kid)
        assertEquals("OP2", r.type)
    }

    @Test
    fun fileNameRemovesUnsafeCharacters() {
        val k = JSONObject("""{"kid":"k1","titles":{"eng":"A/B: C?"}}""")
        assertEquals("AB C.mp4", Km.fileName(k))
    }

    private fun e(name: String, size: Long, at: Long, kept: Boolean = false) = CacheEntry(name, name, size, at, kept)

    @Test
    fun oldestSongsMakeRoomFirst() {
        val list = listOf(e("a", 400, 1), e("b", 400, 2), e("c", 400, 3))
        val v = CachePolicy.victims(list, emptySet(), need = 500, limit = 1500, free = 10_000, minFree = 0)
        assertEquals(listOf("a"), v!!.map { it.file })
    }

    @Test
    fun keptAndQueuedSongsAreNeverRemoved() {
        val list = listOf(e("a", 400, 1, kept = true), e("b", 400, 2), e("c", 400, 3))
        val v = CachePolicy.victims(list, setOf("b"), need = 500, limit = 1500, free = 10_000, minFree = 0)
        assertEquals(listOf("c"), v!!.map { it.file })
    }

    @Test
    fun saysNoWhenNothingCanGo() {
        val list = listOf(e("a", 400, 1, kept = true))
        assertNull(CachePolicy.victims(list, emptySet(), need = 2000, limit = 1000, free = 10_000, minFree = 0))
    }

    @Test
    fun freeSpaceIsAlsoChecked() {
        val list = listOf(e("a", 100, 1), e("b", 100, 2))
        // plenty of limit, but the box itself is nearly full
        val v = CachePolicy.victims(list, emptySet(), need = 150, limit = 10_000, free = 200, minFree = 100)
        assertNotNull(v)
        assertEquals(1, v!!.size)
    }
}
