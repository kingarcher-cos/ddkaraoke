package com.ddkaraoke.standalone

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// A song database added in Settings (Karaoke Mugen style server).
data class SourceRef(val url: String, val name: String)

// A search hit. May not be on the box yet.
data class RemoteSong(
    val kid: String,
    val base: String,
    val title: String,
    val series: String,
    val type: String,
    val singers: String,
    val sizeMb: Int,
    val fileName: String,
    val kind: String = "km",          // km = song database, yt = video site
    val thumb: String = "",
    val tags: List<String> = emptyList(),
    val seconds: Long = 0,
)

// Talks to song databases, with the same requests as the home server.
object Km {
    // normalise a link so duplicates can be spotted
    fun sameLink(url: String): String {
        val u = url.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/')
        val host = u.substringBefore('/').lowercase().removePrefix("www.")
        val rest = if (u.contains('/')) "/" + u.substringAfter('/') else ""
        return host + rest
    }

    fun looksLikeAddress(url: String) = Regex("^https?://[A-Za-z0-9.-]+(:\\d+)?(/[^\\s]*)?$").matches(url)

    private fun pickTitle(k: JSONObject): String {
        val titles = k.optJSONObject("titles")
        if (titles != null) {
            for (key in listOf("eng", "qro")) titles.optString(key, "").takeIf { it.isNotEmpty() }?.let { return it }
            val it = titles.keys()
            while (it.hasNext()) titles.optString(it.next(), "").takeIf { s -> s.isNotEmpty() }?.let { s -> return s }
        }
        return k.optString("songname", "").ifEmpty { "Unknown" }
    }

    private fun firstNames(arr: JSONArray?, n: Int): String {
        if (arr == null) return ""
        return (0 until minOf(arr.length(), n)).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotEmpty() } }
            .joinToString(", ")
    }

    fun fileName(k: JSONObject): String {
        val title = pickTitle(k)
        val series = firstNames(k.optJSONArray("series") ?: k.optJSONArray("franchises"), 1)
        var type = firstNames(k.optJSONArray("songtypes"), 1)
        if (type.isNotEmpty() && k.optInt("songorder", 0) != 0) type += k.optInt("songorder")
        val singers = firstNames(k.optJSONArray("singers") ?: k.optJSONArray("singergroups"), 2)
        var base = listOf(series, type, title).filter { it.isNotEmpty() }.joinToString(" - ")
        if (singers.isNotEmpty()) base += " ($singers)"
        base = base.replace(Regex("[\\\\/:*?\"<>|]+"), "").trim().take(150)
        if (base.isEmpty()) base = k.optString("kid", "song")
        return "$base.mp4"
    }

    fun toRemote(base: String, k: JSONObject): RemoteSong {
        val series = firstNames(k.optJSONArray("series") ?: k.optJSONArray("franchises"), 1)
        var type = firstNames(k.optJSONArray("songtypes"), 1)
        if (type.isNotEmpty() && k.optInt("songorder", 0) != 0) type += k.optInt("songorder")
        return RemoteSong(
            kid = k.getString("kid"),
            base = base,
            title = pickTitle(k),
            series = series,
            type = type,
            singers = firstNames(k.optJSONArray("singers") ?: k.optJSONArray("singergroups"), 2),
            sizeMb = Math.round(k.optLong("mediasize", 0) / 1e6).toInt(),
            fileName = fileName(k),
            thumb = "$base/previews/${k.optString("kid")}.${k.optLong("mediasize", 0)}.25.avif",
            tags = (0 until (k.optJSONArray("versions")?.length() ?: 0)).mapNotNull { k.optJSONArray("versions")?.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotEmpty() } },
            seconds = k.optLong("duration", 0),
        )
    }

    // network

    private fun open(url: String, method: String = "GET", timeoutMs: Int = 20000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "DDKaraoke")
        c.instanceFollowRedirects = true
        return c
    }

    private fun getJson(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // collections to search (the site hides most songs unless asked)
    const val COLLECTIONS = "c7db86a0-ff64-4044-9be4-66dd1ef1d1c1,dbcf2c22-524d-4708-99bb-601703633927,2fa2fe3f-bb56-45ee-aa38-eae60e76f224,efe171c0-e8a1-4d03-98c0-60ecf741ad52"

    data class Page(val items: List<JSONObject>, val rawCount: Int, val total: Int)

    // one page of songs that come with a lyrics video
    fun searchPage(base: String, q: String, size: Int = 20, from: Int = 0): Page {
        var last: Exception? = null
        for (withCollections in listOf(true, false)) {
            try {
                val url = "$base/api/karas/search?filter=${URLEncoder.encode(q, "UTF-8")}&size=$size&from=$from" +
                    if (withCollections) "&collections=$COLLECTIONS" else ""
                val text = getJson(url)
                val obj = if (text.trimStart().startsWith("[")) null else JSONObject(text)
                val root = if (obj == null) JSONArray(text) else obj.optJSONArray("content") ?: JSONArray()
                val total = obj?.optJSONObject("infos")?.optInt("count", root.length()) ?: root.length()
                val all = (0 until root.length()).map { root.getJSONObject(it) }
                if (all.isNotEmpty() || !withCollections || from > 0) {
                    return Page(all.filter { it.optString("hardsubbed_mediafile").isNotEmpty() }, all.size, total)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        if (last != null) throw last
        return Page(emptyList(), 0, 0)
    }

    // songs that come with a lyrics video
    fun search(base: String, q: String, size: Int = 10): List<JSONObject> = searchPage(base, q, size, 0).items

    fun details(base: String, kid: String): JSONObject = JSONObject(getJson("$base/api/karas/${URLEncoder.encode(kid, "UTF-8")}"))

    // real video address and size
    fun videoLocation(base: String, kid: String): Pair<String, Long> {
        val c = open("$base/api/karas/${URLEncoder.encode(kid, "UTF-8")}/hardsub", "HEAD", 30000)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.url.toString() to c.contentLengthLong
        } finally {
            c.disconnect()
        }
    }

    // total size, read from a 1 byte range request
    fun contentLength(url: String): Long {
        val c = open(url, "GET", 30000)
        c.setRequestProperty("Range", "bytes=0-0")
        try {
            val range = c.getHeaderField("Content-Range")
            if (range != null && range.contains("/")) range.substringAfter("/").toLongOrNull()?.let { return it }
            return c.contentLengthLong
        } finally {
            c.disconnect()
        }
    }

    fun openDownload(url: String, from: Long): HttpURLConnection {
        val c = open(url, "GET", 30000)
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }
}
