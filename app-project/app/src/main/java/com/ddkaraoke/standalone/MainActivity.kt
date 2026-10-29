package com.ddkaraoke.standalone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val kofiUrl = "https://ko-fi.com/kingarcher"
    private val foodUrl = "https://doughbaybee.sg/"
    private val port = 8080

    private val queue = QueueManager()
    private val events = Events()
    private val player = PlayerState()
    private lateinit var settings: Settings
    private lateinit var auth: Auth
    private lateinit var cache: CacheManifest
    private lateinit var history: HistoryStore
    private lateinit var media: MediaLists
    private var currentItem: QueueItem? = null
    private lateinit var downloader: Downloader
    private var note: String? = null
    private var nextAllowedAt = 0L
    @Volatile private var accepted = false
    private lateinit var consentLayer: View
    private var joinBox: View? = null
    private lateinit var promoBox: View
    private var singapore = false
    private var noteUntil = 0L
    private lateinit var library: Library
    private var server: GuestServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var tv: WebView? = null
    private var settingsPage: WebView? = null
    private var menuPage: WebView? = null
    private lateinit var updater: Updater
    private lateinit var menuButton: TextView
    private lateinit var idleLayer: View
    private lateinit var upNext: TextView
    private lateinit var joinText: TextView
    private lateinit var joinQr: ImageView
    private lateinit var helpText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var hostNote: TextView
    private lateinit var engineNote: TextView
    private var oldEngine = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings(this)
        accepted = settings.acceptedVersion == appVersion()
        singapore = false   // hidden until the country check answers
        findCountry()
        auth = Auth { settings.pin }
        val allowed = getExternalFilesDirs("songs").filterNotNull().map { it.absolutePath }
        val chosen = settings.folder?.takeIf { it in allowed }   // unplugged drive falls back to built-in
        library = Library(java.io.File(chosen ?: getExternalFilesDir("songs")?.absolutePath ?: filesDir.absolutePath))
        cache = CacheManifest(library)
        media = MediaLists(this)
        updater = Updater(this)
        history = HistoryStore(java.io.File(filesDir, "history.json"))
        downloader = Downloader(library, cache, { settings.cacheGb }) {
            queue.snapshot().map { it.song.file }.toSet() + player.file
        }
        buildScreen()
        startServer()
        tv?.loadUrl("http://127.0.0.1:$port/tv.html")
        handler.post(ticker)
        applyPrefs()
        settingsButton.post { settingsButton.requestFocus() }
        handler.postDelayed({ lookForUpdate() }, 20000)
    }

    // Home button: silence everything. Coming back resumes.
    override fun onStop() {
        tv?.evaluateJavascript("window.__away && window.__away(true)", null)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        tv?.evaluateJavascript("window.__away && window.__away(false)", null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        server?.stop()
        super.onDestroy()
    }

    // screen

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // the whole TV picture is one web page
        val page = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        tv = page
        // old web engines break the screens, so warn
        val chrome = Regex("Chrome/(\\d+)").find(page.settings.userAgentString ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 999
        oldEngine = chrome < 88
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // overlay shown while nobody is singing
        val idle = FrameLayout(this)
        idleLayer = idle
        root.addView(idle, FrameLayout.LayoutParams(-1, -1))

        // top right: Settings button, focused on start so the remote can reach it
        settingsButton = text(14f, Color.WHITE, Typeface.BOLD).apply {
            text = "Settings"
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(140, 0, 0, 0)); cornerRadius = dp(20).toFloat() }
            isFocusable = true
            isClickable = true
            setOnClickListener { openSettings() }
            setOnFocusChangeListener { v, focused -> v.scaleX = if (focused) 1.12f else 1f; v.scaleY = if (focused) 1.12f else 1f }
        }
        // top middle: reminder until a phone unlocks host mode
        hostNote = text(12f, Color.WHITE, Typeface.NORMAL).apply {
            text = "No host phone connected yet. Press Settings to see the Host PIN, then enter it on a phone (Settings > unlock)."
            gravity = Gravity.CENTER
            alpha = 0.85f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(110, 0, 0, 0)); cornerRadius = dp(14).toFloat() }
        }
        idle.addView(hostNote, FrameLayout.LayoutParams(dp(520), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        engineNote = text(12f, Color.WHITE, Typeface.NORMAL).apply {
            text = "This device's web engine is out of date. Update Android System WebView in the Play Store, or some screens may look broken."
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(190, 150, 30, 30)); cornerRadius = dp(14).toFloat() }
            visibility = if (oldEngine) View.VISIBLE else View.GONE
        }
        idle.addView(engineNote, FrameLayout.LayoutParams(dp(520), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(78) })
        idle.addView(settingsButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(24), dp(32), 0) })

        // bottom middle: join QR. Text above it grows upward so the logo stays put
        val join = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        upNext = text(14f, Color.WHITE, Typeface.NORMAL).apply { gravity = Gravity.CENTER; alpha = 0.9f }
        join.addView(upNext, LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        helpText = text(13f, Color.WHITE, Typeface.NORMAL).apply { gravity = Gravity.CENTER; visibility = View.GONE; alpha = 0.9f }
        join.addView(helpText, LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        val code = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }   // hideQr hides this part
        joinQr = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        code.addView(joinQr, LinearLayout.LayoutParams(dp(124), dp(124)))
        joinText = text(15f, Color.WHITE, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        code.addView(joinText, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
        join.addView(code)
        // clock is below, leave room
        idle.addView(join, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(52)) })
        joinBox = code

        // bottom left: food QR (Singapore only, host can turn it off)
        val promo = cornerCode(BitmapFactory.decodeResource(resources, R.drawable.doughbay_qr), "Having a karaoke party?\nGet your party food sorted at DoughBaybee.sg") { openLink(foodUrl) }
        promo.visibility = View.GONE
        promoBox = promo
        idle.addView(promo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(32), 0, 0, dp(24)) })

        // bottom right: Ko-fi QR, same size as the left one
        val kofi = cornerCode(BitmapFactory.decodeResource(resources, R.drawable.kofi_qr), "Enjoying the party?\nBuy us a coffee ☕\nTotally optional, thank you!") { openLink(kofiUrl) }
        idle.addView(kofi, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(32), dp(24)) })

        // settings screen: same page as on the phone, opened with OK on the remote
        val sp = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#222826"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
        }
        settingsPage = sp
        root.addView(sp, FrameLayout.LayoutParams(-1, -1))

        // popup menu: the phone page without Settings, slides in from the left
        val mp = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#222826"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
        }
        menuPage = mp
        root.addView(mp, FrameLayout.LayoutParams(minOf(dp(460), resources.displayMetrics.widthPixels), -1, Gravity.START))

        // top left: small hamburger, faint so it stays out of the way during songs
        val burger = text(22f, Color.WHITE, Typeface.BOLD).apply {
            text = "☰"
            gravity = Gravity.CENTER
            alpha = 0.45f
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.argb(90, 0, 0, 0)); cornerRadius = dp(10).toFloat() }
            isClickable = true
            setOnClickListener { if (menuOpen()) closeMenu() else openMenu() }
            setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.45f }
        }
        menuButton = burger
        root.addView(burger, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        // first run and after updates: host has to accept before anything else
        val consent = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#F2141417")); isClickable = true }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(28), dp(40), dp(56)) }
        box.addView(text(26f, Color.WHITE, Typeface.BOLD).apply { text = "Before you start" },
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        val scroll = ScrollView(this).apply { isFocusable = false; isVerticalScrollBarEnabled = true }
        scroll.addView(text(17f, Color.WHITE, Typeface.NORMAL).apply {
            text = "This app does not come with any karaoke songs or videos. Content comes from third-party sources the host chooses, " +
                "such as YouTube or song databases. All content rights remain with their respective owners. The host is responsible " +
                "for the sources they choose and how the content is used, including following each source's terms and applicable " +
                "copyright laws. Anything downloaded stays on the host's own device.\n\n" +
                "The app is provided as is. Content availability and functionality may change."
            setLineSpacing(0f, 1.2f)
            setShadowLayer(0f, 0f, 0f, 0)
        })
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val agree = Button(this).apply {
            text = "I understand"
            textSize = 20f
            isAllCaps = false
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#C3B94B")); cornerRadius = dp(10).toFloat() }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, focused -> v.scaleX = if (focused) 1.06f else 1f; v.scaleY = if (focused) 1.06f else 1f }
            setOnClickListener { accept() }
        }
        box.addView(agree, LinearLayout.LayoutParams(dp(360), dp(64)).apply { topMargin = dp(18); gravity = Gravity.CENTER_HORIZONTAL })
        consent.addView(box, FrameLayout.LayoutParams(minOf(dp(920), resources.displayMetrics.widthPixels - dp(24)), -1, Gravity.CENTER))
        consentLayer = consent
        root.addView(consent, FrameLayout.LayoutParams(-1, -1))
        if (accepted) consent.visibility = View.GONE else { idle.visibility = View.GONE; agree.post { agree.requestFocus() } }

        setContentView(root)
    }

    private fun text(size: Float, color: Int, style: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
        setShadowLayer(4f, 1f, 1f, Color.argb(160, 0, 0, 0))
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // no browser on this box, the QR still works from a phone
        }
    }

    // guest page

    private fun startServer() {
        server = GuestServer(
            this, port, library, queue, events, player, settings, cache, downloader, auth, history, media, updater,
            qrPng = { localIp()?.let { ip ->
                val out = java.io.ByteArrayOutputStream()
                qr("http://$ip:$port").compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            } },
            onSongEnded = { skipped -> handler.post { finishSong(skipped) } },
            onQuit = { handler.post { finishAffinity() } },
            onPrefsChanged = { handler.post { applyPrefs() } },
            onCloseSettings = { handler.post { closeSettings() } },
            onUpdate = { handler.post { startUpdate() } },
            isAccepted = { accepted },
            inSingapore = { singapore },
        ) { if (player.isPlaying()) player.title else null }.also {
            try { it.start() } catch (e: Exception) { server = null }
        }
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    // crop the white border so QR images from different sources draw at the same size
    private fun trimmed(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            if (Color.red(c) + Color.green(c) + Color.blue(c) < 600) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        }
        return if (r < l || b < t) src else Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }

    // QR with a caption, used for the two corners
    // only the QR picture is clickable, not the text under it
    private fun cornerCode(code: Bitmap, caption: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply {
            setImageBitmap(trimmed(code)); setBackgroundColor(Color.WHITE); setPadding(dp(6), dp(6), dp(6), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused -> v.scaleX = if (focused) 1.1f else 1f; v.scaleY = if (focused) 1.1f else 1f }
        }, LinearLayout.LayoutParams(dp(96), dp(96)))
        box.addView(text(12f, Color.WHITE, Typeface.NORMAL).apply { gravity = Gravity.CENTER; text = caption },
            LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return box
    }

    private fun qr(content: String): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) {
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    // playing

    private var lastIp: String? = "unset"

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        if (!accepted) return
        val ip = localIp()
        if (ip != lastIp) {
            lastIp = ip
            if (ip != null && server != null) {
                val url = "http://$ip:$port"
                joinQr.setImageBitmap(qr(url))
                joinQr.visibility = View.VISIBLE
                joinText.text = "Scan to request songs\n$url"
                helpText.visibility = View.GONE
            } else {
                joinQr.visibility = View.INVISIBLE
                joinText.text = ""
                helpText.text = "Wi-Fi help: connect this TV to your Wi-Fi or a network cable,\n" +
                    "then have phones join the same Wi-Fi to request songs."
                helpText.visibility = View.VISIBLE
            }
        }

        // only the next few songs get downloaded
        downloader.plan(queue.snapshot().take(3).mapNotNull { it.remote })

        var preparing: String? = null
        if (!player.isPlaying()) {
            val head = queue.peek()
            if (head != null) {
                val jobNow = head.remote?.let { downloader.job(it.fileName) }
                if (library.has(head.song.file) || jobNow?.canPlay() == true) {
                    // ok to start now (a song still downloading plays as it arrives)
                    if (System.currentTimeMillis() >= nextAllowedAt) queue.takeNext()?.let { startSong(it) }
                } else {
                    val job = head.remote?.let { downloader.job(it.fileName) }
                    if (job?.state == "error") {
                        note = "Couldn't get \"${head.song.title}\": ${job.message}"
                        noteUntil = System.currentTimeMillis() + 8000
                        downloader.forget(head.song.file)
                        queue.takeNext()
                    } else if (head.remote == null) {
                        queue.takeNext()    // file gone and cannot be refetched
                    } else {
                        preparing = "Preparing \"${head.song.title}\"" + (job?.takeIf { it.state == "downloading" }?.let { " ${it.percent()}%" } ?: "...")
                    }
                }
            }
        }

        hostNote.visibility = if (auth.hostActive(history.currentSession().at)) View.GONE else View.VISIBLE

        if (!player.isPlaying()) {
            val q = queue.snapshot()
            upNext.text = when {
                preparing != null -> preparing
                note != null && System.currentTimeMillis() < noteUntil -> note

                q.isNotEmpty() -> "Up next: " + q.take(3).joinToString("   •   ") { "${it.singer}: ${it.song.title}" }
                library.songs().isEmpty() -> "No songs yet. Open Settings to add a song source, or copy videos into the songs folder."
                else -> "Scan the code to pick a song!"
            }
        }
    }

    private fun startSong(item: QueueItem) {
        if (!library.has(item.song.file) && item.remote?.let { downloader.job(it.fileName)?.canPlay() } != true) return
        player.start(item.song.file, "${item.singer}: ${item.song.title}", item.singer, item.song.title)
        currentItem = item
        idleLayer.visibility = View.GONE
    }

    private fun finishSong(skipped: Boolean = false) {
        if (!player.isPlaying()) return
        val finished = player.file
        currentItem?.let { history.record(it.singer, it.song.title, it.song.file, it.remote, skipped) }
        currentItem = null
        nextAllowedAt = System.currentTimeMillis() + settings.int("nextDelay", 0) * 1000L
        player.stop()
        idleLayer.visibility = View.VISIBLE
        // delete after playing unless kept or queued again
        if (queue.snapshot().none { it.song.file == finished }) {
            downloader.cancel(finished)       // cancel if still downloading
            // songs stay by default, oldest evicted first when space is needed
            if (settings.bool("deleteAfterPlay", false)) cache.afterPlay(finished)
        }
    }

    // Country of this network via api.country.is (it only sees the public IP).
    // Falls back to time zone and region if the call fails. Repeats every 6 hours.
    private fun findCountry() {
        Thread {
            val fromNetwork = try {
                val c = java.net.URL("https://api.country.is/").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                try { org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("country") } finally { c.disconnect() }
            } catch (e: Exception) { "" }
            val here = if (fromNetwork.length == 2) fromNetwork == "SG"
                else java.util.TimeZone.getDefault().id == "Asia/Singapore" || java.util.Locale.getDefault().country == "SG"
            handler.post {
                singapore = here
                if (::promoBox.isInitialized) applyPrefs()
                handler.postDelayed({ findCountry() }, 6 * 60 * 60 * 1000L)
            }
        }.start()
    }

    // New release check: at start and every 12 hours. Asks once per release, and only while nothing is playing.
    private fun lookForUpdate() {
        Thread {
            if (settings.bool("autoUpdate", true)) updater.check()
            handler.post { askAboutUpdate() }
            handler.postDelayed({ lookForUpdate() }, 12 * 60 * 60 * 1000L)
        }.start()
    }

    private fun askAboutUpdate() {
        if (!updater.newer() || settings.text("updateAsked", "") == updater.latest) return
        if (!accepted || player.isPlaying() || settingsOpen() || menuOpen() || isFinishing) {
            handler.postDelayed({ askAboutUpdate() }, 60000)
            return
        }
        settings.putText("updateAsked", updater.latest)
        android.app.AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("DDKaraoke " + updater.latest.removePrefix("v") + " is out (you have " + updater.current + ").\n\nIf you pick Later, you can still update from Settings > Check for updates.")
            .setPositiveButton("Update now", android.content.DialogInterface.OnClickListener { _, _ -> startUpdate() })
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate() {
        android.widget.Toast.makeText(this, "Downloading the update...", android.widget.Toast.LENGTH_LONG).show()
        Thread { updater.update() }.start()
    }

    // apply host settings
    private fun applyPrefs() {
        queue.fair = settings.bool("fairQueue", true)
        queue.limitPerPerson = settings.int("limitPerPerson", 0)
        joinBox?.visibility = if (settings.bool("hideQr", false)) View.GONE else View.VISIBLE
        promoBox.visibility = if (singapore && settings.bool("showPromo", true)) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): Int = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { 0 }

    private fun accept() {
        settings.acceptedVersion = appVersion()
        accepted = true
        consentLayer.visibility = View.GONE
        if (!player.isPlaying()) idleLayer.visibility = View.VISIBLE
        lastIp = "unset"          // force a QR redraw
        settingsButton.post { settingsButton.requestFocus() }
    }

    private fun settingsOpen() = settingsPage?.visibility == View.VISIBLE

    private fun openSettings() {
        settingsPage?.apply {
            loadUrl("http://127.0.0.1:$port/?tab=settings")
            visibility = View.VISIBLE
            requestFocus()
        }
    }

    private fun closeSettings() {
        settingsPage?.visibility = View.GONE
    }

    private fun menuOpen() = menuPage?.visibility == View.VISIBLE

    private fun openMenu() {
        menuPage?.apply {
            loadUrl("http://127.0.0.1:$port/?popup=1")
            visibility = View.VISIBLE
            requestFocus()
        }
        menuButton.text = "✕"
        menuButton.translationX = minOf(dp(460), resources.displayMetrics.widthPixels).toFloat()
        menuButton.alpha = 0.9f
    }

    private fun closeMenu() {
        menuPage?.visibility = View.GONE
        menuButton.text = "☰"
        menuButton.translationX = 0f
        menuButton.alpha = 0.45f
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!accepted) return super.onKeyDown(keyCode, event)
        if (menuOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeMenu(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (settingsOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeSettings(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (!player.isPlaying() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MENU)) {
            openSettings()
            return true
        }
        // remote OK skips the song
        if (player.isPlaying() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            finishSong(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
