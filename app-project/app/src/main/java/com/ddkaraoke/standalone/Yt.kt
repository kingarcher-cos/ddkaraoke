package com.ddkaraoke.standalone

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.MediaFormat
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class YtItem(val id: String, val title: String, val channel: String, val seconds: Long, val thumb: String)

// YouTube access through NewPipeExtractor, so the box needs no helper program.
object Yt {
    private class Http : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
            for ((k, v) in request.headers()) conn.setRequestProperty(k, v.joinToString(", "))
            request.dataToSend()?.let {
                conn.doOutput = true
                conn.outputStream.use { o -> o.write(it) }
            }
            val code = conn.responseCode
            if (code == 429) { conn.disconnect(); throw ReCaptchaException("Too many requests", request.url()) }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) headers[k] = v
            val finalUrl = conn.url.toString()
            conn.disconnect()
            return Response(code, conn.responseMessage ?: "", headers, body, finalUrl)
        }
    }

    @Volatile private var ready = false

    @Synchronized
    private fun init() {
        if (ready) return
        NewPipe.init(Http(), Localization.DEFAULT)
        ready = true
    }

    private val pages = ConcurrentHashMap<String, Triple<String, Page, Long>>()

    private fun toItem(i: StreamInfoItem): YtItem? {
        val id = Regex("[?&]v=([\\w-]{11})").find(i.url)?.groupValues?.get(1) ?: return null
        val thumb = i.thumbnails.firstOrNull()?.url ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        return YtItem(id, i.name, i.uploaderName ?: "", i.duration, thumb)
    }

    // one page of results plus a token for the next page (null at the end)
    fun search(query: String, token: String?): Pair<List<YtItem>, String?> {
        init()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val items: List<Any>
        val next: Page?
        val saved = token?.let { pages[it] }
        if (saved != null) {
            val page = SearchInfo.getMoreItems(service, handler, saved.second)
            items = page.items
            next = page.nextPage
        } else {
            val info = SearchInfo.getInfo(service, handler)
            items = info.relatedItems
            next = info.nextPage
        }
        val out = items.filterIsInstance<StreamInfoItem>().mapNotNull { toItem(it) }
        var nextToken: String? = null
        if (next != null) {
            nextToken = UUID.randomUUID().toString()
            pages[nextToken] = Triple(query, next, System.currentTimeMillis())
            val cutoff = System.currentTimeMillis() - 30 * 60_000
            pages.entries.removeIf { it.value.third < cutoff }
        }
        return out to nextToken
    }

    // direct URL of a combined video+audio stream, with its size
    fun location(id: String): Pair<String, Long> {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val best = info.videoStreams
            .filter { !it.isVideoOnly && it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { it.height }
            ?: throw java.io.IOException("No playable video was offered for this one")
        val url = best.content
        return url to Km.contentLength(url)
    }
}
