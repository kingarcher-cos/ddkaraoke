package com.ddkaraoke.standalone

import org.json.JSONObject
import java.io.File

// A song the app downloaded. Files copied in by hand are left alone.
data class CacheEntry(val file: String, val kid: String, val size: Long, val at: Long, val kept: Boolean)

object CachePolicy {
    // Songs to delete to free [need] bytes: oldest first, skipping kept, queued or playing ones.
    // null if there is not enough to free.
    fun victims(
        entries: List<CacheEntry>,
        protect: Set<String>,
        need: Long,
        limit: Long,
        free: Long,
        minFree: Long,
    ): List<CacheEntry>? {
        var used = entries.sumOf { it.size }
        var avail = free
        val out = ArrayList<CacheEntry>()
        fun ok() = used + need <= limit && avail - need >= minFree
        if (ok()) return out
        for (e in entries.filter { !it.kept && it.file !in protect }.sortedBy { it.at }) {
            out.add(e)
            used -= e.size
            avail += e.size
            if (ok()) return out
        }
        return null
    }
}

// Index of downloaded songs, stored beside them.
class CacheManifest(private val library: Library) {
    private val name = ".ddkaraoke-cache.json"

    private fun fileObj() = File(library.folderFile(), name)

    @Synchronized
    fun entries(): List<CacheEntry> {
        val f = fileObj()
        if (!f.isFile) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            root.keys().asSequence().mapNotNull { key ->
                val o = root.optJSONObject(key) ?: return@mapNotNull null
                if (!File(library.folderFile(), key).isFile) return@mapNotNull null
                CacheEntry(key, o.optString("kid"), o.optLong("size"), o.optLong("at"), o.optBoolean("kept"))
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(list: List<CacheEntry>) {
        val root = JSONObject()
        list.forEach { root.put(it.file, JSONObject().put("kid", it.kid).put("size", it.size).put("at", it.at).put("kept", it.kept)) }
        fileObj().writeText(root.toString())
    }

    @Synchronized
    fun add(e: CacheEntry) = save(entries().filter { it.file != e.file } + e)

    @Synchronized
    fun get(file: String): CacheEntry? = entries().firstOrNull { it.file == file }

    @Synchronized
    fun setKept(file: String, kept: Boolean): Boolean {
        val list = entries()
        if (list.none { it.file == file }) return false
        save(list.map { if (it.file == file) it.copy(kept = kept) else it })
        return true
    }

    @Synchronized
    fun remove(file: String) {
        File(library.folderFile(), file).delete()
        save(entries().filter { it.file != file })
    }

    // after playing: delete unless kept
    @Synchronized
    fun afterPlay(file: String) {
        val e = get(file) ?: return
        if (!e.kept) remove(file)
    }
}
