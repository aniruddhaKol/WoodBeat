package app.adc.woodbeat

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Main window (LCD, sliders, transport) + playlist window. */
class PlayerPage(private val a: MainActivity) {

    val view: LinearLayout = a.vbox()
    private val time = LcdTime(a)
    // LCD text is sized in dp like the seven-segment clock: a real LCD doesn't grow with the phone's font setting.
    private val title = a.text(15f, Colors.LCD, mono = true).lcdSize(15f)
    private val kbps = a.text(11f, Colors.LCD, mono = true).lcdSize(KBPS_DP)
    private val channels = a.text(11f, Colors.LCD_BRIGHT, mono = true).lcdSize(11f) // "stereo" / "mono" / nothing
    private val spectrum = Spectrum(a)
    private val elapsed = a.text(12f, Colors.IVORY, mono = true)
    private val total = a.text(12f, Colors.IVORY, mono = true)
    private val seek = WoodSlider(a, false).apply { showFill = true }
    private val volume = WoodSlider(a, false).apply { showFill = true }
    private val balance = WoodSlider(a, false).apply { snapCenter = true; centerTick = true }
    private val eqToggle = Plaque(a, "EQ", 11f).apply { toggle = true }
    private val plToggle = Plaque(a, "PL", 11f).apply { toggle = true }
    private val shuffle = WoodButton(a, R.drawable.glyph_shuffle).apply { toggle = true }
    private val repeat = WoodButton(a, R.drawable.glyph_repeat).apply { toggle = true }
    private val list = RecyclerView(a)
    private val adapter = PlAdapter()
    private lateinit var plHeader: View
    private lateinit var plPanel: View
    private lateinit var lcdBox: LinearLayout
    val selected = sortedSetOf<Int>()
    private var remainingMode = Engine.prefs(a).getBoolean("time_remaining", false)
    private var marquee = true

    init { build() } // keep after all property initialisers

    // ------------------------------------------------------------ layout

    private fun build() {
        view.setPadding(a.dpi(8f), 0, a.dpi(8f), a.dpi(4f))

        // LCD
        lcdBox = a.vbox()
        val top = a.hbox(Gravity.TOP)
        time.setOnClickListener {
            remainingMode = !remainingMode
            Engine.prefs(a).edit().putBoolean("time_remaining", remainingMode).apply()
            tick()
        }
        top.addView(time, lp(WRAP, WRAP))
        val right = a.vbox()
        title.apply {
            ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
            setOnClickListener { marquee = !marquee; isSelected = marquee }
        }
        right.addView(title, lp(MATCH, WRAP).margins(a, 0f, 2f, 0f, 6f))
        val infoRow = a.hbox()
        infoRow.addView(kbps, lp(0, WRAP, 1f))
        infoRow.addView(channels, lp(WRAP, WRAP).margins(a, 6f, 0f, 0f, 0f))
        right.addView(infoRow, lp(MATCH, WRAP))
        top.addView(right, lp(0, WRAP, 1f).margins(a, 10f, 0f, 0f, 0f))
        lcdBox.addView(top, lp(MATCH, WRAP))

        spectrum.mode = Engine.prefs(a).getInt("vis_mode", 0)
        spectrum.setOnClickListener {
            spectrum.mode = (spectrum.mode + 1) % 3
            Engine.prefs(a).edit().putInt("vis_mode", spectrum.mode).apply()
        }
        lcdBox.addView(spectrum, lp(MATCH, a.dpi(64f)).margins(a, 0f, 8f, 0f, 6f))

        val seekRow = a.hbox()
        seekRow.addView(elapsed, lp(WRAP, WRAP))
        seekRow.addView(seek, lp(0, a.dpi(30f), 1f).margins(a, 6f, 0f, 6f, 0f))
        seekRow.addView(total, lp(WRAP, WRAP))
        lcdBox.addView(seekRow, lp(MATCH, WRAP))
        seek.onChange = { v -> a.controller?.let { c -> if (c.duration > 0) elapsed.text = fmt((v * c.duration).toLong()) } }
        seek.onRelease = { v -> a.controller?.let { c -> if (c.duration > 0 && c.isCurrentMediaItemSeekable) c.seekTo((v * c.duration).toLong()) } }

        val main = a.vbox()
        main.addView(a.framed(lcdBox, raised = false, padDp = 12f), lp(MATCH, WRAP))

        // Volume / balance / EQ / PL
        val vb = a.hbox()
        vb.addView(a.text(10f, Colors.IVORY, bold = true).apply { text = "VOL" }, lp(WRAP, WRAP))
        vb.addView(volume, lp(0, a.dpi(30f), 2f).margins(a, 4f, 0f, 8f, 0f))
        vb.addView(a.text(10f, Colors.IVORY, bold = true).apply { text = "BAL" }, lp(WRAP, WRAP))
        vb.addView(balance, lp(0, a.dpi(30f), 1.1f).margins(a, 4f, 0f, 6f, 0f))
        vb.addView(eqToggle, lp(a.dpi(46f), a.dpi(30f)).margins(a, 0f, 0f, 4f, 0f))
        vb.addView(plToggle, lp(a.dpi(46f), a.dpi(30f)))
        main.addView(vb, lp(MATCH, WRAP).margins(a, 0f, 8f, 0f, 4f))
        volume.value = Engine.prefs(a).getFloat("volume", 1f)
        volume.onChange = { v -> a.controller?.volume = v }
        volume.onRelease = { v -> Engine.prefs(a).edit().putFloat("volume", v).apply() }
        balance.value = Engine.dsp.balance / 2f + 0.5f
        balance.onChange = { v -> Engine.dsp.balance = (v - 0.5f) * 2f }
        balance.onRelease = { Engine.saveDsp(a) }
        eqToggle.setOnClickListener { a.showPage(1) }
        eqToggle.setOnLongClickListener { a.eqPage.setEnabled(!Engine.dsp.enabled); true }
        plToggle.setOnClickListener { setPlaylistVisible(!plToggle.lit) }

        // Transport
        val tr = a.hbox()
        fun b(g: Int, click: () -> Unit) = WoodButton(a, g).apply { setOnClickListener { click() } }
        val buttons = listOf(
            b(R.drawable.glyph_previous) { a.controller?.seekToPreviousMediaItem() },
            b(R.drawable.glyph_play) { play() },
            b(R.drawable.glyph_pause) { pause() },
            b(R.drawable.glyph_stop) { stop() },
            b(R.drawable.glyph_next) { a.controller?.seekToNextMediaItem() },
        )
        for (x in buttons) tr.addView(x, lp(0, WRAP, 1f).margins(a, 2f, 0f, 2f, 0f))
        tr.addView(View(a), lp(a.dpi(8f), 1))
        shuffle.setOnClickListener { a.controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeat.setOnClickListener {
            a.controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        tr.addView(shuffle, lp(0, WRAP, 0.85f).margins(a, 2f, 0f, 2f, 0f))
        tr.addView(repeat, lp(0, WRAP, 0.85f).margins(a, 2f, 0f, 2f, 0f))
        main.addView(tr, lp(MATCH, WRAP).margins(a, 0f, 4f, 0f, 0f))

        val mainPanel = a.framed(main, raised = true, padDp = 12f)
        view.addView(mainPanel, lp(MATCH, WRAP))

        // Playlist
        plHeader = SectionHeader(a, "PLAYLIST")
        view.addView(plHeader, lp(MATCH, WRAP).margins(a, 0f, 6f, 0f, 2f))
        val pl = a.vbox()
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = adapter
        list.addItemDecoration(RowLines(a))
        pl.addView(a.framed(list, raised = false, padDp = 9f), lp(MATCH, 0, 1f))

        val buttonsRow = a.hbox()
        val add = Plaque(a, "ADD", 12f); val rem = Plaque(a, "REM", 12f); val sel = Plaque(a, "SEL", 12f)
        val misc = Plaque(a, "MISC", 12f) // MISC also holds the list options (Winamp's OPTS)
        for (p in listOf(add, rem, sel, misc)) buttonsRow.addView(p, lp(0, a.dpi(34f), 1f).margins(a, 2f, 0f, 2f, 0f))
        // Equal space above (to the list frame) and below (to the panel rim): measured 26 px vs 10 px before.
        pl.addView(buttonsRow, lp(MATCH, WRAP).margins(a, 0f, 3f, 0f, 3f))
        add.setOnClickListener { menu(it, "Add files…", "Add folder…", "Add URL…", "Add from library") { addAction(it) } }
        rem.setOnClickListener { menu(it, "Remove selected", "Crop (keep selected)", "Remove all") { remAction(it) } }
        sel.setOnClickListener { menu(it, "Select all", "Select none", "Invert selection") { selAction(it) } }
        val listOpts = listOf("New list", "Save list…", "Load list…")
        misc.setOnClickListener {
            menu(it, "Sort by title", "Sort by artist", "Reverse list", "Randomize list", "File info…", WOOD_MENU_BREAK, *listOpts.toTypedArray()) { c ->
                if (c in listOpts) optsAction(c) else miscAction(c)
            }
        }

        plPanel = a.framed(pl, raised = true, padDp = 12f)
        view.addView(plPanel, lp(MATCH, 0, 1f))
        setPlaylistVisible(Engine.prefs(a).getBoolean("pl_visible", true))
    }

    private fun setPlaylistVisible(show: Boolean) {
        plToggle.lit = show
        Engine.prefs(a).edit().putBoolean("pl_visible", show).apply()
        plHeader.visibility = if (show) View.VISIBLE else View.GONE
        plPanel.visibility = if (show) View.VISIBLE else View.GONE
        // Without the playlist the main window takes the page and the analyser grows.
        val mainPanel = view.getChildAt(0)
        mainPanel.layoutParams = lp(MATCH, if (show) WRAP else 0, if (show) 0f else 1f)
        (mainPanel as ViewGroup).getChildAt(0).layoutParams =
            android.widget.FrameLayout.LayoutParams(MATCH, if (show) WRAP else MATCH)
        val main = mainPanel.getChildAt(0) as LinearLayout
        main.getChildAt(0).layoutParams = lp(MATCH, if (show) WRAP else 0, if (show) 0f else 1f)
        (main.getChildAt(0) as ViewGroup).getChildAt(0).layoutParams =
            android.widget.FrameLayout.LayoutParams(MATCH, if (show) WRAP else MATCH)
        spectrum.layoutParams = lp(MATCH, if (show) a.dpi(64f) else 0, if (show) 0f else 1f).margins(a, 0f, 8f, 0f, 6f)
        view.requestLayout()
    }

    private fun menu(anchor: View, vararg items: String, act: (String) -> Unit) = a.woodMenu(anchor, items.toList(), act = act)

    // ------------------------------------------------------------ transport (Winamp semantics)

    private fun play() {
        val c = a.controller ?: return
        if (c.mediaItemCount == 0) { a.pickFiles(); return }
        if (c.isPlaying) { c.seekTo(0); return } // Winamp: Play while playing restarts the song
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(c.currentMediaItemIndex)
        c.play()
    }

    private fun pause() {
        val c = a.controller ?: return
        if (c.isPlaying) c.pause() else if (c.playbackState == Player.STATE_READY) c.play()
    }

    private fun stop() {
        val c = a.controller ?: return
        c.stop()
        c.seekTo(c.currentMediaItemIndex, 0)
    }

    // ------------------------------------------------------------ menu actions

    private fun addAction(s: String) = when (s) {
        "Add files…" -> a.pickFiles()
        "Add folder…" -> a.pickFolder()
        "Add URL…" -> a.askUrl(playNow = false)
        else -> a.showPage(2)
    }

    private fun remAction(s: String) {
        when (s) {
            "Remove selected" -> { a.removeIndices(selected.toList()); selected.clear() }
            "Crop (keep selected)" -> if (selected.isNotEmpty()) { a.reorder(selected.toList()); selected.clear() }
            "Remove all" -> { a.controller?.clearMediaItems(); selected.clear() }
        }
        refreshList()
    }

    private fun selAction(s: String) {
        val all = a.queue.indices.toSet()
        when (s) {
            "Select all" -> { selected.clear(); selected.addAll(all) }
            "Select none" -> selected.clear()
            else -> { val inv = all - selected; selected.clear(); selected.addAll(inv) }
        }
        refreshList()
    }

    private fun miscAction(s: String) {
        val q = a.queue
        val idx = q.indices.toList()
        when (s) {
            "Sort by title" -> a.reorder(idx.sortedBy { q[it].title.lowercase() })
            "Sort by artist" -> a.reorder(idx.sortedWith(compareBy({ q[it].artist.lowercase() }, { q[it].title.lowercase() })))
            "Reverse list" -> a.reorder(idx.reversed())
            "Randomize list" -> a.reorder(idx.shuffled())
            else -> fileInfo(selected.firstOrNull() ?: a.controller?.currentMediaItemIndex ?: -1)
        }
        selected.clear()
    }

    private fun optsAction(s: String) {
        when (s) {
            "New list" -> { a.controller?.clearMediaItems(); selected.clear() }
            "Save list…" -> a.askText("Save playlist", "My playlist") { name ->
                Library.savePlaylist(a, name, a.queue.toList()); a.toast("Saved \"$name\"")
            }
            else -> {
                val names = Library.playlistNames(a)
                if (names.isEmpty()) a.toast("No saved playlists")
                else a.pick("Load playlist", names) { a.playTracks(Library.loadPlaylist(a, names[it])) }
            }
        }
    }

    private fun fileInfo(i: Int) {
        val t = a.queue.getOrNull(i) ?: return
        val sb = StringBuilder()
        sb.append("Title: ${t.title}\n")
        if (t.artist.isNotBlank()) sb.append("Artist: ${t.artist}\n")
        if (t.album.isNotBlank()) sb.append("Album: ${t.album}\n")
        if (t.genre.isNotBlank()) sb.append("Genre: ${t.genre}\n")
        if (!t.radio) sb.append("Length: ${fmt(t.durationMs)}\n")
        if (t.sizeBytes > 0) sb.append("Size: %.1f MB\n".format(t.sizeBytes / 1048576.0))
        if (i == a.controller?.currentMediaItemIndex) Engine.player?.audioFormat?.let { f ->
            sb.append("Format: ${f.sampleMimeType ?: "?"}, ${f.sampleRate} Hz, ${f.channelCount} ch\n")
        }
        sb.append("\n${t.uri}")
        a.info(if (t.radio) "Stream info" else "File info", sb.toString())
    }

    // ------------------------------------------------------------ state

    fun onConnected() {
        a.controller?.volume = volume.value
        refreshState()
    }

    fun nowTitle(t: Track): String {
        if (!t.radio) return t.label + if (t.durationMs > 0) " (${fmt(t.durationMs)})" else ""
        val live = a.controller?.mediaMetadata?.title?.toString()
        return if (live.isNullOrBlank() || live == t.title) t.title else "${t.title}: $live"
    }

    fun refreshState() {
        val c = a.controller ?: return
        shuffle.lit = c.shuffleModeEnabled
        repeat.lit = c.repeatMode != Player.REPEAT_MODE_OFF
        repeat.badgeText = if (c.repeatMode == Player.REPEAT_MODE_ONE) "1" else null
        eqToggle.lit = Engine.dsp.enabled
        spectrum.playing = c.isPlaying
        adapter.notifyDataSetChanged()
        tick()
    }

    fun refreshList() {
        selected.retainAll(a.queue.indices.toSet())
        adapter.notifyDataSetChanged()
        tick()
    }

    fun tick() {
        val c = a.controller
        if (c == null) {
            time.update(LcdTime.State.STOP, 0, false)
            title.text = "Woodbeat"
            return
        }
        val i = c.currentMediaItemIndex
        val t = a.queue.getOrNull(i)
        val dur = if (c.duration == C.TIME_UNSET) 0 else c.duration
        val pos = c.currentPosition
        val state = when {
            (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING) && c.playWhenReady -> LcdTime.State.PLAY
            c.playbackState == Player.STATE_READY || (c.playbackState == Player.STATE_BUFFERING && pos > 0) -> LcdTime.State.PAUSE
            else -> LcdTime.State.STOP
        }
        val live = t?.radio == true || c.isCurrentMediaItemLive
        val secs = if (remainingMode && dur > 0 && !live) (dur - pos) / 1000 else pos / 1000
        time.update(state, secs, remainingMode && dur > 0 && !live)

        val label = if (t == null) "Woodbeat — ADD songs or open the LIBRARY" else "${i + 1}. ${nowTitle(t)}"
        if (title.text.toString() != label) title.text = label

        // kbps / kHz / channels
        val f = Engine.player?.audioFormat
        val rate = f?.sampleRate?.takeIf { it > 0 }
        var bitrate = listOf(f?.bitrate ?: -1, f?.averageBitrate ?: -1, f?.peakBitrate ?: -1).firstOrNull { it > 0 }?.div(1000)
        if (bitrate == null && t != null && !t.radio && t.sizeBytes > 0 && t.durationMs > 0) bitrate = (t.sizeBytes * 8 / t.durationMs).toInt()
        if (bitrate == null && t != null && t.kbps > 0) bitrate = t.kbps
        kbps.text = when {
            a.lastError != null -> a.lastError
            c.playbackState == Player.STATE_BUFFERING -> "BUFFERING…"
            t == null -> ""
            else -> "${bitrate ?: "--"}kbps ${rate?.let { (it + 500) / 1000 } ?: "--"}kHz"
        }
        kbps.setTextColor(if (a.lastError != null) Colors.ORANGE else Colors.LCD)
        val ch = f?.channelCount ?: 0
        val chText = if (t == null || a.lastError != null) "" else if (ch >= 2) "stereo" else if (ch == 1) "mono" else ""
        if (channels.text.toString() != chText) channels.text = chText
        // Safety net: if the line still doesn't fit (narrow phone, long bitrate), shrink it a little instead of "…".
        kbps.post { fitText(kbps, KBPS_DP) }

        seek.isEnabled = !live && dur > 0
        if (!seek.dragging) {
            seek.value = if (dur > 0) pos.toFloat() / dur else 0f
            elapsed.text = fmt(pos)
        }
        total.text = if (live) "LIVE" else fmt(dur)
        if (!volume.dragging) volume.value = c.volume
        spectrum.playing = c.isPlaying
    }

    // ------------------------------------------------------------ playlist rows

    private inner class Holder(val row: LinearLayout, val name: TextView, val len: TextView) : RecyclerView.ViewHolder(row)

    private inner class PlAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = a.queue.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = a.hbox().apply { setPadding(a.dpi(8f), a.dpi(7f), a.dpi(8f), a.dpi(7f)) }
            val name = a.text(15f, Colors.LCD, mono = true)
            val len = a.text(15f, Colors.LCD, mono = true)
            row.addView(name, lp(0, WRAP, 1f))
            row.addView(len, lp(WRAP, WRAP).margins(a, 8f, 0f, 0f, 0f))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return Holder(row, name, len)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: Holder, position: Int) {
            val t = a.queue[position]
            val current = position == a.controller?.currentMediaItemIndex
            h.name.text = "${position + 1}. ${t.label}"
            h.len.text = if (t.radio) "LIVE" else fmt(t.durationMs)
            val color = if (current) Colors.LCD_BRIGHT else Colors.LCD
            h.name.setTextColor(color); h.len.setTextColor(color)
            h.row.setBackgroundColor(
                when {
                    position in selected -> Colors.ROW_SELECTED
                    current -> Colors.ROW_PLAYING
                    else -> 0
                },
            )
            h.row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0) return@setOnClickListener
                if (selected.isNotEmpty()) toggleSel(p) else a.playAt(p)
            }
            h.row.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) toggleSel(p)
                true
            }
        }
    }

    private fun toggleSel(p: Int) {
        if (!selected.remove(p)) selected.add(p)
        adapter.notifyItemChanged(p)
        tick()
    }
}
