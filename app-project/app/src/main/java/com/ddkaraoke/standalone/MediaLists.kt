package com.ddkaraoke.standalone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom

// Sound board and overlay clips. Built-in lists to start with, the host can add
// from a link or remove. Added files go in private storage.
class MediaLists(private val context: Context) {
    private val dir = File(context.filesDir, "media").apply { mkdirs() }
    private val sounds = File(dir, "sounds").apply { mkdirs() }
    private val clips = File(dir, "clips").apply { mkdirs() }

    private fun read(name: String, asset: String, key: String): JSONArray {
        val f = File(dir, name)
        if (!f.isFile) f.writeText(context.assets.open(asset).bufferedReader().use { it.readText() })
        return JSONObject(f.readText()).getJSONArray(key)
    }

    @Synchronized fun cheers(): JSONArray = read("cheers.json", "web/cheers.json", "cheers")

    @Synchronized fun overlays(): JSONArray = read("overlays.json", "web/overlays.json", "overlays")

    @Synchronized private fun saveCheers(a: JSONArray) = File(dir, "cheers.json").writeText(JSONObject().put("cheers", a).toString())

    @Synchronized private fun saveOverlays(a: JSONArray) = File(dir, "overlays.json").writeText(JSONObject().put("overlays", a).toString())

    // host-added file, looked up by name and never outside its folder
    fun userFile(kind: String, name: String): File? {
        val base = if (kind == "sfx") sounds else clips
        val f = File(base, name)
        return if (f.isFile && f.parentFile?.canonicalPath == base.canonicalPath) f else null
    }

    private fun newKey(label: String): String {
        val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(14).ifEmpty { "item" }
        return "$slug-${SecureRandom().nextInt(9000) + 1000}"
    }

    @Synchronized
    fun addSound(emoji: String, label: String, bytes: ByteArray): String {
        val key = newKey(label)
        File(sounds, "$key.mp3").writeBytes(bytes)
        val a = cheers()
        // ZA WARUDO stays last
        val item = JSONObject().put("key", key).put("emoji", emoji).put("label", label).put("special", false).put("user", true)
        val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        val at = list.indexOfFirst { it.optBoolean("special") }.let { if (it < 0) list.size else it }
        list.add(at, item)
        saveCheers(JSONArray(list))
        return key
    }

    @Synchronized
    fun removeSound(key: String): Boolean {
        val a = cheers()
        val list = (0 until a.length()).map { a.getJSONObject(it) }
        val victim = list.firstOrNull { it.getString("key") == key } ?: return false
        if (victim.optBoolean("special")) return false
        saveCheers(JSONArray(list.filter { it !== victim }))
        File(sounds, "$key.mp3").delete()
        return true
    }

    @Synchronized
    fun addClip(emoji: String, label: String, placement: String, ext: String, move: (File) -> Unit): String {
        val key = newKey(label)
        val dest = File(clips, "$key.$ext")
        move(dest)
        val a = overlays()
        a.put(JSONObject().put("key", key).put("emoji", emoji).put("label", label).put("placement", placement).put("keyed", false).put("ext", ext).put("user", true))
        saveOverlays(a)
        return key
    }

    @Synchronized
    fun removeClip(key: String): Boolean {
        val a = overlays()
        val list = (0 until a.length()).map { a.getJSONObject(it) }
        val victim = list.firstOrNull { it.getString("key") == key } ?: return false
        saveOverlays(JSONArray(list.filter { it !== victim }))
        File(clips, "$key.${victim.optString("ext", "webm")}").delete()
        return true
    }

    fun clipsDir(): File = clips
}

// helpers for "add from a link"
object Net {
    // public internet addresses only, nothing on the local network or this box
    fun isPublic(url: String): Boolean {
        return try {
            val u = URL(url)
            if (u.protocol != "http" && u.protocol != "https") return false
            InetAddress.getAllByName(u.host).none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }
        } catch (e: Exception) {
            false
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 DDKaraoke")
        return c
    }

    fun text(url: String, maxBytes: Int = 600_000): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(LimitedOut(out, maxBytes)) }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    // download to [dest], throws past [maxBytes]
    fun download(url: String, dest: File, maxBytes: Long) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            if (c.contentLengthLong > maxBytes) throw java.io.IOException("That file is too big")
            var total = 0L
            dest.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("That file is too big")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private class LimitedOut(private val out: java.io.OutputStream, private var left: Int) : java.io.OutputStream() {
        override fun write(b: Int) { if (left-- <= 0) throw java.io.IOException("too big"); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > left) throw java.io.IOException("too big")
            left -= len; out.write(b, off, len)
        }
    }
}
