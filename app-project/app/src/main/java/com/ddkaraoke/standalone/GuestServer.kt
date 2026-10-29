package com.ddkaraoke.standalone

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

// Web server on the box. Serves the guest page and the API.
class GuestServer(
    private val context: Context,
    port: Int,
    private val library: Library,
    private val queue: QueueManager,
    private val events: Events,
    private val player: PlayerState,
    private val settings: Settings,
    private val cache: CacheManifest,
    private val downloader: Downloader,
    private val auth: Auth,
    private val history: HistoryStore,
    private val media: MediaLists,
    private val qrPng: () -> ByteArray?,
    private val onSongEnded: (skipped: Boolean) -> Unit,
    private val onQuit: () -> Unit,
    private val onPrefsChanged: () -> Unit,
    private val onCloseSettings: () -> Unit,
    private val isAccepted: () -> Boolean,
    private val inSingapore: () -> Boolean,
    private val nowPlaying: () -> String?,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!isAccepted() && !isTv(session)) {
                return if (session.uri == "/") {
                    newFixedLengthResponse(
                        Response.Status.OK, "text/html; charset=utf-8",
                        "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;background:#222826;color:#fff;padding:2rem;text-align:center'>" +
                            "<h2>Almost ready</h2><p>The host needs to press <b>I understand</b> on the TV first. Then refresh this page.</p></body>",
                    )
                } else {
                    json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("ok", false).put("error", "The host has not finished setting up on the TV yet."))
                }
            }
            when {
                session.method == Method.GET && session.uri == "/" -> page()
                session.method == Method.GET && session.uri == "/logo.png" -> asset(session, "web/logo.png")
                session.method == Method.GET && session.uri.startsWith("/static/") -> asset(session, "web/" + session.uri.removePrefix("/static/"))
                session.method == Method.GET && session.uri == "/tv.html" -> assetText("tv.html")
                session.method == Method.GET && session.uri.startsWith("/media/") -> media(session)
                session.method == Method.GET && session.uri == "/api/player" -> json(Response.Status.OK, player.toJson().put("score", settings.scoreEnabled))
                session.method == Method.POST && session.uri == "/api/vocals" -> vocals(session)
                session.method == Method.POST && session.uri == "/api/vocalstyle" -> { player.setStyle(body(session).optString("mode")); ok() }
                session.method == Method.POST && session.uri == "/api/key" -> key(session)
                session.method == Method.POST && session.uri == "/api/seek" -> seek(session)
                session.method == Method.POST && session.uri == "/api/skip" -> hostOnly(session) { session.parseBody(HashMap()); onSongEnded(true); ok() }
                session.method == Method.POST && session.uri == "/api/skipscore" -> hostOnly(session) { session.parseBody(HashMap()); player.skipScore(); ok() }
                session.method == Method.GET && session.uri == "/api/joinqr.png" -> joinQr()
                session.method == Method.POST && session.uri == "/api/login" -> login(session)
                session.method == Method.POST && session.uri == "/api/logout" -> logout(session)
                session.method == Method.GET && session.uri == "/api/admin" -> json(Response.Status.OK, JSONObject().put("admin", isHost(session)))
                session.method == Method.GET && session.uri == "/api/settings" -> hostOnly(session) { settingsJson(session) }
                session.method == Method.POST && session.uri == "/api/settings" -> hostOnly(session) { saveSettings(session) }
                session.method == Method.POST && session.uri == "/api/queue/remove" -> hostOnly(session) { removeFromQueue(session) }
                session.method == Method.POST && session.uri == "/api/ended" -> { val b = body(session); onSongEnded(b.optBoolean("failed", false)); ok() }
                session.method == Method.GET && session.uri == "/effects.html" -> assetText("effects.html")
                session.method == Method.GET && session.uri == "/api/cheers" -> json(Response.Status.OK, JSONObject().put("cheers", media.cheers()))
                session.method == Method.GET && session.uri == "/api/overlays" -> json(Response.Status.OK, JSONObject().put("overlays", media.overlays()))
                session.method == Method.GET && session.uri == "/api/events" -> eventsSince(session)
                session.method == Method.POST && session.uri == "/api/cheer" -> cheer(session)
                session.method == Method.POST && session.uri == "/api/overlay" -> overlay(session)
                session.method == Method.POST && session.uri == "/api/message" -> message(session)
                session.method == Method.GET && session.uri == "/api/sources/search" -> communitySearch(session)
                session.method == Method.GET && session.uri == "/api/youtube/search" -> youtubeSearch(session)
                session.method == Method.GET && session.uri == "/api/downloads" -> downloadsJson()
                session.method == Method.POST && session.uri == "/api/songs/delete" -> hostOnly(session) { deleteSong(session) }
                session.method == Method.GET && session.uri == "/api/prefs" -> json(Response.Status.OK, prefsMap())
                session.method == Method.POST && session.uri == "/api/prefs" -> hostOnly(session) { savePrefs(session) }
                session.method == Method.POST && session.uri == "/api/position" -> { val b = body(session); player.setPosition(b.optDouble("t", 0.0), b.optDouble("d", 0.0)); ok() }
                session.method == Method.POST && session.uri == "/api/audioinfo" -> { player.setMono(body(session).optBoolean("mono", false)); ok() }
                session.method == Method.POST && session.uri == "/api/queue/move" -> moveInQueue(session)
                session.method == Method.POST && session.uri == "/api/closesettings" -> { session.parseBody(HashMap()); if (isTv(session)) onCloseSettings(); ok() }
                session.method == Method.GET && session.uri == "/api/promo" -> promo()
                session.method == Method.GET && session.uri == "/api/colour" -> json(Response.Status.OK, JSONObject().put("colour", colourFor(session.parms["name"] ?: "")))
                session.method == Method.POST && session.uri == "/api/colour" -> setColour(session)
                session.method == Method.POST && session.uri == "/api/quit" -> hostOnly(session) { session.parseBody(HashMap()); onQuit(); ok() }
                session.method == Method.POST && session.uri == "/api/request-remote" -> requestRemote(session)
                session.method == Method.POST && session.uri == "/api/keep" -> keep(session)
                session.method == Method.GET && session.uri == "/api/rankings" -> rankings(session)
                session.method == Method.GET && session.uri == "/api/history" -> historyJson(session)
                session.method == Method.GET && session.uri == "/api/sessions" -> sessionsJson()
                session.method == Method.POST && session.uri == "/api/session/new" -> hostOnly(session) { newSession(session) }
                session.method == Method.POST && session.uri == "/api/cheers/add" -> hostOnly(session) { addSound(session) }
                session.method == Method.POST && session.uri == "/api/cheers/remove" -> hostOnly(session) { removeSound(session) }
                session.method == Method.POST && session.uri == "/api/overlays/add" -> hostOnly(session) { addClip(session) }
                session.method == Method.POST && session.uri == "/api/overlays/remove" -> hostOnly(session) { removeClip(session) }
                session.method == Method.GET && session.uri == "/api/songs" -> songs(session)
                session.method == Method.GET && session.uri == "/api/queue" -> queueJson()
                session.method == Method.POST && session.uri == "/api/request" -> request(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
            }
        } catch (e: Throwable) {
            // one bad request must not take the app down
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", "Something went wrong"))
        }
    }

    private fun page(): Response {
        val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // theme.css with the host's accent colour filled in
    private fun themeCss(): Response {
        val accent = settings.text("accent", "#c3b94b")
        val css = context.assets.open("web/theme.css").bufferedReader().use { it.readText() }
        val r = accent.substring(1, 3).toInt(16) / 255.0
        val g = accent.substring(3, 5).toInt(16) / 255.0
        val b = accent.substring(5, 7).toInt(16) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        var h = when { d == 0.0 -> 0.0; mx == r -> 60 * (((g - b) / d) % 6); mx == g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
        if (h < 0) h += 360
        val hue = Math.round(h)
        val out = css.replace("#c3b94b", accent)
            .replace(Regex("hsl\\(\\d+, 45%, 13%\\)"), "hsl($hue, 45%, 13%)")
            .replace(Regex("hsla\\(\\d+, 65%, 74%, \\.18\\)"), "hsla($hue, 65%, 74%, .18)")
        return newFixedLengthResponse(Response.Status.OK, "text/css; charset=utf-8", out)
    }

    private fun asset(session: IHTTPSession, path: String): Response {
        if (path.contains("..")) return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        if (path == "web/theme.css") return themeCss()
        // sounds and clips added by the host are in private storage
        val kind = when { path.startsWith("web/sfx/") -> "sfx"; path.startsWith("web/overlays/") -> "clips"; else -> null }
        if (kind != null) {
            val f = media.userFile(kind, path.substringAfterLast('/'))
            if (f != null) {
                val mime = when (f.extension.lowercase()) { "mp3" -> "audio/mpeg"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; else -> "video/mp4" }
                return ranged(session, mime, f.length()) { start -> java.io.FileInputStream(f).also { it.channel.position(start) } }
            }
        }
        val bytes = try {
            context.assets.open(path.substringBefore("?")).use { it.readBytes() }
        } catch (e: Exception) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
        val mime = when (path.substringBefore("?").substringAfterLast('.').lowercase()) {
            "css" -> "text/css; charset=utf-8"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "svg" -> "image/svg+xml"
            "js" -> "application/javascript"
            "mp3" -> "audio/mpeg"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        return ranged(session, mime, bytes.size.toLong()) { start -> java.io.ByteArrayInputStream(bytes, start.toInt(), bytes.size - start.toInt()) }
    }

    private fun cookie(session: IHTTPSession, name: String): String? =
        session.headers["cookie"]?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private fun isHost(session: IHTTPSession) = auth.isHost(cookie(session, "kmadmin"), session.remoteIpAddress)

    private fun isTv(session: IHTTPSession) = session.remoteIpAddress.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" }

    private fun hostOnly(session: IHTTPSession, block: () -> Response): Response =
        if (isHost(session)) block()
        else json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "Host PIN needed."))

    private fun login(session: IHTTPSession): Response {
        val (token, error) = auth.login(body(session).optString("pin"))
        if (token == null) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", error))
        val r = json(Response.Status.OK, JSONObject().put("ok", true))
        r.addHeader("Set-Cookie", "kmadmin=$token; Path=/; Max-Age=43200; SameSite=Lax")
        return r
    }

    private fun logout(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        auth.logout(cookie(session, "kmadmin"))
        val r = json(Response.Status.OK, JSONObject().put("ok", true))
        r.addHeader("Set-Cookie", "kmadmin=; Path=/; Max-Age=0; SameSite=Lax")
        return r
    }

    private fun storageOptions(): JSONArray {
        val arr = JSONArray()
        val dirs = context.getExternalFilesDirs("songs")
        dirs.forEachIndexed { i, d ->
            if (d == null) return@forEachIndexed
            d.mkdirs()
            val stat = try { android.os.StatFs(d.absolutePath) } catch (e: Exception) { null }
            val free = stat?.let { it.availableBytes / 1e9 } ?: 0.0
            val total = stat?.let { it.totalBytes / 1e9 } ?: 0.0
            arr.put(
                JSONObject()
                    .put("path", d.absolutePath)
                    .put("label", if (i == 0) "Built-in storage" else "External drive $i")
                    .put("freeGb", Math.round(free * 10) / 10.0)
                    .put("totalGb", Math.round(total * 10) / 10.0)
            )
        }
        return arr
    }

    private fun settingsJson(session: IHTTPSession): Response {
        val o = JSONObject()
            .put("folder", library.path())
            .put("options", storageOptions())
            .put("cacheGb", settings.cacheGb)
            .put("scoreEnabled", settings.scoreEnabled)
            .put("version", try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" })
            .put("sources", JSONArray(settings.sourcesJson))
            .put("songCount", library.songs().size)
        // PIN is shown on the TV only
        if (isTv(session)) o.put("pin", settings.pin)
        return json(Response.Status.OK, o)
    }

    private fun saveSettings(session: IHTTPSession): Response {
        val b = body(session)
        if (b.has("folder")) {
            val f = b.optString("folder")
            val allowed = context.getExternalFilesDirs("songs").filterNotNull().map { it.absolutePath }
            if (f !in allowed) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "That place is not available."))
            settings.folder = f
            library.setFolder(java.io.File(f))
        }
        if (b.has("cacheGb")) settings.cacheGb = b.optInt("cacheGb", 2)
        if (b.has("scoreEnabled")) settings.scoreEnabled = b.optBoolean("scoreEnabled", true)
        if (b.has("sources")) {
            val error = saveSources(b.getJSONArray("sources"))
            if (error != null) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", error))
        }
        if (b.has("newPin")) {
            val p = b.optString("newPin")
            if (!Regex("^[0-9]{4}$").matches(p)) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "The PIN must be 4 digits."))
            settings.pin = p
        }
        return ok()
    }

    // anyone can reorder, only the host can remove
    private fun moveInQueue(session: IHTTPSession): Response {
        val b = body(session)
        val moved = if (b.has("to")) queue.moveTo(b.optLong("id"), b.optInt("to")) else queue.move(b.optLong("id"), b.optString("action"))
        return if (moved) ok() else fail("That song is not waiting any more.")
    }

    private fun removeFromQueue(session: IHTTPSession): Response {
        queue.remove(body(session).optLong("id"))
        return ok()
    }

    private fun sources(): List<SourceRef> {
        val arr = JSONArray(settings.sourcesJson)
        return (0 until arr.length()).map { SourceRef(arr.getJSONObject(it).getString("url"), arr.getJSONObject(it).optString("name")) }
    }

    // check each address is really a song database before saving; returns an error message or null
    private fun saveSources(items: JSONArray): String? {
        val clean = ArrayList<SourceRef>()
        val errors = ArrayList<String>()
        for (i in 0 until minOf(items.length(), 8)) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url").trim().trimEnd('/')
            val name = o.optString("name").replace(Regex("\\s+"), " ").trim().take(30)
            if (url.isEmpty()) continue
            if (!Km.looksLikeAddress(url)) { errors.add("$url: not a web address"); continue }
            if (clean.any { Km.sameLink(it.url) == Km.sameLink(url) }) { errors.add("That link is already in your list."); continue }
            try { Km.search(url, "a", 1) } catch (e: Exception) { errors.add("$url: couldn't reach a song database there"); continue }
            clean.add(SourceRef(url, name))
        }
        if (errors.isNotEmpty()) {
            return errors.joinToString(" / ") + if (errors.any { it.contains("already in your list") }) " Nothing was saved. Remove the repeated link and save again." else " Nothing was saved."
        }
        val arr = JSONArray()
        clean.forEach { arr.put(JSONObject().put("url", it.url).put("name", it.name)) }
        settings.sourcesJson = arr.toString()
        return null
    }

    private fun sourceSearch(session: IHTTPSession): Response {
        val q = (session.parms["q"] ?: "").trim()
        val srcs = sources()
        if (srcs.isEmpty()) return json(Response.Status.OK, JSONObject().put("ok", true).put("results", JSONArray()).put("noSources", true))
        if (q.length < 2) return json(Response.Status.OK, JSONObject().put("ok", true).put("results", JSONArray()))
        val out = JSONArray()
        val seen = HashSet<String>()
        var answered = 0
        srcs.forEachIndexed { i, s ->
            try {
                Km.search(s.url, q, 10).forEach { k ->
                    val r = Km.toRemote(s.url, k)
                    if (seen.add(r.kid)) {
                        out.put(
                            JSONObject().put("kid", r.kid).put("base", r.base).put("title", r.title).put("series", r.series)
                                .put("type", r.type).put("singers", r.singers).put("sizeMb", r.sizeMb)
                                .put("source", if (srcs.size > 1) s.name.ifEmpty { "Database ${i + 1}" } else "")
                                .put("onBox", library.has(r.fileName))
                        )
                    }
                }
                answered++
            } catch (e: Exception) {
                // skip a source that does not answer
            }
        }
        if (answered == 0) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "No song database answered. Check the internet connection."))
        return json(Response.Status.OK, JSONObject().put("ok", true).put("results", out))
    }

    private fun requestRemote(session: IHTTPSession): Response {
        val b0 = body(session)
        val queueIt = b0.optBoolean("queue", true)
        val singer0 = b0.optString("singer").trim().take(24)
        if (queueIt && singer0.isEmpty()) return fail("Please enter your name first.")
        if (queueIt && queue.limitPerPerson > 0 && queue.countFor(singer0) >= queue.limitPerPerson) {
            return fail("You already have ${queue.limitPerPerson} songs waiting. Wait for one to play first.")
        }
        val r: RemoteSong = try {
            if (b0.optString("kind") == "yt") {
                val id = b0.optString("id")
                if (!Regex("^[\\w-]{11}$").matches(id)) return fail("That video is not valid.")
                val title = b0.optString("title").trim().take(150).ifEmpty { "Video" }
                RemoteSong(id, "youtube", title, "", "", b0.optString("channel").take(60), 0, ytFileName(title, id), kind = "yt")
            } else {
                val base = b0.optString("base")
                if (sources().none { it.url == base }) return fail("That song source is not in your list.")
                Km.toRemote(base, Km.details(base, b0.optString("kid")))
            }
        } catch (e: Exception) {
            return fail("Couldn't reach the song source.")
        }
        if (!queueIt) {
            downloader.saveForLater(r)
            return ok()
        }
        val shown = r.fileName.removeSuffix(".mp4").substringBefore("---")
        queue.add(singer0, Song(r.fileName, shown), if (library.has(r.fileName)) null else r)
        events.add("queued", JSONObject().put("singer", singer0).put("title", shown).put("colour", colourFor(singer0)))
        return ok()
    }

    private fun requestRemoteOld(session: IHTTPSession): Response {
        val b = body(session)
        val singer = b.optString("singer").trim().take(24)
        if (singer.isEmpty()) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Please type your name first."))
        val base = b.optString("base")
        if (sources().none { it.url == base }) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "That song source is not in your list."))
        val r = try {
            Km.toRemote(base, Km.details(base, b.optString("kid")))
        } catch (e: Exception) {
            return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Couldn't reach the song database."))
        }
        queue.add(singer, Song(r.fileName, r.fileName.removeSuffix(".mp4")), if (library.has(r.fileName)) null else r)
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun keep(session: IHTTPSession): Response {
        val b = body(session)
        val done = cache.setKept(b.optString("file"), b.optBoolean("kept", true))
        return json(Response.Status.OK, JSONObject().put("ok", done))
    }

    private fun sessionParam(session: IHTTPSession): Int? = session.parms["session"]?.toIntOrNull()

    private fun rankings(session: IHTTPSession): Response {
        val s = sessionParam(session)
        val limit = (session.parms["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 100)
        fun arr(list: List<Ranked>): JSONArray {
            val a = JSONArray()
            list.forEachIndexed { i, r -> a.put(JSONObject().put("rank", i + 1).put("name", r.name).put("plays", r.plays)) }
            return a
        }
        return json(
            Response.Status.OK,
            JSONObject().put("songs", arr(history.topSongs(s, limit))).put("performers", arr(history.topPerformers(s, limit))),
        )
    }

    private fun historyJson(session: IHTTPSession): Response {
        val a = JSONArray()
        history.list(sessionParam(session)).take(500).forEach {
            a.put(
                JSONObject().put("at", it.at).put("singer", it.singer).put("title", it.title).put("file", it.file)
                    .put("kid", it.kid).put("base", it.base).put("status", it.status)
            )
        }
        return json(Response.Status.OK, JSONObject().put("entries", a))
    }

    private fun sessionsJson(): Response {
        val a = JSONArray()
        history.sessionList().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name.ifEmpty { "Session ${it.id}" }).put("named", it.name.isNotEmpty())) }
        return json(Response.Status.OK, JSONObject().put("sessions", a).put("current", history.currentSession().id))
    }

    private fun newSession(session: IHTTPSession): Response {
        val b = body(session)
        history.newSession(b.optString("name"))
        if (b.optBoolean("clearQueue", false)) queue.clear()
        return ok()
    }

    private fun resultJson(r: RemoteSong, sourceName: String): JSONObject {
        val j = downloader.job(r.fileName)
        return JSONObject().put("file", r.fileName).put("kid", r.kid).put("base", r.base).put("kind", r.kind).put("title", r.title).put("series", r.series)
            .put("type", r.type).put("singers", r.singers).put("sizeMb", r.sizeMb).put("seconds", r.seconds).put("thumb", r.thumb)
            .put("tags", JSONArray(r.tags)).put("source", sourceName).put("onBox", library.has(r.fileName))
            .put("state", j?.state ?: "")
    }

    // one page of songs from all sources ("from" = how many to skip in each)
    private fun communitySearch(session: IHTTPSession): Response {
        val q = (session.parms["q"] ?: "").trim()
        val from = (session.parms["from"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val srcs = sources()
        if (srcs.isEmpty()) return json(Response.Status.OK, JSONObject().put("ok", true).put("results", JSONArray()).put("noSources", true))
        if (q.length < 2) return json(Response.Status.OK, JSONObject().put("ok", true).put("results", JSONArray()))
        val out = JSONArray()
        val seen = HashSet<String>()
        var answered = 0
        var more = false
        var nextFrom = from
        srcs.forEachIndexed { i, s ->
            try {
                val page = Km.searchPage(s.url, q, 20, from)
                page.items.forEach { k ->
                    val r = Km.toRemote(s.url, k)
                    if (seen.add(r.kid)) out.put(resultJson(r, if (srcs.size > 1) s.name.ifEmpty { "Database ${i + 1}" } else ""))
                }
                if (from + page.rawCount < page.total) more = true
                nextFrom = maxOf(nextFrom, from + page.rawCount)
                answered++
            } catch (e: Exception) {
                // skip a source that does not answer
            }
        }
        if (answered == 0) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "No song database answered. Check the internet connection."))
        return json(Response.Status.OK, JSONObject().put("ok", true).put("results", out).put("more", more).put("next", nextFrom))
    }

    private fun ytFileName(title: String, id: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]+"), "").trim().take(120).ifEmpty { "video" }
        return "$clean---$id.mp4"
    }

    private fun youtubeSearch(session: IHTTPSession): Response {
        val q0 = (session.parms["q"] ?: "").trim()
        if (q0.length < 2) return json(Response.Status.OK, JSONObject().put("ok", true).put("results", JSONArray()))
        val karaoke = session.parms["karaoke"] != "0"
        val q = if (karaoke && !q0.lowercase().contains("karaoke")) "$q0 karaoke" else q0
        return try {
            val (items, next) = Yt.search(q, session.parms["token"])
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("file", ytFileName(it.title, it.id)).put("title", it.title).put("channel", it.channel).put("seconds", it.seconds)
                        .put("thumb", it.thumb).put("onBox", library.has(ytFileName(it.title, it.id)))
                )
            }
            json(Response.Status.OK, JSONObject().put("ok", true).put("results", arr).put("next", next ?: JSONObject.NULL))
        } catch (e: Exception) {
            json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Couldn't search YouTube right now. Check the internet connection and try again."))
        }
    }

    private fun downloadsJson(): Response {
        val arr = JSONArray()
        downloader.allJobs().sortedBy { it.state != "downloading" }.forEach {
            arr.put(
                JSONObject().put("file", it.file).put("title", it.file.removeSuffix(".mp4")).put("state", it.state).put("percent", it.percent())
                    .put("message", it.message).put("keep", it.keep).put("sizeMb", Math.round(it.size / 1e6))
            )
        }
        return json(Response.Status.OK, JSONObject().put("items", arr))
    }

    private fun deleteSong(session: IHTTPSession): Response {
        val name = body(session).optString("file")
        if (!library.has(name)) return fail("That song is not on the box.")
        val f = java.io.File(library.folderFile(), name)
        if (cache.get(name) != null) cache.remove(name) else f.delete()
        return ok()
    }

    private val boolPrefs = mapOf(
        "disableBgVideo" to false, "disableBgMusic" to false, "hideQr" to false,
        "showClock" to true, "showSessionName" to true, "fairQueue" to true, "deleteAfterPlay" to false, "showQueueNotices" to true, "showPromo" to true,
    )
    private val intPrefs = mapOf(
        "videoVolume" to (0..100 to 100), "bgMusicVolume" to (0..100 to 30), "nextDelay" to (0..60 to 0),
        "limitPerPerson" to (0..50 to 0), "songsPerPage" to (10..200 to 50), "keepMonths" to (1..60 to 3),
        "bgHue" to (0..360 to 135), "bgSat" to (0..300 to 135), "bgBright" to (0..200 to 55),
    )

    private fun defaultPhrases(tier: String): String {
        val arr = JSONObject(context.assets.open("web/score/phrases.json").bufferedReader().use { it.readText() }).getJSONArray(tier)
        return (0 until arr.length()).joinToString("\n") { arr.getString(it) }
    }

    private fun prefsMap(): JSONObject {
        val o = JSONObject()
        boolPrefs.forEach { (k, d) -> o.put(k, settings.bool(k, d)) }
        intPrefs.forEach { (k, d) -> o.put(k, settings.int(k, d.second)) }
        o.put("accent", settings.text("accent", "#c3b94b"))
        o.put("scoreEnabled", settings.scoreEnabled)
        listOf("low", "mid", "high").forEach { o.put("score" + it.replaceFirstChar { c -> c.uppercase() }, settings.text("phrases_$it", defaultPhrases(it))) }
        return o
    }

    private fun savePrefs(session: IHTTPSession): Response {
        val b = body(session)
        boolPrefs.keys.filter { b.has(it) }.forEach { settings.putBool(it, b.optBoolean(it)) }
        intPrefs.filter { b.has(it.key) }.forEach { (k, d) -> settings.putInt(k, b.optInt(k, d.second).coerceIn(d.first.first, d.first.last)) }
        if (b.has("accent")) {
            val a = b.optString("accent")
            if (!Regex("^#[0-9a-fA-F]{6}$").matches(a)) return fail("Pick a colour like #c3b94b.")
            settings.putText("accent", a.lowercase())
        }
        if (b.has("scoreEnabled")) settings.scoreEnabled = b.optBoolean("scoreEnabled", true)
        listOf("low", "mid", "high").forEach {
            val key = "score" + it.replaceFirstChar { c -> c.uppercase() }
            if (b.has(key)) {
                val lines = b.optString(key).split("\n").map { l -> l.trim() }.filter { l -> l.isNotEmpty() }.take(60)
                settings.putText("phrases_$it", lines.joinToString("\n").ifEmpty { defaultPhrases(it) })
            }
        }
        onPrefsChanged()
        return ok()
    }

    private fun fail(msg: String) = json(Response.Status.OK, JSONObject().put("ok", false).put("error", msg))

    private fun addSound(session: IHTTPSession): Response {
        val b = body(session)
        val emoji = b.optString("emoji").trim().take(8).ifEmpty { "\uD83D\uDD0A" }
        val label = b.optString("label").trim().take(24)
        if (label.isEmpty()) return fail("Give the sound a title.")
        val url = b.optString("url").trim()
        if (!Net.isPublic(url)) return fail("That link doesn't look right.")
        val direct = Regex("\\.(mp3|wav|ogg|m4a)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)
        val audioUrl = if (direct) url else {
            if (!java.net.URL(url).host.lowercase().endsWith("myinstants.com")) return fail("Use a myinstants.com page link, or a direct link to an .mp3 file.")
            val html = try { Net.text(url) } catch (e: Exception) { return fail("Couldn't open that link.") }
            val m = Regex("og:audio\"\\s+content=\"([^\"]+)\"").find(html) ?: Regex("content=\"([^\"]+\\.mp3[^\"]*)\"").find(html)
                ?: return fail("Couldn't find a sound on that page.")
            var a = m.groupValues[1].replace("&amp;", "&")
            if (a.startsWith("/")) a = "https://www.myinstants.com$a"
            a
        }
        if (!Net.isPublic(audioUrl)) return fail("That link doesn't look right.")
        val tmp = java.io.File.createTempFile("snd", ".tmp", context.cacheDir)
        return try {
            Net.download(audioUrl, tmp, 1_500_000)
            media.addSound(emoji, label, tmp.readBytes())
            ok()
        } catch (e: Exception) {
            fail("Couldn't download that sound. Check the link.")
        } finally {
            tmp.delete()
        }
    }

    private fun removeSound(session: IHTTPSession): Response =
        if (media.removeSound(body(session).optString("key"))) ok() else fail("That sound can't be removed.")

    private fun addClip(session: IHTTPSession): Response {
        val b = body(session)
        val emoji = b.optString("emoji").trim().take(8).ifEmpty { "\uD83C\uDFAC" }
        val label = b.optString("label").trim().take(24)
        if (label.isEmpty()) return fail("Give the clip a title.")
        val url = b.optString("url").trim()
        if (!Net.isPublic(url)) return fail("That link doesn't look right.")
        val host = java.net.URL(url).host.lowercase()
        if (host.contains("youtube.") || host == "youtu.be" || host.endsWith(".youtu.be")) {
            return fail("YouTube links can't be used on the TV box. Paste a direct link to a short .mp4 or .webm video file instead.")
        }
        val ext = Regex("\\.(mp4|webm|m4v|mov)(\\?.*)?$", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
            ?: return fail("Paste a direct link to a video file ending in .mp4 or .webm.")
        val placement = b.optString("placement").takeIf { it in setOf("full", "corner", "random") } ?: "full"
        val tmp = java.io.File(media.clipsDir(), ".tmp-${System.nanoTime()}")
        return try {
            Net.download(url, tmp, 40_000_000)
            media.addClip(emoji, label, placement, ext) { dest -> tmp.renameTo(dest) }
            ok()
        } catch (e: Exception) {
            fail(if (e.message?.contains("too big") == true) "That video is too big (40 MB at most)." else "Couldn't download that video. Check the link.")
        } finally {
            tmp.delete()
        }
    }

    private fun removeClip(session: IHTTPSession): Response =
        if (media.removeClip(body(session).optString("key"))) ok() else fail("That clip can't be removed.")

    private fun joinQr(): Response {
        val png = qrPng() ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "No network"))
        return newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
    }

    private fun ok(): Response = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun ranged(session: IHTTPSession, mime: String, total: Long, open: (Long) -> java.io.InputStream): Response {
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toLongOrNull() ?: 0L
            val end = minOf(spec.getOrNull(1)?.toLongOrNull() ?: (total - 1), total - 1)
            if (start >= total || end < start) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also {
                    it.addHeader("Content-Range", "bytes */$total")
                }
            }
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, open(start), end - start + 1)
            r.addHeader("Content-Range", "bytes $start-$end/$total")
            r.addHeader("Accept-Ranges", "bytes")
            return r
        }
        val r = newFixedLengthResponse(Response.Status.OK, mime, open(0), total)
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    private fun media(session: IHTTPSession): Response {
        val name = java.net.URLDecoder.decode(session.uri.removePrefix("/media/"), "UTF-8")
        val song = library.find(name)
        if (song == null) {
            // not on the box yet: if it is downloading and enough is in, play it while it grows
            val job = downloader.job(name)
            val part = downloader.partFile(name)
            if (job != null && job.state == "downloading" && part.isFile) {
                return ranged(session, "video/mp4", maxOf(job.size, job.done)) { start ->
                    try {
                        GrowingInput(part, start, job)
                    } catch (e: java.io.FileNotFoundException) {
                        java.io.FileInputStream(java.io.File(library.folderFile(), name)).also { it.channel.position(start) }
                    }
                }
            }
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
        val file = library.fileFor(song) ?: return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        val mime = when (file.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
        return ranged(session, mime, file.length()) { start ->
            java.io.FileInputStream(file).also { it.channel.position(start) }
        }
    }

    private fun vocals(session: IHTTPSession): Response {
        player.setVocals(body(session).optBoolean("on", true))
        return ok()
    }

    private fun key(session: IHTTPSession): Response {
        val b = body(session)
        player.changeKey(if (b.has("delta")) b.optInt("delta") else 0)
        if (b.has("reset")) player.changeKey(-player.key())
        return ok()
    }

    private fun seek(session: IHTTPSession): Response {
        val b = body(session)
        player.seek(if (b.has("to")) b.optDouble("to") else null, if (b.has("by")) b.optDouble("by") else null)
        return ok()
    }

    private fun assetText(path: String): Response {
        val html = context.assets.open(path).bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun assetJson(path: String): Response {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun eventsSince(session: IHTTPSession): Response {
        val since = session.parms["since"]?.toLongOrNull() ?: -1L
        val (list, last) = events.since(since)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return json(Response.Status.OK, JSONObject().put("events", arr).put("last", last))
    }

    private fun findIn(path: String, listKey: String, key: String): JSONObject? {
        val arr = if (listKey == "cheers") media.cheers() else media.overlays()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("key") == key) return arr.getJSONObject(i)
        return null
    }

    private fun tooSoon(b: JSONObject, seconds: Int): Response? {
        val dev = b.optString("dev").take(64).ifEmpty { "unknown" }
        val left = events.cooldownLeft(dev, seconds)
        return if (left > 0) json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Easy there! Try again in ${left}s.")) else null
    }

    private fun cheer(session: IHTTPSession): Response {
        val b = body(session)
        val user = b.optString("user").trim().take(24)
        if (user.isEmpty()) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Please type your name first."))
        val c = findIn("web/cheers.json", "cheers", b.optString("key"))
            ?: return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Unknown sound."))
        if (c.getString("key") == "zawarudo") {
            val left = events.timeStopLeft(90)
            if (left > 0) return fail("Time is still recovering... ${left}s")
        } else tooSoon(b, 3)?.let { return it }
        events.add("cheer", JSONObject().put("key", c.getString("key")).put("emoji", c.getString("emoji")).put("user", user).put("custom", c.optBoolean("user", false)))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun overlay(session: IHTTPSession): Response {
        val b = body(session)
        val o = findIn("web/overlays.json", "overlays", b.optString("key"))
            ?: return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Unknown clip."))
        events.overlayBlocked(b.optString("dev").take(64).ifEmpty { "unknown" }, 10, 8)?.let { return fail(it) }
        events.add("overlay", JSONObject().put("key", o.getString("key")).put("placement", o.optString("placement", "full")).put("keyed", o.optBoolean("keyed", true)).put("ext", o.optString("ext", "webm")))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private val colours = setOf("white", "red", "pink", "orange", "yellow", "green", "cyan", "blue", "purple", "black")
    private val sizes = setOf("small", "medium", "big")

    private fun message(session: IHTTPSession): Response {
        val b = body(session)
        val text = b.optString("text").trim().take(80)
        if (text.isEmpty()) return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Type a comment first."))
        val colour = b.optString("color").takeIf { it in colours } ?: "white"
        val size = b.optString("size").takeIf { it in sizes } ?: "medium"
        events.add("message", JSONObject().put("text", text).put("color", colour).put("size", size))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun songs(session: IHTTPSession): Response {
        val q = session.parms["q"] ?: ""
        val per = session.parms["per"]?.toIntOrNull()?.coerceIn(5, 500)
        val page = (session.parms["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val all = library.search(q)
        val shown = if (per == null) all.take(300) else all.drop(page * per).take(per)
        val arr = JSONArray()
        shown.forEach {
            val c = cache.get(it.file)
            arr.put(JSONObject().put("file", it.file).put("title", it.title).put("cached", c != null).put("kept", c?.kept ?: false))
        }
        return json(Response.Status.OK, JSONObject().put("songs", arr).put("total", all.size).put("page", page).put("per", per ?: 300))
    }

    // food plug, Singapore only
    private fun promo(): Response {
        val show = inSingapore() && settings.bool("showPromo", true)
        return json(Response.Status.OK, JSONObject().put("show", show).put("url", "https://doughbaybee.sg/")
            .put("text", "Having a karaoke party? Get your party food sorted at DoughBaybee.sg"))
    }

    private val palette = listOf("#ff6b6b", "#ffa94d", "#ffe066", "#69db7c", "#38d9a9", "#4dabf7", "#9775fa", "#f783ac", "#a9e34b", "#66d9e8")

    private fun colourFor(singer: String): String {
        if (singer.isEmpty()) return "#ffffff"
        val saved = try { JSONObject(settings.text("singerColours", "{}")).optString(singer.lowercase(), "") } catch (e: Exception) { "" }
        if (saved.isNotEmpty()) return saved
        return palette[Math.floorMod(singer.lowercase().hashCode(), palette.size)]
    }

    private fun setColour(session: IHTTPSession): Response {
        val b = body(session)
        val name = b.optString("name").trim().take(24)
        val colour = b.optString("colour")
        if (name.isEmpty()) return fail("Please enter your name first.")
        if (!Regex("^#[0-9a-fA-F]{6}$").matches(colour)) return fail("Pick a colour like #4dabf7.")
        val all = try { JSONObject(settings.text("singerColours", "{}")) } catch (e: Exception) { JSONObject() }
        all.put(name.lowercase(), colour.lowercase())
        settings.putText("singerColours", all.toString())
        return ok()
    }

    private fun queueJson(): Response {
        val arr = JSONArray()
        queue.snapshot().forEach {
            val j = it.remote?.let { r -> downloader.job(r.fileName) }
            val state = when {
                library.has(it.song.file) || j?.canPlay() == true -> "ready"
                j == null -> "waiting"
                else -> j.state
            }
            arr.put(JSONObject().put("id", it.id).put("singer", it.singer).put("colour", colourFor(it.singer)).put("title", it.song.title).put("file", it.song.file)
                .put("state", state).put("percent", j?.percent() ?: 0))
        }
        val cur = history.currentSession()
        return json(
            Response.Status.OK,
            JSONObject().put("now", nowPlaying() ?: JSONObject.NULL).put("queue", arr)
                .put("nowSinger", player.singer).put("nowTitle", player.songTitle).put("nowColour", colourFor(player.singer))
                .put("session", JSONObject().put("id", cur.id).put("name", cur.name)),
        )
    }

    private fun request(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val singer = (session.parms["singer"] ?: "").trim().take(24)
        val file = session.parms["file"] ?: ""
        if (singer.isEmpty()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Please type your name first."))
        if (queue.limitPerPerson > 0 && queue.countFor(singer) >= queue.limitPerPerson) {
            return json(Response.Status.OK, JSONObject().put("ok", false).put("error", "You already have ${queue.limitPerPerson} songs waiting. Wait for one to play first."))
        }
        val song = library.find(file)
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "That song is not available."))
        queue.add(singer, song)
        events.add("queued", JSONObject().put("singer", singer).put("title", song.title).put("colour", colourFor(singer)))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
}
