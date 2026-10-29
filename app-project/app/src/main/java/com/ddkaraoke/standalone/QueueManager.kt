package com.ddkaraoke.standalone

data class Song(val file: String, val title: String)

data class QueueItem(val id: Long, val singer: String, val song: Song, val round: Int, val remote: RemoteSong? = null)

// Song queue with fair turns: everyone's first song, then everyone's second, and so on.
// First come first served inside a round.
class QueueManager {
    private val items = mutableListOf<QueueItem>()
    private var nextId = 1L

    // max waiting songs per person, 0 = no limit
    @Volatile var limitPerPerson = 0

    // fair turns on/off
    @Volatile var fair = true

    @Synchronized
    fun countFor(singer: String): Int = items.count { it.singer.equals(singer, ignoreCase = true) }

    @Synchronized
    fun add(singer: String, song: Song, remote: RemoteSong? = null): QueueItem {
        val round = items.count { it.singer.equals(singer, ignoreCase = true) }
        val insertAt = if (fair) items.indexOfLast { it.round <= round } + 1 else items.size
        val item = QueueItem(nextId++, singer, song, round, remote)
        items.add(insertAt, item)
        return item
    }

    @Synchronized
    fun peek(): QueueItem? = items.firstOrNull()

    // move a waiting song: top (play next), up, down, bottom
    @Synchronized
    fun move(id: Long, action: String): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        val item = items.removeAt(i)
        val to = when (action) {
            "top" -> 0
            "up" -> (i - 1).coerceAtLeast(0)
            "down" -> (i + 1).coerceAtMost(items.size)
            "bottom" -> items.size
            else -> { items.add(i, item); return false }
        }
        items.add(to, item)
        return true
    }

    // drag and drop: move a song to a given position
    @Synchronized
    fun moveTo(id: Long, index: Int): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        val item = items.removeAt(i)
        items.add(index.coerceIn(0, items.size), item)
        return true
    }

    // pop the front of the queue, it is now playing
    @Synchronized
    fun takeNext(): QueueItem? = if (items.isEmpty()) null else items.removeAt(0)

    @Synchronized
    fun remove(id: Long): Boolean = items.removeAll { it.id == id }

    @Synchronized
    fun snapshot(): List<QueueItem> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}
