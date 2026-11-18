package app.adc.woodbeat

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_FILES = 2
        private const val REQ_FOLDER = 3
    }

    var controller: MediaController? = null
        private set
    private var future: ListenableFuture<MediaController>? = null
    val queue = ArrayList<Track>()
    val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Uri>()

    lateinit var playerPage: PlayerPage
    lateinit var eqPage: EqPage
    lateinit var libraryPage: LibraryPage
    private var pager: ViewPager2? = null
    private val tabs = ArrayList<Plaque>()
    private var firstTab = 0 // 0 on phones (Player is a tab), 1 on tablets (Player always shown)
    private lateinit var strip: View
    private lateinit var stripTitle: android.widget.TextView
    private lateinit var stripPlay: WoodButton

    private val tick = object : Runnable {
        override fun run() {
            playerPage.tick()
            updateStrip()
            handler.postDelayed(this, 200)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Engine.loadDsp(this) // saved EQ/balance before the sliders are built
        playerPage = PlayerPage(this)
        eqPage = EqPage(this)
        libraryPage = LibraryPage(this)
        // App area only: the system bars stay plain black (window background), the wood starts below/above them.
        val outer = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        outer.addView(buildShell(), FrameLayout.LayoutParams(MATCH, MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(outer)
        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            if (c.mediaItemCount == 0) restoreQueue(c)
            rebuildQueue()
            playerPage.onConnected()
            flushPending()
        }, ContextCompat.getMainExecutor(this))
        handler.post(tick)
    }

    override fun onStop() {
        saveQueue()
        handler.removeCallbacks(tick)
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (libraryPage.back()) return
        val p = pager
        if (p != null && firstTab == 0 && p.currentItem != 0) { p.currentItem = 0; return }
        moveTaskToBack(true)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) { rebuildQueue(); saveQueue() }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.mediaMetadata?.genre?.let { eqPage.autoLoad(it.toString()) }
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || player.isPlaying) lastError = null
            playerPage.refreshState()
            updateStrip()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val net = generateSequence<Throwable>(error) { it.cause }
                .any { it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException }
            lastError = if (net) "NO CONNECTION" else "CAN'T PLAY THIS"
            toast(if (net) "No internet connection — can't reach the stream" else "Can't play: ${error.errorCodeName}")
        }
    }

    /** Last playback error, shown on the LCD until something plays again. */
    var lastError: String? = null

    // ------------------------------------------------------------ shell

    private fun buildShell(): View {
        val root = vbox()
        root.background = Skin.wood(this)

        // Logo bar with overflow menu
        val logoBar = FrameLayout(this)
        val logo = ImageView(this).apply {
            setImageBitmap(Skin.bmp(this@MainActivity, R.drawable.logo))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        // Header row: the trimmed logo fills 96 % of its height (2 % gap top and bottom).
        val rowH = dpi(42f)
        val moreW = dpi(36f)
        logoBar.addView(logo, FrameLayout.LayoutParams(MATCH, (rowH * 0.96f).toInt(), Gravity.CENTER_VERTICAL))
        val more = ImageView(this).apply {
            setImageBitmap(Skin.bmp(this@MainActivity, R.drawable.glyph_overflow))
            setPadding(dpi(6f), dpi(7f), dpi(4f), dpi(7f))
            setOnClickListener { overflow(it) }
        }
        logoBar.addView(more, FrameLayout.LayoutParams(moreW, rowH, Gravity.END or Gravity.CENTER_VERTICAL))
        // Maker's stamp: full opacity, slightly tilted, starting under the logo's right ornament tip, low in the row.
        val stampBmp = Skin.bmp(this, R.drawable.stamp)
        val stamp = ImageView(this).apply {
            setImageBitmap(stampBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 1f
            rotation = -6f
        }
        logoBar.addView(stamp, FrameLayout.LayoutParams(0, 0, Gravity.START or Gravity.CENTER_VERTICAL))
        logoBar.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l == or_ - ol && stamp.width > 0) return@addOnLayoutChangeListener
            val w = r - l
            val d = logo.drawable ?: return@addOnLayoutChangeListener
            val logoW = minOf(w.toFloat(), logo.height * d.intrinsicWidth.toFloat() / d.intrinsicHeight)
            // Visible ends: the ornament tip sits at 98 % of the logo image; the ⋮ dots are ~17 dp from the right edge.
            val gapStart = (w - logoW) / 2f + logoW * 0.98f
            val gapEnd = w - dp(21f)
            val sw = minOf(dp(62f), gapEnd - gapStart - dp(2f)).toInt().coerceAtLeast(dpi(30f))
            val sh = sw * stampBmp.height / stampBmp.width
            v.post {
                stamp.layoutParams = FrameLayout.LayoutParams(sw, sh, Gravity.START or Gravity.CENTER_VERTICAL)
                stamp.translationX = gapStart + dp(1f) // starts just under the logo's right ornament tip
                stamp.translationY = dp(6f)            // sits low in the row, below the ornament
            }
        }
        root.addView(logoBar, lp(MATCH, rowH))

        val cfg = resources.configuration
        val twoPane = cfg.smallestScreenWidthDp >= 600 && cfg.screenWidthDp >= 720
        val pages: List<View>
        val names: List<String>
        if (twoPane) {
            firstTab = 1
            pages = listOf(eqPage.view, libraryPage.view)
            names = listOf("EQUALIZER", "LIBRARY")
        } else {
            pages = listOf(playerPage.view, eqPage.view, libraryPage.view)
            names = listOf("PLAYER", "EQUALIZER", "LIBRARY")
        }

        val tabRow = hbox()
        names.forEachIndexed { i, n ->
            val t = Plaque(this, n, 12f).apply { toggle = true; lit = i == 0; setOnClickListener { pager?.currentItem = i } }
            tabs += t
            tabRow.addView(t, lp(0, dpi(36f), 1f).margins(this, 3f, 0f, 3f, 0f))
        }

        val vp = ViewPager2(this).apply {
            offscreenPageLimit = pages.size
            adapter = PageAdapter(pages)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    tabs.forEachIndexed { i, t -> t.lit = i == position }
                    updateStrip()
                }
            })
        }
        pager = vp

        val right = vbox().apply {
            addView(tabRow, lp(MATCH, WRAP).margins(this@MainActivity, 8f, 2f, 8f, 8f))
            addView(vp, lp(MATCH, 0, 1f))
        }
        if (twoPane) {
            val row = hbox(Gravity.TOP)
            row.addView(playerPage.view, lp(0, MATCH, 1f))
            row.addView(right, lp(0, MATCH, 1f))
            root.addView(row, lp(MATCH, 0, 1f))
        } else {
            root.addView(right, lp(MATCH, 0, 1f))
        }

        strip = buildStrip()
        root.addView(strip, lp(MATCH, WRAP).margins(this, 8f, 0f, 8f, 6f))
        updateStrip()
        return root
    }

    private class PageAdapter(val pages: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = pages[viewType]
            (v.parent as? ViewGroup)?.removeView(v)
            v.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    /** Slim now-playing bar shown on the EQ / Library pages. */
    private fun buildStrip(): View {
        val row = hbox()
        // Single dark LCD strip holding the title and the three buttons.
        stripTitle = text(14f, Colors.LCD, mono = true).lcdSize(14f).apply {
            ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(stripTitle, lp(0, dpi(40f), 1f).margins(this, 4f, 0f, 6f, 0f))
        val prev = WoodButton(this, R.drawable.glyph_previous).apply { setOnClickListener { controller?.seekToPreviousMediaItem() } }
        stripPlay = WoodButton(this, R.drawable.glyph_play).apply { setOnClickListener { playPause() } }
        val next = WoodButton(this, R.drawable.glyph_next).apply { setOnClickListener { controller?.seekToNextMediaItem() } }
        for (b in listOf(prev, stripPlay, next)) row.addView(b, lp(dpi(40f), dpi(40f)).margins(this, 3f, 0f, 3f, 0f))
        return framed(row, raised = false, padDp = 9f)
    }

    private fun updateStrip() {
        val p = pager ?: return
        strip.visibility = if (firstTab == 0 && p.currentItem != 0) View.VISIBLE else View.GONE
        val c = controller
        val i = c?.currentMediaItemIndex ?: -1
        val t = queue.getOrNull(i)
        val label = if (t == null) "Woodbeat" else "${i + 1}. ${playerPage.nowTitle(t)}"
        if (stripTitle.text.toString() != label) stripTitle.text = label
        stripPlay.setGlyph(if (c?.isPlaying == true) R.drawable.glyph_pause else R.drawable.glyph_play)
    }

    fun showPage(tab: Int) {
        pager?.currentItem = (tab - firstTab).coerceAtLeast(0)
    }

    val isTwoPane get() = firstTab == 1

    private fun overflow(anchor: View) {
        // About + splash screen come later.
        woodMenu(anchor, listOf("Play URL…", "Rescan library")) {
            when (it) {
                "Play URL…" -> askUrl(playNow = true)
                else -> libraryPage.reload()
            }
        }
    }

    // ------------------------------------------------------------ permissions + pickers

    fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun askPermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            want += Manifest.permission.READ_MEDIA_AUDIO
            want += Manifest.permission.POST_NOTIFICATIONS
        } else {
            want += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else libraryPage.reload()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) libraryPage.reload()
    }

    fun pickFiles() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_FILES)
    }

    fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
                data.data?.let { if (uris.isEmpty()) uris += it }
                uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, flags) } }
                addUris(uris, play = false)
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
                toast("Reading folder…")
                io.execute {
                    val uris = runCatching { Library.audioInTree(this, tree) }.getOrDefault(emptyList())
                    runOnUiThread { if (uris.isEmpty()) toast("No audio files in that folder") else addUris(uris, play = false) }
                }
            }
        }
    }

    private fun addUris(uris: List<Uri>, play: Boolean) {
        if (uris.isEmpty()) return
        io.execute {
            val tracks = uris.map { Library.describe(this, it) }
            runOnUiThread { if (play) playTracks(tracks) else enqueue(tracks) }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.action = null
        pending += uri
        flushPending()
    }

    private fun flushPending() {
        if (controller == null || pending.isEmpty()) return
        val list = ArrayList(pending)
        pending.clear()
        io.execute {
            val tracks = list.map { Library.describe(this, it) }
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                val start = c.mediaItemCount
                enqueue(tracks)
                playAt(start)
            }
        }
    }

    fun askUrl(playNow: Boolean) {
        askText("Play URL", hint = "http://… stream, .pls or .m3u", url = true) { url ->
            io.execute {
                val stream = Radio.unwrap(url)
                val t = Track(stream, url.substringAfter("://").substringBefore('/'), radio = true)
                runOnUiThread {
                    if (playNow) {
                        val c = controller ?: return@runOnUiThread
                        val start = c.mediaItemCount
                        enqueue(listOf(t)); playAt(start)
                    } else enqueue(listOf(t))
                }
            }
        }
    }

    // ------------------------------------------------------------ queue

    fun rebuildQueue() {
        val c = controller ?: return
        queue.clear()
        for (i in 0 until c.mediaItemCount) queue += Track.fromItem(c.getMediaItemAt(i))
        playerPage.refreshList()
    }

    fun playTracks(list: List<Track>, start: Int = 0) {
        val c = controller ?: return toast("Player not ready")
        if (list.isEmpty()) return
        c.setMediaItems(list.map { it.toItem() }, start.coerceIn(0, list.size - 1), 0)
        c.prepare()
        c.play()
    }

    fun enqueue(list: List<Track>) {
        val c = controller ?: return toast("Player not ready")
        if (list.isEmpty()) return
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(list.map { it.toItem() })
        if (wasEmpty) c.prepare()
        toast(if (list.size == 1) "Added: ${list[0].label}" else "Added ${list.size} items")
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(c.currentMediaItemIndex)
            c.play()
        }
    }

    /** Re-orders / filters the playlist; order holds old indices. Keeps the current song playing. */
    fun reorder(order: List<Int>) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        val newCur = order.indexOf(cur)
        val pos = c.currentPosition
        val items = order.map { queue[it].toItem() }
        if (items.isEmpty()) { c.clearMediaItems(); return }
        c.setMediaItems(items, if (newCur >= 0) newCur else 0, if (newCur >= 0) pos else 0)
        c.prepare()
    }

    fun removeIndices(indices: Collection<Int>) {
        val c = controller ?: return
        for (i in indices.sortedDescending()) if (i in 0 until c.mediaItemCount) c.removeMediaItem(i)
    }

    private fun saveQueue() {
        val c = controller ?: return
        Engine.prefs(this).edit()
            .putString("queue", JSONArray(queue.map { it.toJson() }).toString())
            .putInt("queue_index", c.currentMediaItemIndex)
            .putLong("queue_pos", if (c.isCurrentMediaItemLive) 0 else c.currentPosition)
            .apply()
    }

    private fun restoreQueue(c: MediaController) {
        val p = Engine.prefs(this)
        val a = runCatching { JSONArray(p.getString("queue", "[]")) }.getOrNull() ?: return
        if (a.length() == 0) return
        val list = List(a.length()) { Track.fromJson(a.getJSONObject(it)) }
        val idx = p.getInt("queue_index", 0).coerceIn(0, list.size - 1)
        c.setMediaItems(list.map { it.toItem() }, idx, p.getLong("queue_pos", 0))
        c.prepare()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

fun LinearLayout.addAll(vararg v: View) = v.forEach { addView(it) }
