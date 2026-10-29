package com.ddkaraoke.standalone

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlayEntry(
    val at: Long,
    val singer: String,
    val title: String,
    val file: String,
    val kid: String,
    val base: String,
    val status: String,      // played / skipped
    val session: Int,
)

data class SessionInfo(val id: Int, val name: String, val at: Long)

data class Ranked(val name: String, val plays: Int)

// Play history, grouped by session (one per party). Saved to a file.
class HistoryStore(private val file: File?) {
    private val entries = ArrayList<PlayEntry>()
    private val sessions = ArrayList<SessionInfo>()
    private var current = 1

    init {
        load()
        if (sessions.isEmpty()) sessions.add(SessionInfo(1, "", System.currentTimeMillis()))
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            current = root.optInt("current", 1)
            root.optJSONArray("sessions")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { sessions.add(SessionInfo(it.getInt("id"), it.optString("name"), it.optLong("at"))) }
            }
            root.optJSONArray("entries")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    entries.add(
                        PlayEntry(it.optLong("at"), it.optString("singer"), it.optString("title"), it.optString("file"),
                            it.optString("kid"), it.optString("base"), it.optString("status"), it.optInt("session", 1))
                    )
                }
            }
        } catch (e: Exception) {
            // unreadable file: start empty
        }
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject().put("current", current)
        val s = JSONArray(); sessions.forEach { s.put(JSONObject().put("id", it.id).put("name", it.name).put("at", it.at)) }
        val e = JSONArray()
        entries.forEach {
            e.put(JSONObject().put("at", it.at).put("singer", it.singer).put("title", it.title).put("file", it.file)
                .put("kid", it.kid).put("base", it.base).put("status", it.status).put("session", it.session))
        }
        root.put("sessions", s).put("entries", e)
        try { f.writeText(root.toString()) } catch (ex: Exception) { /* not worth crashing for */ }
    }

    @Synchronized
    fun record(singer: String, title: String, file: String, remote: RemoteSong?, skipped: Boolean, now: Long = System.currentTimeMillis()) {
        entries.add(PlayEntry(now, singer, title, file, remote?.kid ?: "", remote?.base ?: "", if (skipped) "skipped" else "played", current))
        if (entries.size > 5000) entries.removeAt(0)
        save()
    }

    @Synchronized
    fun newSession(name: String, now: Long = System.currentTimeMillis()): SessionInfo {
        val s = SessionInfo((sessions.maxOfOrNull { it.id } ?: 0) + 1, name.trim().take(30), now)
        sessions.add(s)
        current = s.id
        save()
        return s
    }

    @Synchronized
    fun currentSession(): SessionInfo = sessions.first { it.id == current }

    @Synchronized
    fun sessionList(): List<SessionInfo> = sessions.toList()

    // newest first; session null = all
    @Synchronized
    fun list(session: Int?): List<PlayEntry> = entries.filter { session == null || it.session == session }.sortedByDescending { it.at }

    @Synchronized
    fun topSongs(session: Int?, limit: Int): List<Ranked> =
        rank(list(session).filter { it.status == "played" }.map { it.title }, limit)

    @Synchronized
    fun topPerformers(session: Int?, limit: Int): List<Ranked> =
        rank(list(session).filter { it.status == "played" }.map { it.singer }, limit)

    private fun rank(names: List<String>, limit: Int): List<Ranked> =
        names.groupingBy { it }.eachCount().map { Ranked(it.key, it.value) }
            .sortedWith(compareByDescending<Ranked> { it.plays }.thenBy { it.name.lowercase() })
            .take(limit)
}
