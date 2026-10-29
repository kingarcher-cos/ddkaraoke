package com.ddkaraoke.standalone

import android.os.StatFs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DownloadJob(val file: String, val remote: RemoteSong, val keep: Boolean) {
    @Volatile var state = "waiting"      // waiting / downloading / done / error / cancelled
    @Volatile var done = 0L
    @Volatile var size = 0L
    @Volatile var message = ""
    @Volatile var cancelled = false

    fun percent(): Int = if (size > 0) ((done * 100) / size).toInt().coerceIn(0, 100) else 0

    // enough is in to start playing
    fun canPlay(): Boolean = state == "done" || (state == "downloading" && done >= maxOf(3_000_000L, size / 10))
}

// Input stream over a file that is still downloading. Blocks until the next bytes arrive.
class GrowingInput(private val path: File, start: Long, private val job: DownloadJob) : InputStream() {
    private val raf = RandomAccessFile(path, "r")
    private var pos = start

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var waited = 0
        while (pos >= job.done) {
            if (job.state != "downloading" && job.state != "waiting") return -1
            Thread.sleep(100)
            waited += 100
            if (waited > 120_000) return -1
        }
        val n = minOf(len.toLong(), job.done - pos).toInt()
        raf.seek(pos)
        val r = raf.read(b, off, n)
        if (r > 0) pos += r
        return r
    }

    override fun close() { try { raf.close() } catch (e: Exception) { } }
}

// Rolling download buffer: only the next songs in the queue are fetched, two at a time.
// Playback can start before a download finishes. Old downloads get evicted when the space
// limit or free space runs out.
class Downloader(
    private val library: Library,
    private val cache: CacheManifest,
    private val cacheGb: () -> Int,
    private val protect: () -> Set<String>,
) {
    private val jobs = ConcurrentHashMap<String, DownloadJob>()
    private val pool = Executors.newFixedThreadPool(2)
    private val minFreeBytes = 500L * 1000 * 1000

    fun job(file: String): DownloadJob? = jobs[file]

    fun allJobs(): List<DownloadJob> = jobs.values.toList()

    fun forget(file: String) { jobs.remove(file) }

    fun isLocal(file: String): Boolean = library.has(file)

    fun partFile(name: String): File = File(library.folderFile(), "$name.part")

    // called every second with the next queued database songs, in order
    fun plan(wanted: List<RemoteSong>) {
        for (r in wanted) {
            if (isLocal(r.fileName)) { jobs.remove(r.fileName); continue }
            if (jobs.containsKey(r.fileName)) continue
            start(r, keep = false)
        }
    }

    // "save for later": fetch now and keep
    fun saveForLater(r: RemoteSong): Boolean {
        if (isLocal(r.fileName)) { cache.setKept(r.fileName, true); return true }
        val j = jobs[r.fileName]
        if (j != null && j.state != "error" && j.state != "cancelled") return true
        jobs.remove(r.fileName)
        start(r, keep = true)
        return true
    }

    private fun start(r: RemoteSong, keep: Boolean) {
        val j = DownloadJob(r.fileName, r, keep)
        jobs[r.fileName] = j
        pool.submit { run(j) }
    }

    // stop fetching a song that will not be played
    fun cancel(file: String) {
        val j = jobs[file] ?: return
        if (j.keep) return
        j.cancelled = true
    }

    private fun resolve(r: RemoteSong): Pair<String, Long> =
        if (r.kind == "yt") Yt.location(r.kid) else Km.videoLocation(r.base, r.kid)

    private fun run(j: DownloadJob) {
        j.state = "downloading"
        val part = partFile(j.file)
        try {
            val (url, size) = resolve(j.remote)
            j.size = size
            makeRoom(size)
            val folder = library.folderFile()
            folder.mkdirs()
            var from = if (part.isFile) part.length() else 0L
            if (size > 0 && from >= size) { part.delete(); from = 0 }
            var conn = Km.openDownload(url, from)
            var code = conn.responseCode
            if (from > 0 && code == 200) {            // range ignored, restart
                conn.disconnect(); part.delete(); from = 0
                conn = Km.openDownload(url, 0); code = conn.responseCode
            }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (j.size <= 0) j.size = from + conn.contentLengthLong.coerceAtLeast(0)
            j.done = from
            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(from)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (j.cancelled) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        j.done += n
                    }
                }
            }
            conn.disconnect()
            if (j.size > 0 && part.length() != j.size) throw IOException("The download stopped early")
            val dest = File(folder, j.file)
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save the song")
            cache.add(CacheEntry(j.file, j.remote.kid, dest.length(), System.currentTimeMillis(), j.keep))
            j.state = "done"
        } catch (e: Exception) {
            if (j.cancelled) {
                part.delete()
                j.state = "cancelled"
                jobs.remove(j.file)
            } else {
                j.message = e.message ?: "Download failed"
                j.state = "error"
            }
        }
    }

    private fun makeRoom(need: Long) {
        val folder = library.folderFile()
        folder.mkdirs()
        val free = try { StatFs(folder.absolutePath).availableBytes } catch (e: Exception) { Long.MAX_VALUE / 2 }
        val keep = protect()
        val victims = CachePolicy.victims(cache.entries(), keep, need, cacheGb() * 1_000_000_000L, free, minFreeBytes)
            ?: throw IOException("Not enough space for this song. Raise the space limit or free some space.")
        victims.forEach { cache.remove(it.file) }
    }
}
