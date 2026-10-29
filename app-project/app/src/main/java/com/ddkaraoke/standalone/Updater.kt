package com.ddkaraoke.standalone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Finds the newest GitHub release and installs it over this app.
class Updater(private val context: Context) {
    private val repo = "kingarcher-cos/ddkaraoke"
    private val action = "com.ddkaraoke.standalone.INSTALL_RESULT"

    @Volatile var latest = ""
        private set
    @Volatile private var assetUrl = ""
    @Volatile var busy = ""          // "", "checking", "downloading", "installing"
        private set
    @Volatile var percent = 0
        private set
    @Volatile var error = ""
        private set

    @Suppress("DEPRECATION")
    val current: String = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()

    fun newer(): Boolean {
        if (latest.isEmpty()) return false
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // blocking, call off the main thread
    fun check(): Boolean {
        if (busy.isNotEmpty() && busy != "checking") return newer()
        busy = "checking"; error = ""
        try {
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val assets = j.optJSONArray("assets")
                var url = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                }
                latest = j.optString("tag_name")
                assetUrl = url
            } finally { c.disconnect() }
        } catch (e: Exception) {
            error = "Could not reach GitHub. Check the internet connection and try again."
        }
        busy = ""
        return newer()
    }

    fun state(): JSONObject = JSONObject().put("current", current).put("latest", latest).put("available", newer() && assetUrl.isNotEmpty())
        .put("busy", busy).put("percent", percent).put("error", error)

    // blocking: download the file, then hand it to the system installer
    fun update() {
        if (busy == "downloading" || !newer() || !assetUrl.startsWith("https://github.com/$repo/")) return
        busy = "downloading"; percent = 0; error = ""
        try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val apk = File(dir, "DDKaraoke.apk")
            val c = URL(assetUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            try {
                val total = c.contentLengthLong
                c.inputStream.use { i -> apk.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) percent = (done * 100 / total).toInt()
                    }
                } }
            } finally { c.disconnect() }
            if (!sameSigner(apk)) {
                error = "This copy was installed from a different source, so it can't be updated in place. Download the new version from GitHub and install it by hand."
                busy = ""; return
            }
            busy = "installing"
            install(apk)
        } catch (e: Exception) {
            error = "The download did not finish. Try again."
            busy = ""
        }
    }

    // Android refuses an update signed with another key, so say so before it gets that far
    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean = try {
        val pm = context.packageManager
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        val theirs = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        theirs != null && mine != null && mine.isNotEmpty() && theirs.isNotEmpty() && mine[0] == theirs[0]
    } catch (e: Exception) { true }

    private fun install(apk: File) {
        val result = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val ask = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (ask != null) c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    else -> {
                        error = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The install did not go through."
                        busy = ""
                        try { c.unregisterReceiver(this) } catch (e: Exception) {}
                    }
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(result, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(result, filter)

        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("DDKaraoke.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val done = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), flags)
            s.commit(done.intentSender)
        }
    }
}
