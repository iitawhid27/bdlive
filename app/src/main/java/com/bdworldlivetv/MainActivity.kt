package com.bdworldlivetv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class Channel(val name: String, val url: String, val tags: Set<String>, val logo: String)

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val probe = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val base = "https://iptv-org.github.io/iptv/"
    private val sources = listOf(
        base + "countries/bd.m3u" to "bd",
        "https://raw.githubusercontent.com/saeidrahmanbd/BDIX-IPTV/main/IPTV-Playlist.m3u" to "",
        base + "countries/in.m3u" to "in",
        base + "countries/pk.m3u" to "pk",
        base + "categories/sports.m3u" to "sports",
        base + "categories/movies.m3u" to "fun",
        base + "categories/entertainment.m3u" to "fun",
        "https://raw.githubusercontent.com/AdeelWajid/iptv_validated_list/main/playlist.m3u" to "",
        base + "index.m3u" to ""
    )

    private val results = ConcurrentHashMap<String, Long>()
    private val order = AtomicLong()
    private val checker = Executors.newFixedThreadPool(10) as ThreadPoolExecutor
    private val logoPool = Executors.newFixedThreadPool(4)
    private val badLogos = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val logoCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val ui = Handler(Looper.getMainLooper())
    private val tick = AtomicBoolean(false)

    private var channels = listOf<Channel>()
    private var loading = false
    private var updated = "—"
    private var page = "home"
    private var wide = false
    private var current: Channel? = null
    private var playList = listOf<Channel>()
    private var playIndex = 0
    private val tried = mutableSetOf<String>()
    private var pageRefresh: (() -> Unit)? = null
    private var pageSource: (() -> List<Channel>)? = null

    private lateinit var mainHolder: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var footer: TextView
    private lateinit var playerLayer: FrameLayout
    private lateinit var playerStatus: TextView
    private lateinit var playerBar: LinearLayout
    private lateinit var pv: PlayerView
    private var panel: View? = null
    private var player: ExoPlayer? = null
    private val navItems = linkedMapOf<String, TextView>()

    private val white = -1
    private val grey = 0xFF9AA4B2.toInt()
    private val card = 0xFF141A33.toInt()
    private val purple = 0xFF7B2CFF.toInt()
    private val yellow = 0xFFFFD21E.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTv() = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION

    private fun isWide() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun playing() = playerLayer.visibility == View.VISIBLE

    private fun shape(vararg colors: Int, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        if (colors.size == 1) d.setColor(colors[0]) else {
            d.colors = colors
            d.orientation = GradientDrawable.Orientation.TL_BR
        }
        d.cornerRadius = dp(12).toFloat()
        if (stroke != 0) d.setStroke(dp(2), stroke)
        return d
    }

    private fun clickable(vararg colors: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(*colors, stroke = yellow))
        addState(intArrayOf(android.R.attr.state_focused), shape(*colors, stroke = yellow))
        addState(intArrayOf(), shape(*colors))
    }

    private fun label(value: String, size: Float, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(value: String, run: () -> Unit) = label(value, 14f, white, true).apply {
        gravity = Gravity.CENTER
        background = clickable(0xCC141A33.toInt())
        isFocusable = true
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { run() }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun margins(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildRoot()
        player = ExoPlayer.Builder(this).build()
        pv.player = player
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playerStatus.text = current?.name ?: ""
                if (playbackState == Player.STATE_BUFFERING) playerStatus.text = "Buffering… " + (current?.name ?: "")
            }
            override fun onPlayerError(error: PlaybackException) {
                tryNext()
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel != null -> closePanel()
                    playing() -> closePlayer()
                    page != "home" -> show("home")
                    else -> finish()
                }
            }
        })
        buildMain()
        show("home")
        loadSources()
    }

    private fun buildRoot() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF050816.toInt())
        mainHolder = FrameLayout(this)
        root.addView(mainHolder, FrameLayout.LayoutParams(-1, -1))

        playerLayer = FrameLayout(this)
        playerLayer.setBackgroundColor(0xFF000000.toInt())
        playerLayer.visibility = View.GONE
        playerLayer.isClickable = true
        pv = PlayerView(this)
        pv.setShowPreviousButton(false)
        pv.setShowNextButton(false)
        pv.setShowRewindButton(false)
        pv.setShowFastForwardButton(false)
        playerLayer.addView(pv, FrameLayout.LayoutParams(-1, -1))

        playerStatus = label("", 14f)
        playerStatus.setPadding(dp(16), dp(10), dp(16), dp(10))
        playerStatus.maxWidth = dp(260)
        playerLayer.addView(playerStatus, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        playerBar = row()
        playerBar.addView(button("⏮ আগের") { change(-1) }, margins(-2, -2))
        playerBar.addView(button("☰ তালিকা") { showPanel() }, margins(-2, -2))
        playerBar.addView(button("পরের ⏭") { change(1) }, margins(-2, -2))
        playerLayer.addView(playerBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            playerBar.visibility = v
        })

        root.addView(playerLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(mainHolder) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // Mobile (lomba) ar TV (aaraari) screen er jonno alada layout
    private fun buildMain() {
        wide = isWide()
        mainHolder.removeAllViews()
        navItems.clear()

        val nav = if (wide) column() else row()
        nav.background = shape(card)
        listOf(
            "home" to "⌂\nHome", "all" to "📺\nAll", "live" to "▶\nLive",
            "fav" to "★\nFavorite", "search" to "🔍\nSearch", "history" to "↺\nHistory",
            "settings" to "⚙\nSettings", "about" to "ⓘ\nAbout"
        ).forEach { (key, title) ->
            val item = label(title, 12f)
            item.gravity = Gravity.CENTER
            item.isFocusable = true
            item.setPadding(0, dp(6), 0, dp(6))
            item.setOnClickListener { show(key) }
            navItems[key] = item
            nav.addView(item, margins(if (wide) -1 else dp(68), -2))
        }

        val body = column()
        val header = row()
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = column()
        titles.addView(label("BD World Live TV", 20f, yellow, true))
        titles.addView(label("All Countries • All Channels", 11f, grey))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val clock = TextClock(this)
        clock.format12Hour = "hh:mm a"
        clock.format24Hour = "HH:mm"
        clock.setTextColor(white)
        header.addView(clock)
        body.addView(header, margins(-1, -2))
        content = FrameLayout(this)
        body.addView(content, margins(-1, 0, 1f))
        footer = label("", 11f, grey)
        footer.background = shape(card)
        footer.setPadding(dp(10), dp(6), dp(10), dp(6))
        body.addView(footer, margins(-1, -2))

        val main = if (wide) row() else column()
        if (wide) {
            val scroll = ScrollView(this)
            scroll.addView(nav)
            main.addView(scroll, margins(dp(86), -1))
            main.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            main.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            val scroll = HorizontalScrollView(this)
            scroll.addView(nav)
            main.addView(scroll, margins(-1, -2))
        }
        mainHolder.addView(main)
        ViewCompat.requestApplyInsets(mainHolder)
        updateBars()
    }

    private fun updateBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (playing() || isWide()) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!playing() && wide != isWide()) {
            buildMain()
            show(page)
        } else updateBars()
    }

    private fun show(target: String) {
        page = target
        pageRefresh = null
        pageSource = null
        checker.queue.clear()
        navItems.forEach { (key, view) ->
            view.background = if (key == target) clickable(purple) else clickable(card)
        }
        content.removeAllViews()
        val view: View = when (target) {
            "home" -> homePage()
            "all" -> listPage("All Channels (সব চ্যানেল)", false) { channels }
            "live" -> listPage("Live TV (চালু চ্যানেল)") { channels }
            "fav" -> listPage("Favorites (চেপে ধরে যোগ/বাদ দিন)", false) { byUrls(saved("fav")) }
            "history" -> listPage("History", false) { byUrls(saved("history")) }
            "search" -> searchPage()
            "settings" -> settingsPage()
            "about" -> textPage("BD World Live TV\nVersion 1.0\n\nপাবলিক M3U প্লেলিস্ট থেকে চ্যানেল দেখায়।")
            "bd" -> listPage("Bangladesh Channels") { tagged("bd") }
            "in" -> listPage("India Channels") { tagged("in") }
            "pk" -> listPage("Pakistan Channels") { tagged("pk") }
            "sports" -> listPage("Sports") { tagged("sports") }
            "fun" -> listPage("Movies & Entertainment") { tagged("fun") }
            else -> listPage("Other Countries") {
                channels.filter { c -> listOf("bd", "in", "pk").none { it in c.tags } }
            }
        }
        content.addView(view)
        updateFooter()
    }

    private fun tagged(tag: String) = channels.filter { tag in it.tags }

    private fun score(c: Channel) = results[c.url] ?: 0L

    private fun check(list: List<Channel>) {
        checker.queue.clear()
        list.filter { !results.containsKey(it.url) }.forEach { c ->
            checker.execute {
                if (!results.containsKey(c.url)) results[c.url] = probeUrl(c.url)
                scheduleUi()
            }
        }
    }

    private fun probeUrl(url: String): Long = try {
        probe.newCall(Request.Builder().url(url).build()).execute().use { r ->
            var alive = false
            if (r.isSuccessful) {
                val type = r.header("Content-Type").orEmpty().lowercase(Locale.US)
                alive = type.startsWith("video/") || r.peekBody(1024).string().let {
                    it.contains("#EXTM3U") || it.contains("<MPD")
                }
            }
            if (alive) order.incrementAndGet() else 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun scheduleUi() {
        if (tick.compareAndSet(false, true)) ui.postDelayed({
            tick.set(false)
            if (!isDestroyed) {
                if (!playing()) pageRefresh?.invoke()
                updateFooter()
            }
        }, 1500)
    }

    private fun logoView(url: String): ImageView {
        val view = ImageView(this)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        loadLogo(view, url)
        return view
    }

    private fun loadLogo(view: ImageView, url: String) {
        view.tag = url
        val cached = if (url.isBlank()) null else logoCache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(R.mipmap.ic_launcher)
        if (url.isBlank() || url in badLogos) return
        logoPool.execute {
            if (view.tag != url) return@execute
            var bitmap: Bitmap? = null
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 BDWorldLiveTV").build()
                probe.newCall(request).execute().use { r ->
                    val data = r.body
                    if (r.isSuccessful && data != null && data.contentLength() < 2_000_000) {
                        val bytes = data.bytes()
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        var sample = 1
                        while (maxOf(options.outWidth, options.outHeight) / sample > 200) sample *= 2
                        options.inJustDecodeBounds = false
                        options.inSampleSize = sample
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                }
            } catch (_: Exception) {
            }
            val done = bitmap
            if (done == null) badLogos.add(url) else {
                logoCache.put(url, done)
                ui.post { if (view.tag == url) view.setImageBitmap(done) }
            }
        }
    }

    private fun homePage(): View {
        val box = column()
        val banner = column()
        banner.background = shape(0xFF3A0CA3.toInt(), 0xFF0A1E5E.toInt(), 0xFF7209B7.toInt())
        banner.setPadding(dp(18), dp(14), dp(18), dp(14))
        banner.addView(label("🌍 BD World Live TV", 24f, white, true))
        banner.addView(label("Watch Your Favorite Channels\nAnytime, Anywhere", 14f))
        box.addView(banner, margins(-1, -2))

        val allTile = button("📺  All Channels (${channels.size})") { show("all") }
        allTile.background = clickable(purple, 0xFF3A0CA3.toInt())
        box.addView(allTile, margins(-1, -2))

        val tiles = mutableListOf<TextView>()
        fun tile(title: String, target: String, top: Int, bottom: Int) {
            val t = label(title, 12f, white, true)
            t.gravity = Gravity.CENTER
            t.background = clickable(top, bottom)
            t.isFocusable = true
            t.setOnClickListener { show(target) }
            tiles.add(t)
        }
        tile("🇧🇩\nBangladesh", "bd", 0xFF0F6E7A.toInt(), 0xFF0A2F45.toInt())
        tile("🇮🇳\nIndia", "in", 0xFFB7791F.toInt(), 0xFF3F3A0A.toInt())
        tile("🇵🇰\nPakistan", "pk", 0xFF0F8A4B.toInt(), 0xFF063B2A.toInt())
        tile("🌐\nOthers", "other", 0xFF1733D1.toInt(), 0xFF170A6B.toInt())
        tile("⚽\nSports", "sports", 0xFFB5175C.toInt(), 0xFF4A0A33.toInt())
        tile("🎬\nMovies", "fun", 0xFFC2540A.toInt(), 0xFF5A1E05.toInt())
        tiles.chunked(if (wide) 6 else 3).forEach { group ->
            val line = row()
            line.isBaselineAligned = false
            group.forEach { line.addView(it, margins(0, dp(90), 1f)) }
            box.addView(line)
        }

        box.addView(label("Popular Channels", 15f, white, true), margins(-1, -2))
        val picks = listOf("GTV", "ATN Bangla", "Star Jalsha", "Zee Bangla", "Sony", "Colors Bangla", "DD National")
            .mapNotNull { wanted ->
                val pattern = Regex("\\b" + Regex.escape(wanted) + "\\b", RegexOption.IGNORE_CASE)
                channels.firstOrNull { pattern.containsMatchIn(it.name) && results[it.url] != 0L }
            }
        val popular = row()
        picks.forEach { c ->
            val t = column()
            t.gravity = Gravity.CENTER
            t.background = clickable(card)
            t.isFocusable = true
            t.setPadding(dp(6), dp(6), dp(6), dp(6))
            t.setOnClickListener { play(c, picks) }
            t.addView(logoView(c.logo), LinearLayout.LayoutParams(-1, 0, 1f))
            val name = label(c.name, 11f, white, true)
            name.gravity = Gravity.CENTER
            name.setSingleLine(true)
            t.addView(name)
            popular.addView(t, margins(dp(110), dp(90)))
        }
        if (picks.isEmpty()) popular.addView(label(if (loading) "Loading…" else "পাওয়া যায়নি", 13f, grey))
        val across = HorizontalScrollView(this)
        across.addView(popular)
        box.addView(across)

        check(tagged("bd"))
        val scroll = ScrollView(this)
        scroll.addView(box)
        return scroll
    }

    private fun channelList(
        items: () -> List<Channel>,
        onPick: (List<Channel>, Int) -> Unit = { l, i -> play(l[i], l) }
    ): Pair<ListView, () -> Unit> {
        var shown = items()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val line = (convertView as? LinearLayout) ?: row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(56), dp(40)))
                    addView(label("", 15f).apply { setPadding(dp(14), 0, 0, 0) })
                }
                val c = shown[position]
                loadLogo(line.getChildAt(0) as ImageView, c.logo)
                (line.getChildAt(1) as TextView).text =
                    (if (c.url in saved("fav")) "★  " else "") + c.name
                return line
            }
        }
        val list = ListView(this)
        list.adapter = adapter
        val refresh: () -> Unit = {
            shown = items()
            adapter.notifyDataSetChanged()
        }
        list.setOnItemClickListener { _, _, position, _ -> onPick(shown, position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            toggleFavorite(shown[position])
            refresh()
            true
        }
        return list to refresh
    }

    private fun listPage(title: String, onlyWorking: Boolean = true, items: () -> List<Channel>): View {
        val box = column()
        box.addView(label(title, 16f, white, true))
        val result = channelList({
            if (onlyWorking) items().filter { score(it) > 0 }.sortedBy { score(it) } else items()
        })
        pageRefresh = result.second
        if (onlyWorking) {
            pageSource = items
            check(items())
        }
        box.addView(result.first, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }

    private fun searchPage(): View {
        val box = column()
        val input = EditText(this)
        input.hint = "চ্যানেলের নাম লিখুন"
        input.setTextColor(white)
        input.setHintTextColor(grey)
        input.setSingleLine(true)
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val result = channelList({
            val q = input.text.toString().trim()
            channels.filter { q.isEmpty() || it.name.contains(q, true) }
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                result.second()
            }
        })
        pageRefresh = result.second
        box.addView(input)
        box.addView(result.first, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }

    private fun settingsPage(): View {
        val box = column()
        box.addView(button("🔄  Refresh channels") {
            results.clear()
            show("home")
            loadSources()
        }, margins(-1, -2))
        box.addView(button("🗑  Clear history") {
            getPreferences(MODE_PRIVATE).edit().remove("history").apply()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }, margins(-1, -2))
        return box
    }

    private fun textPage(value: String): View =
        label(value, 15f).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

    private fun saved(key: String): List<String> =
        getPreferences(MODE_PRIVATE).getString(key, "").orEmpty().split("\n").filter { it.isNotBlank() }

    private fun save(key: String, value: List<String>) {
        getPreferences(MODE_PRIVATE).edit().putString(key, value.joinToString("\n")).apply()
    }

    private fun byUrls(urls: List<String>): List<Channel> {
        val map = channels.associateBy { it.url }
        return urls.mapNotNull { map[it] }
    }

    private fun toggleFavorite(c: Channel) {
        val old = saved("fav")
        val removing = c.url in old
        save("fav", if (removing) old - c.url else listOf(c.url) + old)
        Toast.makeText(this, if (removing) "Favorite থেকে বাদ" else "Favorite-এ যোগ হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun updateFooter() {
        val working = results.values.count { it > 0 }
        val waiting = checker.queue.size
        footer.text = (if (loading) "Loading…" else "Updated: $updated") +
            "  |  Working: $working" +
            (if (waiting > 0) "  |  Checking: $waiting" else "") +
            "  |  Total: ${channels.size}"
    }

    private fun loadSources() {
        if (loading) return
        loading = true
        updateFooter()
        Thread {
            val unique = linkedMapOf<String, Channel>()
            sources.forEach { (source, tag) ->
                try {
                    http.newCall(Request.Builder().url(source).build()).execute().use { response ->
                        if (response.isSuccessful) parse(response.body?.string().orEmpty(), tag, unique)
                    }
                    val snapshot = unique.values.toList()
                    runOnUiThread {
                        if (snapshot.size >= channels.size) channels = snapshot
                        if (!playing()) {
                            if (page == "home") show("home") else pageSource?.let { check(it()) }
                        }
                        updateFooter()
                    }
                } catch (_: Exception) {
                }
            }
            val result = unique.values.toList()
            runOnUiThread {
                loading = false
                if (result.isNotEmpty()) {
                    channels = result
                    updated = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                } else {
                    Toast.makeText(this, "চ্যানেল লোড হয়নি। ইন্টারনেট দেখুন।", Toast.LENGTH_LONG).show()
                }
                if (!playing()) {
                    pageRefresh?.invoke()
                    pageSource?.let { check(it()) }
                    if (page == "home") show("home")
                }
                updateFooter()
            }
        }.start()
    }

    private fun parse(body: String, tag: String, into: MutableMap<String, Channel>) {
        var meta = ""
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) meta = line
            else if ((line.startsWith("http://") || line.startsWith("https://")) && meta.isNotBlank()) {
                val name = meta.substringAfterLast(",").trim().ifBlank { "Unnamed" }
                val group = Regex("group-title=\"([^\"]+)\"").find(meta)?.groupValues?.get(1).orEmpty()
                val logo = Regex("tvg-logo=\"([^\"]+)\"").find(meta)?.groupValues?.get(1).orEmpty()
                val tags = mutableSetOf<String>()
                if (tag.isNotEmpty()) tags.add(tag)
                if (group.contains("bangladesh", true)) tags.add("bd")
                if (group.contains("sport", true)) tags.add("sports")
                val old = into[line]
                into[line] = old?.copy(tags = old.tags + tags, logo = old.logo.ifBlank { logo })
                    ?: Channel(name, line, tags, logo)
                meta = ""
            }
        }
    }

    private fun play(c: Channel, from: List<Channel>) {
        playList = from.toList()
        playIndex = playList.indexOfFirst { it.url == c.url }.coerceAtLeast(0)
        tried.clear()
        checker.queue.clear()
        start(c)
    }

    private fun start(c: Channel) {
        current = c
        tried.add(c.url)
        playerLayer.visibility = View.VISIBLE
        if (!isTv()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        updateBars()
        playerStatus.text = "Trying: " + c.name
        pv.requestFocus()
        pv.showController()
        player?.setMediaItem(MediaItem.fromUri(c.url))
        player?.prepare()
        player?.playWhenReady = true
        save("history", (listOf(c.url) + (saved("history") - c.url)).take(50))
    }

    private fun change(step: Int) {
        if (playList.isEmpty()) return
        playIndex = (playIndex + step + playList.size) % playList.size
        tried.clear()
        start(playList[playIndex])
    }

    private fun showPanel() {
        if (panel != null) return
        val list = channelList({ playList }, { l, i ->
            playIndex = i
            tried.clear()
            closePanel()
            start(l[i])
        }).first
        list.setBackgroundColor(0xEE050816.toInt())
        playerLayer.addView(list, FrameLayout.LayoutParams(dp(300), -1, Gravity.END))
        list.setSelection(playIndex)
        list.requestFocus()
        panel = list
        pv.hideController()
    }

    private fun closePanel() {
        panel?.let { playerLayer.removeView(it) }
        panel = null
    }

    // TV remote: Channel Up/Down ba Up/Down button diye channel change
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playing() && panel == null && event.action == KeyEvent.ACTION_DOWN) {
            val hidden = !pv.isControllerFullyVisible
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                (hidden && code == KeyEvent.KEYCODE_DPAD_UP)) {
                change(1)
                return true
            }
            if (code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                (hidden && code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                change(-1)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun tryNext() {
        val failed = current ?: return
        results[failed.url] = 0L
        val other = channels.firstOrNull {
            it.name.equals(failed.name, true) && it.url !in tried && results[it.url] != 0L
        }
        if (other != null && tried.size < 5) start(other)
        else {
            playerStatus.text = "চ্যানেলটি চলছে না। \"পরের\" চাপুন।"
            pv.showController()
        }
    }

    private fun closePlayer() {
        closePanel()
        current = null
        player?.stop()
        player?.clearMediaItems()
        playerLayer.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updateBars()
        if (wide != isWide() && isTv()) buildMain()
        if (page == "home") show("home") else {
            pageRefresh?.invoke()
            pageSource?.let { check(it()) }
            updateFooter()
        }
    }

    override fun onStart() {
        super.onStart()
        if (current != null) player?.play()
        else pageSource?.let { check(it()) }
    }

    override fun onStop() {
        super.onStop()
        checker.queue.clear()
        player?.pause()
    }

    override fun onDestroy() {
        checker.shutdownNow()
        logoPool.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        pv.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
