package com.ddkaraoke.standalone

import org.json.JSONObject

// What the TV page should be doing: song file, vocals, key, one-off seek commands.
// The page polls it about twice a second.
class PlayerState {
    var file: String = ""
        private set
    var title: String = ""
        private set
    private var songId = 0L
    private var vocalsId = 0L
    private var vocalsOn = true
    private var keyId = 0L
    private var keySemitones = 0
    private var seekId = 0L
    private var seekTo: Double? = null
    private var seekBy: Double? = null
    private var skipScoreId = 0L
    private var positionSec = 0.0
    private var durationSec = 0.0
    private var mono = false

    @Synchronized
    fun setPosition(t: Double, d: Double) { positionSec = t; durationSec = d }

    @Synchronized
    fun setMono(m: Boolean) { mono = m }

    @Synchronized
    fun skipScore() { skipScoreId++ }

    var singer: String = ""
        private set
    var songTitle: String = ""
        private set

    @Synchronized
    fun start(file: String, title: String, singer: String = "", songTitle: String = title) {
        this.file = file
        this.title = title
        this.singer = singer
        this.songTitle = songTitle
        songId++
        // key resets on each new song
        keySemitones = 0
        keyId++
        positionSec = 0.0
        durationSec = 0.0
        mono = false
    }

    @Synchronized
    fun stop() {
        file = ""
        title = ""
        singer = ""
        songTitle = ""
        songId++
    }

    @Synchronized
    fun isPlaying() = file.isNotEmpty()

    @Synchronized
    fun setVocals(on: Boolean) { vocalsOn = on; vocalsId++ }

    private var styleId = 0L
    private var style = "classic"

    // classic = home server method, gentle = keeps bass, drums and wide parts
    @Synchronized
    fun setStyle(s: String) { style = if (s == "gentle") "gentle" else "classic"; styleId++ }

    @Synchronized
    fun vocals() = vocalsOn

    @Synchronized
    fun changeKey(delta: Int) {
        keySemitones = (keySemitones + delta).coerceIn(-6, 6)
        keyId++
    }

    @Synchronized
    fun key() = keySemitones

    @Synchronized
    fun seek(to: Double?, by: Double?) {
        seekTo = to; seekBy = by; seekId++
    }

    @Synchronized
    fun toJson(): JSONObject {
        val seek = JSONObject().put("id", seekId)
        seekTo?.let { seek.put("to", it) }
        seekBy?.let { seek.put("by", it) }
        return JSONObject()
            .put("file", file)
            .put("song", songId)
            .put("vocals", JSONObject().put("id", vocalsId).put("on", vocalsOn))
            .put("style", JSONObject().put("id", styleId).put("mode", style))
            .put("key", JSONObject().put("id", keyId).put("semitones", keySemitones))
            .put("seek", seek)
            .put("skipScore", skipScoreId)
            .put("position", positionSec)
            .put("duration", durationSec)
            .put("mono", mono)
    }
}
