package app.adc.woodbeat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Library window: songs / albums / artists / folders / genres / playlists / radio. */
class LibraryPage(private val a: MainActivity) {

    private class Row(
        val icon: Int, val title: String, val sub: String = "", val right: String = "", val chevron: Boolean = false,
        val click: () -> Unit, val long: ((View) -> Unit)? = null,
    )

    private sealed class Screen(val title: String) {
        object Root : Screen("")
        class Group(title: String, val kind: String) : Screen(title)
        class Songs(title: String, val tracks: List<Track>, val playlistName: String? = null) : Screen(title)
        object Stations : Screen("Radio")
        class Results(val query: String, val tracks: List<Track>) : Screen("“$query”")
    }

    val view: LinearLayout = a.vbox()
    private val stack = ArrayList<Screen>()
    private var rows: List<Row> = emptyList()
    private val nav = a.hbox()
    private val back = Plaque(a, "‹ BACK", 12f)
    private val heading = a.text(15f, Colors.IVORY, bold = true)
    private val actions = a.hbox()
    private val list = RecyclerView(a)
    private val adapter = Adapter()
    private var loading = false

    init { build() }

    private fun build() {
        // Single frame: the back / title / actions row sits on the wood (like the tabs), the list in one dark frame.
        view.setPadding(a.dpi(8f), 0, a.dpi(8f), a.dpi(4f))
        back.setOnClickListener { back() }
        nav.addView(back, lp(WRAP, a.dpi(34f)))
        nav.addView(heading, lp(0, WRAP, 1f).margins(a, 10f, 0f, 6f, 0f))
        nav.addView(actions, lp(WRAP, WRAP))
        view.addView(nav, lp(MATCH, WRAP).margins(a, 3f, 0f, 3f, 8f))
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = adapter
        list.addItemDecoration(RowLines(a))
        view.addView(a.framed(list, raised = false, padDp = 9f), lp(MATCH, 0, 1f))
        stack += Screen.Root
        render()
    }

    fun reload() {
        if (!a.hasAudioPermission() || loading) { render(); return }
        loading = true
        a.io.execute {
            runCatching { Library.scan(a) }
            a.runOnUiThread { loading = false; render() }
        }
    }

    /** Back inside the library; false when already at the top. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        render()
        return true
    }

    private fun open(s: Screen) { stack += s; render(); list.scrollToPosition(0) }

    private fun action(label: String, click: () -> Unit) {
        actions.addView(Plaque(a, label, 11f).apply { setOnClickListener { click() } },
            lp(WRAP, a.dpi(34f)).margins(a, 4f, 0f, 0f, 0f))
    }

    private fun render() {
        val s = stack.last()
        nav.visibility = if (s is Screen.Root) View.GONE else View.VISIBLE
        heading.text = s.title
        actions.removeAllViews()
        rows = when (s) {
            is Screen.Root -> rootRows()
            is Screen.Group -> groupRows(s.kind)
            is Screen.Songs -> {
                action("PLAY") { a.playTracks(s.tracks) }
                action("ADD") { a.enqueue(s.tracks) }
                songRows(s)
            }
            is Screen.Stations -> {
                action("SEARCH") { search() }
                action("URL") { a.askUrl(playNow = true) }
                stationRows()
            }
            is Screen.Results -> resultRows(s)
        }
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ screens

    private fun rootRows(): List<Row> {
        val out = ArrayList<Row>()
        val songs = Library.songs
        if (!a.hasAudioPermission()) out += Row(R.drawable.glyph_song, "Allow access to your music", "Needed to list songs on this device",
            click = { a.askPermissions() })
        else if (loading && songs.isEmpty()) out += Row(R.drawable.glyph_song, "Reading your music…", click = {})
        // Order: Radio, All Songs, Folders, Playlists, then Albums, Artists, Genres.
        out += Row(R.drawable.glyph_radio, "Radio", right = "${Radio.stations(a).size}", chevron = true,
            click = { open(Screen.Stations) })
        out += Row(R.drawable.glyph_song, "All Songs", right = "${songs.size}", chevron = true,
            click = { open(Screen.Songs("All Songs", songs)) })
        out += Row(R.drawable.glyph_folder, "Folders", right = "${songs.map { it.folder }.distinct().size}", chevron = true,
            click = { open(Screen.Group("Folders", "folder")) })
        out += Row(R.drawable.glyph_playlist, "Playlists", right = "${Library.playlistNames(a).size}", chevron = true,
            click = { open(Screen.Group("Playlists", "playlist")) })
        out += Row(R.drawable.glyph_album, "Albums", right = "${songs.map { it.album }.filter { it.isNotBlank() }.distinct().size}", chevron = true,
            click = { open(Screen.Group("Albums", "album")) })
        out += Row(R.drawable.glyph_artist, "Artists", right = "${songs.map { it.artist }.filter { it.isNotBlank() }.distinct().size}", chevron = true,
            click = { open(Screen.Group("Artists", "artist")) })
        out += Row(R.drawable.glyph_genre, "Genres", right = "${songs.map { it.genre }.filter { it.isNotBlank() }.distinct().size}", chevron = true,
            click = { open(Screen.Group("Genres", "genre")) })
        return out
    }

    private fun groupRows(kind: String): List<Row> {
        if (kind == "playlist") {
            return Library.playlistNames(a).map { n ->
                val tracks = Library.loadPlaylist(a, n)
                Row(R.drawable.glyph_playlist, n, right = "${tracks.size}", chevron = true,
                    click = { open(Screen.Songs(n, tracks, n)) },
                    long = { v -> popup(v, "Play", "Enqueue", "Delete") { when (it) {
                        "Play" -> a.playTracks(tracks); "Enqueue" -> a.enqueue(tracks)
                        else -> { Library.deletePlaylist(a, n); render() }
                    } } })
            }
        }
        val key: (Track) -> String = when (kind) {
            "album" -> { t -> t.album }
            "artist" -> { t -> t.artist }
            "folder" -> { t -> t.folder }
            else -> { t -> t.genre }
        }
        val icon = when (kind) {
            "album" -> R.drawable.glyph_album; "artist" -> R.drawable.glyph_artist
            "folder" -> R.drawable.glyph_folder; else -> R.drawable.glyph_genre
        }
        return Library.songs.groupBy { key(it).ifBlank { "Unknown" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (name, tracks) ->
            val sorted = if (kind == "album") tracks else tracks.sortedBy { it.title.lowercase() }
            val sub = if (kind == "album") tracks.firstOrNull()?.artist ?: "" else ""
            Row(icon, name, sub, "${tracks.size}", chevron = true,
                click = { open(Screen.Songs(name, sorted)) },
                long = { v -> popup(v, "Play", "Enqueue") { if (it == "Play") a.playTracks(sorted) else a.enqueue(sorted) } })
        }
    }

    private fun songRows(s: Screen.Songs): List<Row> = s.tracks.mapIndexed { i, t ->
        Row(R.drawable.glyph_song, t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
            if (t.radio) "LIVE" else fmt(t.durationMs),
            click = { a.playTracks(s.tracks, i) },
            long = { v -> popup(v, "Play", "Enqueue", "Play next") { when (it) {
                "Play" -> a.playTracks(s.tracks, i)
                "Enqueue" -> a.enqueue(listOf(t))
                else -> a.controller?.let { c -> c.addMediaItem(c.currentMediaItemIndex + 1, t.toItem()); a.toast("Plays next: ${t.label}") }
            } } })
    }

    private fun stationRows(): List<Row> {
        val stations = Radio.stations(a)
        return stations.mapIndexed { i, st ->
            Row(R.drawable.glyph_radio, st.title, st.artist, if (st.kbps > 0) "${st.kbps}k" else "",
                click = { a.playTracks(listOf(st)) },
                long = { v -> popup(v, "Play", "Enqueue", "Rename…", "Delete") { when (it) {
                    "Play" -> a.playTracks(listOf(st))
                    "Enqueue" -> a.enqueue(listOf(st))
                    "Rename…" -> a.askText("Rename station", st.title) { n -> stations[i] = st.copy(title = n); Radio.save(a, stations); render() }
                    else -> { stations.removeAt(i); Radio.save(a, stations); render() }
                } } })
        }
    }

    private fun resultRows(s: Screen.Results): List<Row> {
        if (s.tracks.isEmpty()) return listOf(Row(R.drawable.glyph_radio, "No stations found", "Try a city, language or genre", click = {}))
        return s.tracks.map { st ->
            Row(R.drawable.glyph_radio, st.title, st.artist, if (st.kbps > 0) "${st.kbps}k" else "",
                click = { a.playTracks(listOf(st)) },
                long = { v -> popup(v, "Play", "Save station", "Enqueue") { when (it) {
                    "Play" -> a.playTracks(listOf(st))
                    "Enqueue" -> a.enqueue(listOf(st))
                    else -> {
                        val list = Radio.stations(a)
                        if (list.none { x -> x.uri == st.uri }) { list += st; Radio.save(a, list) }
                        a.toast("Saved: ${st.title}")
                    }
                } } })
        }
    }

    private fun search() {
        a.askSearch { q, india ->
            a.toast("Searching…")
            a.io.execute {
                val found = runCatching { Radio.search(q, india) }
                a.runOnUiThread {
                    found.onSuccess { open(Screen.Results(q, it)) }
                        .onFailure { a.toast("Search failed — check the internet connection") }
                }
            }
        }
    }

    private fun popup(anchor: View, vararg items: String, act: (String) -> Unit) = a.woodMenu(anchor, items.toList(), act = act)

    // ------------------------------------------------------------ rows

    private class Holder(
        val row: LinearLayout, val icon: ImageView, val title: TextView, val sub: TextView, val right: TextView, val chev: ImageView,
    ) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = a.hbox().apply { setPadding(a.dpi(8f), a.dpi(10f), a.dpi(6f), a.dpi(10f)); isClickable = true }
            val icon = ImageView(a)
            row.addView(icon, lp(a.dpi(32f), a.dpi(32f)))
            val col = a.vbox()
            val title = a.text(16f, Colors.IVORY)
            val sub = a.text(12f, Colors.IVORY_DIM)
            col.addView(title); col.addView(sub)
            row.addView(col, lp(0, WRAP, 1f).margins(a, 12f, 0f, 8f, 0f))
            val right = a.text(14f, Colors.IVORY).apply { gravity = Gravity.END }
            row.addView(right, lp(WRAP, WRAP))
            val chev = ImageView(a)
            row.addView(chev, lp(a.dpi(20f), a.dpi(20f)).margins(a, 6f, 0f, 0f, 0f))
            row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return Holder(row, icon, title, sub, right, chev)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rows[position]
            h.icon.setImageBitmap(Skin.bmp(a, r.icon))
            h.title.text = r.title
            h.sub.text = r.sub
            h.sub.visibility = if (r.sub.isBlank()) View.GONE else View.VISIBLE
            h.right.text = r.right
            h.chev.visibility = if (r.chevron) View.VISIBLE else View.INVISIBLE
            if (r.chevron) h.chev.setImageBitmap(Skin.bmp(a, R.drawable.glyph_chevron))
            h.row.setOnClickListener { r.click() }
            h.row.setOnLongClickListener { v -> r.long?.invoke(v); r.long != null }
        }
    }
}
