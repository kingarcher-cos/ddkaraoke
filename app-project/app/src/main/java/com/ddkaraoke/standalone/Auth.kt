package com.ddkaraoke.standalone

import android.content.Context
import java.security.SecureRandom

// Saved settings: host PIN, songs folder, cache limit.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ddkaraoke", Context.MODE_PRIVATE)

    var pin: String
        get() = prefs.getString("pin", null) ?: String.format("%04d", SecureRandom().nextInt(10000)).also { pin = it }
        set(v) { prefs.edit().putString("pin", v).apply() }

    // songs folder path, null = built-in
    var folder: String?
        get() = prefs.getString("folder", null)
        set(v) { prefs.edit().putString("folder", v).apply() }

    fun bool(key: String, default: Boolean) = prefs.getBoolean("o_$key", default)
    fun int(key: String, default: Int) = prefs.getInt("o_$key", default)
    fun text(key: String, default: String) = prefs.getString("o_$key", default) ?: default
    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean("o_$key", v).apply()
    fun putInt(key: String, v: Int) = prefs.edit().putInt("o_$key", v).apply()
    fun putText(key: String, v: String) = prefs.edit().putString("o_$key", v).apply()

    // version code the host accepted the popup on
    var acceptedVersion: Int
        get() = prefs.getInt("acceptedVersion", 0)
        set(v) { prefs.edit().putInt("acceptedVersion", v).apply() }

    var scoreEnabled: Boolean
        get() = prefs.getBoolean("score", true)
        set(v) { prefs.edit().putBoolean("score", v).apply() }

    // song databases as JSON: [{"url":..., "name":...}]
    var sourcesJson: String
        get() = prefs.getString("sources", "[]") ?: "[]"
        set(v) { prefs.edit().putString("sources", v).apply() }

    var cacheGb: Int
        get() = prefs.getInt("cacheGb", 1)
        set(v) { prefs.edit().putInt("cacheGb", v.coerceIn(1, 500)).apply() }
}

// Host check. The TV itself is always host. A phone becomes host with the PIN,
// and 5 wrong tries lock the PIN box for a minute.
class Auth(private val currentPin: () -> String) {
    private val tokens = HashMap<String, Long>()
    private var failures = 0
    private var lockedUntil = 0L
    private var lastHostSeen = 0L

    @Synchronized
    fun login(pin: String): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return null to "Too many tries. Please wait a minute."
        if (pin != currentPin()) {
            failures++
            if (failures >= 5) { lockedUntil = now + 60_000; failures = 0 }
            return null to "Wrong PIN."
        }
        failures = 0
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens[token] = now + 12 * 3600_000L
        return token to null
    }

    @Synchronized
    fun isHost(token: String?, remoteIp: String?): Boolean {
        if (remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "0:0:0:0:0:0:0:1") return true
        val exp = tokens[token ?: return false] ?: return false
        if (exp < System.currentTimeMillis()) { tokens.remove(token); return false }
        lastHostSeen = System.currentTimeMillis()
        return true
    }

    // phone logs out
    @Synchronized
    fun logout(token: String?) {
        if (token != null) tokens.remove(token)
        if (tokens.isEmpty()) lastHostSeen = 0L
    }

    // true if a phone entered the PIN since [since] and is still active
    @Synchronized
    fun hostActive(since: Long, withinMs: Long = 30 * 60_000L): Boolean {
        val now = System.currentTimeMillis()
        return lastHostSeen >= since && now - lastHostSeen <= withinMs
    }
}
