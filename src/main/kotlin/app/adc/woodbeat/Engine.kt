package app.adc.woodbeat

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/** One entry of the playlist: a local song or a radio stream. */
data class Track(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val radio: Boolean = false,
    val kbps: Int = 0,
    val folder: String = "",
    val genre: String = "",
) {
    val label: String get() = if (artist.isBlank() || radio) title else "$artist - $title"

    fun toItem(): MediaItem {
        val extras = Bundle().apply {
            putLong("dur", durationMs); putLong("size", sizeBytes)
            putBoolean("radio", radio); putInt("kbps", kbps)
            putString("album", album); putString("genre", genre); putString("folder", folder)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifBlank { null })
            .setAlbumTitle(album.ifBlank { null })
            .setGenre(genre.ifBlank { null })
            .setStation(if (radio) title else null)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setMediaMetadata(meta)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .build()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("u", uri).put("t", title).put("a", artist).put("al", album).put("d", durationMs)
        .put("s", sizeBytes).put("r", radio).put("k", kbps).put("f", folder).put("g", genre)

    companion object {
        fun fromItem(item: MediaItem): Track {
            val m = item.mediaMetadata
            val x = m.extras
            return Track(
                uri = item.mediaId,
                title = (if (x?.getBoolean("radio") == true) m.station ?: m.title else m.title)?.toString() ?: item.mediaId,
                artist = m.artist?.toString() ?: "",
                album = x?.getString("album") ?: "",
                durationMs = x?.getLong("dur") ?: 0,
                sizeBytes = x?.getLong("size") ?: 0,
                radio = x?.getBoolean("radio") ?: false,
                kbps = x?.getInt("kbps") ?: 0,
                folder = x?.getString("folder") ?: "",
                genre = x?.getString("genre") ?: "",
            )
        }

        fun fromJson(o: JSONObject) = Track(
            o.getString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"),
            o.optLong("s"), o.optBoolean("r"), o.optInt("k"), o.optString("f"), o.optString("g"),
        )
    }
}

/** Process-wide state shared by the service and the UI (same process). */
object Engine {
    val dsp = WoodDsp()
    @Volatile var player: ExoPlayer? = null

    /** Winamp's built-in presets (dB, bands 60 Hz … 16 kHz). */
    val PRESETS: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f),
        "Club" to floatArrayOf(0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f),
        "Dance" to floatArrayOf(9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f),
        "Full Bass" to floatArrayOf(-8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f),
        "Full Bass & Treble" to floatArrayOf(7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f),
        "Full Treble" to floatArrayOf(-9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 12f, 12f, 12f, 12f),
        "Headphones" to floatArrayOf(4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12f, 12f),
        "Large Hall" to floatArrayOf(10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f),
        "Live" to floatArrayOf(-4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f),
        "Party" to floatArrayOf(7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f),
        "Pop" to floatArrayOf(-1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f),
        "Reggae" to floatArrayOf(0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f),
        "Ska" to floatArrayOf(-2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f),
        "Soft" to floatArrayOf(4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f),
        "Soft Rock" to floatArrayOf(4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f),
        "Techno" to floatArrayOf(8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f),
    )

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("woodbeat", Context.MODE_PRIVATE)

    /** Loads EQ / balance settings into the DSP (called by the service at start). */
    fun loadDsp(ctx: Context) {
        val p = prefs(ctx)
        dsp.enabled = p.getBoolean("eq_on", true)
        dsp.preampDb = p.getFloat("eq_pre", 0f)
        dsp.balance = p.getFloat("balance", 0f)
        val g = p.getString("eq_bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (g != null && g.size == WoodDsp.BANDS) for (i in 0 until WoodDsp.BANDS) dsp.gainsDb[i] = g[i]
        dsp.changed()
    }

    fun saveDsp(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean("eq_on", dsp.enabled)
            .putFloat("eq_pre", dsp.preampDb)
            .putFloat("balance", dsp.balance)
            .putString("eq_bands", dsp.gainsDb.joinToString(","))
            .apply()
    }

    fun customPresets(ctx: Context): LinkedHashMap<String, FloatArray> {
        val out = linkedMapOf<String, FloatArray>()
        val o = JSONObject(prefs(ctx).getString("eq_custom", "{}") ?: "{}")
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = FloatArray(WoodDsp.BANDS) { a.optDouble(it, 0.0).toFloat() }
        }
        return out
    }

    fun saveCustomPresets(ctx: Context, map: Map<String, FloatArray>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray(v.map { it.toDouble() }))
        prefs(ctx).edit().putString("eq_custom", o.toString()).apply()
    }

    /** Media items sent through the session lose their URI on some paths; rebuild it from the media id. */
    fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
        return item.buildUpon().setUri(uri).build()
    }
}
