package com.ddkaraoke.standalone

import org.json.JSONObject

// Things guests send to the TV: cheers, overlay clips, scrolling comments.
// The TV polls for anything newer than the last id it saw.
class Events {
    private val items = ArrayList<Pair<Long, JSONObject>>()
    private var lastId = 0L
    private val lastSent = HashMap<String, Long>()
    private var lastTimeStop = 0L
    private var overlayBusyUntil = 0L

    @Synchronized
    fun add(type: String, fields: JSONObject): Long {
        lastId++
        fields.put("id", lastId).put("type", type)
        items.add(lastId to fields)
        if (items.size > 100) items.removeAt(0)
        return lastId
    }

    @Synchronized
    fun since(id: Long): Pair<List<JSONObject>, Long> =
        items.filter { it.first > id }.map { it.second } to lastId

    // ZA WARUDO affects everyone, so it has a global cooldown. Returns seconds left, 0 if free.
    @Synchronized
    fun timeStopLeft(seconds: Int): Int {
        val now = System.currentTimeMillis()
        val left = (lastTimeStop + seconds * 1000L - now + 999) / 1000
        if (left > 0) return left.toInt()
        lastTimeStop = now
        return 0
    }

    // One overlay clip at a time, and one per phone every [seconds]. Returns an error message or null.
    @Synchronized
    fun overlayBlocked(device: String, seconds: Int, clipSeconds: Int): String? {
        val now = System.currentTimeMillis()
        if (now < overlayBusyUntil) return "Another clip is playing, try again in ${(overlayBusyUntil - now) / 1000 + 1}s."
        val last = lastSent["clip:$device"] ?: 0L
        val left = (last + seconds * 1000L - now + 999) / 1000
        if (left > 0) return "Easy there! ${left}s"
        lastSent["clip:$device"] = now
        overlayBusyUntil = now + (clipSeconds + 1) * 1000L
        return null
    }

    // one send per device every [seconds]; returns seconds left, 0 if free
    @Synchronized
    fun cooldownLeft(device: String, seconds: Int): Int {
        val now = System.currentTimeMillis()
        val last = lastSent[device] ?: 0L
        val left = (last + seconds * 1000L - now + 999) / 1000
        if (left > 0) return left.toInt()
        lastSent[device] = now
        return 0
    }
}
