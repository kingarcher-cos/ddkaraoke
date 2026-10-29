package com.ddkaraoke.standalone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingAndRulesTest {
    @Test
    fun aSongCanBeReadWhileItIsStillArriving() {
        val dir = java.nio.file.Files.createTempDirectory("ddk").toFile()
        val part = File(dir, "s.mp4.part")
        val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val job = DownloadJob("s.mp4", RemoteSong("k", "b", "t", "", "", "", 0, "s.mp4"), false)
        job.size = data.size.toLong()
        job.state = "downloading"
        part.writeBytes(ByteArray(0))
        val writer = Thread {
            val out = java.io.RandomAccessFile(part, "rw")
            var at = 0
            while (at < data.size) {
                val n = minOf(20_000, data.size - at)
                out.write(data, at, n)
                at += n
                job.done = at.toLong()
                Thread.sleep(20)
            }
            out.close()
            job.state = "done"
        }
        writer.start()
        val got = GrowingInput(part, 1000, job).use { it.readBytes() }
        writer.join()
        assertArrayEquals(data.copyOfRange(1000, data.size), got)
        dir.deleteRecursively()
    }

    @Test
    fun playbackCanStartBeforeTheDownloadEnds() {
        val job = DownloadJob("s.mp4", RemoteSong("k", "b", "t", "", "", "", 0, "s.mp4"), false)
        job.size = 50_000_000
        job.state = "downloading"
        job.done = 1_000_000
        assertTrue(!job.canPlay())
        job.done = 5_000_001
        assertTrue(job.canPlay())
        job.state = "done"
        assertTrue(job.canPlay())
    }

    @Test
    fun cooldownsMatchTheHomeServer() {
        val e = Events()
        assertEquals(0, e.timeStopLeft(90))
        assertTrue(e.timeStopLeft(90) > 80)          // for everyone, not per phone
        assertNull(e.overlayBlocked("a", 10, 8))
        assertTrue(e.overlayBlocked("b", 10, 8)!!.startsWith("Another clip is playing"))   // one at a time
    }

    @Test
    fun queueCanBePlainFirstComeFirstServedAndLimited() {
        val q = QueueManager()
        q.fair = false
        q.add("Ann", Song("1.mp4", "1")); q.add("Ann", Song("2.mp4", "2")); q.add("Bob", Song("3.mp4", "3"))
        assertEquals(listOf("1", "2", "3"), q.snapshot().map { it.song.title })
        assertEquals(2, q.countFor("ann"))
    }
}
