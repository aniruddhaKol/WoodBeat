package app.adc.woodbeat

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Songs on the device (MediaStore), saved playlists, and file metadata. */
object Library {

    @Volatile var songs: List<Track> = emptyList()
        private set

    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val genres = if (Build.VERSION.SDK_INT < 30) legacyGenres(ctx) else emptyMap()
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.GENRE
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(6) ?: ""
                val artist = c.getString(2)?.takeUnless { it == "<unknown>" } ?: ""
                out += Track(
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    title = c.getString(1) ?: c.getString(7) ?: "?",
                    artist = artist,
                    album = c.getString(3)?.takeUnless { it == "<unknown>" } ?: "",
                    durationMs = c.getLong(4),
                    sizeBytes = c.getLong(5),
                    folder = File(path).parentFile?.name ?: "",
                    genre = if (Build.VERSION.SDK_INT >= 30) c.getString(8) ?: "" else genres[id] ?: "",
                )
            }
        }
        songs = out
        return out
    }

    private fun legacyGenres(ctx: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null,
        )?.use { g ->
            while (g.moveToNext()) {
                val name = g.getString(1) ?: continue
                val members = MediaStore.Audio.Genres.Members.getContentUri("external", g.getLong(0))
                ctx.contentResolver.query(members, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)
                    ?.use { m -> while (m.moveToNext()) map[m.getLong(0)] = name }
            }
        }
        return map
    }

    /** Title/artist/duration of a file picked through the system picker. */
    fun describe(ctx: Context, uri: Uri): Track {
        var name = uri.lastPathSegment ?: "?"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } }
        }
        var title = name.substringBeforeLast('.')
        var artist = ""
        var album = ""
        var dur = 0L
        runCatching {
            MediaMetadataRetriever().apply {
                setDataSource(ctx, uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
                artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                album = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                dur = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                release()
            }
        }
        return Track(uri.toString(), title, artist, album, dur, size)
    }

    /** All audio files below a folder picked with the system folder picker. */
    fun audioInTree(ctx: Context, tree: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        fun walk(docId: String, depth: Int) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val rows = ArrayList<Triple<String, String, String>>()
                while (c.moveToNext()) rows += Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
                for ((id, mime, _) in rows.sortedBy { it.third.lowercase() }) {
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id, depth + 1)
                        mime.startsWith("audio/") -> out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    // ---- Saved playlists (app files, JSON) ----

    private fun plDir(ctx: Context) = File(ctx.filesDir, "playlists").apply { mkdirs() }

    fun playlistNames(ctx: Context): List<String> =
        plDir(ctx).listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun savePlaylist(ctx: Context, name: String, tracks: List<Track>) {
        File(plDir(ctx), safe(name) + ".json").writeText(JSONArray(tracks.map { it.toJson() }).toString())
    }

    fun loadPlaylist(ctx: Context, name: String): List<Track> = runCatching {
        val a = JSONArray(File(plDir(ctx), safe(name) + ".json").readText())
        List(a.length()) { Track.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun deletePlaylist(ctx: Context, name: String) {
        File(plDir(ctx), safe(name) + ".json").delete()
    }

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Playlist" }
}

/** Internet radio: saved stations + radio-browser.info search. The only part of the app that uses the network. */
object Radio {

    /** Starter list: Indian Bengali stations (streams checked 2026-11-18). */
    private val STARTER = listOf(
        Track("https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio055/hlspbaudio05564kbps.m3u8", "Akashvani Kolkata", radio = true, kbps = 64),
        Track("https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio058/hlspbaudio05864kbps.m3u8", "Akashvani FM Rainbow Kolkata", radio = true, kbps = 64),
        Track("https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio057/hlspbaudio05764kbps.m3u8", "Akashvani FM Gold Kolkata", radio = true, kbps = 64),
        Track("http://stream.zeno.fm/2k6xfu1pvs8uv", "Dipak (Bengali hits)", radio = true, kbps = 64),
        Track("http://stream.zeno.fm/10rt7tpf6ehvv", "Radio Malda", radio = true, kbps = 128),
        Track("http://stream.zeno.fm/krk4kw830tzuv", "Radio Quarantine Kolkata", radio = true, kbps = 128),
        Track("http://stream.zeno.fm/xush70b1cbruv", "Ankora Radio", radio = true, kbps = 128),
        Track("https://stream.zeno.fm/ybf1umk1k18uv", "Radio Bhumi", radio = true, kbps = 192),
    )

    fun stations(ctx: Context): MutableList<Track> {
        val p = Engine.prefs(ctx)
        if (!p.getBoolean("radio_seeded", false)) {
            save(ctx, STARTER)
            p.edit().putBoolean("radio_seeded", true).apply()
        }
        val a = JSONArray(p.getString("stations", "[]") ?: "[]")
        return MutableList(a.length()) { Track.fromJson(a.getJSONObject(it)) }
    }

    fun save(ctx: Context, list: List<Track>) {
        Engine.prefs(ctx).edit().putString("stations", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** Blocking; call off the main thread. */
    fun search(query: String, indiaOnly: Boolean): List<Track> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val country = if (indiaOnly) "&countrycode=IN" else ""
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (field in listOf("name", "tag", "language")) {
            val url = "https://de1.api.radio-browser.info/json/stations/search?$field=$q$country" +
                "&hidebroken=true&order=votes&reverse=true&limit=40"
            val a = runCatching { JSONArray(get(url)) }.getOrNull() ?: continue
            for (i in 0 until a.length()) {
                val s: JSONObject = a.getJSONObject(i)
                val stream = s.optString("url_resolved").ifBlank { s.optString("url") }
                if (stream.isBlank() || !seen.add(s.optString("stationuuid"))) continue
                val where = listOf(s.optString("state"), s.optString("language")).filter { it.isNotBlank() }.joinToString(" · ")
                out += Track(stream, s.optString("name").trim(), artist = where, radio = true, kbps = s.optInt("bitrate"))
            }
        }
        return out
    }

    /** .pls / .m3u links -> first stream URL (ExoPlayer plays the stream itself, not the list). Blocking. */
    fun unwrap(url: String): String {
        val lower = url.lowercase().substringBefore('?')
        if (!(lower.endsWith(".pls") || lower.endsWith(".m3u"))) return url
        return runCatching {
            get(url).lines().map { it.trim() }
                .map { if (it.startsWith("File", true) && it.contains('=')) it.substringAfter('=') else it }
                .firstOrNull { it.startsWith("http", true) } ?: url
        }.getOrDefault(url)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Woodbeat/0.1")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
