package app.adc.woodbeat

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Equalizer window: ON, AUTO, presets, response graph, preamp + 10 bands. */
class EqPage(private val a: MainActivity) {

    val view: LinearLayout = a.vbox()
    private val dsp = Engine.dsp
    private val onSwitch = ImageView(a)
    private val preset = Plaque(a, "Flat", 14f).apply { dropDown = true }
    private val graph = EqGraph(a)
    private val pre = WoodSlider(a, true).apply { snapCenter = true; centerTick = true }
    private val bands = List(WoodDsp.BANDS) { WoodSlider(a, true).apply { snapCenter = true; centerTick = true } }

    init { build() }

    private fun build() {
        view.setPadding(a.dpi(8f), 0, a.dpi(8f), a.dpi(4f))

        val body = a.vbox()
        val top = a.hbox()
        onSwitch.scaleType = ImageView.ScaleType.FIT_CENTER
        onSwitch.setOnClickListener { setEnabled(!dsp.enabled) }
        top.addView(onSwitch, lp(a.dpi(76f), a.dpi(38f)))
        // One preset field does everything: choose a preset, save / delete / reset, auto-load by genre.
        preset.setOnClickListener { presetMenu(it) }
        top.addView(preset, lp(0, a.dpi(36f), 1f).margins(a, 10f, 0f, 0f, 0f))
        body.addView(top, lp(MATCH, WRAP))

        body.addView(a.framed(graph, raised = false, padDp = 9f), lp(MATCH, a.dpi(70f)).margins(a, 0f, 10f, 0f, 8f))

        // Sliders: dB scale | PRE | 10 bands
        val sliders = a.hbox(Gravity.TOP)
        val scale = FrameLayout(a)
        for ((txt, g) in listOf("+12 dB" to Gravity.TOP, "0 dB" to Gravity.CENTER_VERTICAL, "-12 dB" to Gravity.BOTTOM)) {
            scale.addView(a.text(10f, Colors.IVORY).apply { text = txt },
                FrameLayout.LayoutParams(WRAP, WRAP, g or Gravity.END))
        }
        val scaleCol = a.vbox()
        scaleCol.addView(scale, lp(WRAP, 0, 1f).margins(a, 0f, 6f, 0f, 6f))
        scaleCol.addView(View(a), lp(1, a.dpi(18f)))
        sliders.addView(scaleCol, lp(WRAP, MATCH).margins(a, 0f, 0f, 4f, 0f))

        sliders.addView(column(pre, "PRE"), lp(0, MATCH, 1f))
        sliders.addView(BevelRule(a, vertical = true), lp(WRAP, MATCH).margins(a, 3f, 4f, 3f, 22f))
        bands.forEachIndexed { i, s -> sliders.addView(column(s, WoodDsp.LABELS[i]), lp(0, MATCH, 1f)) }
        body.addView(sliders, lp(MATCH, 0, 1f))

        view.addView(a.framed(body, raised = true, padDp = 14f), lp(MATCH, 0, 1f))

        pre.onChange = { v -> dsp.preampDb = toDb(v); changed(custom = true) }
        pre.onRelease = { Engine.saveDsp(a) }
        bands.forEachIndexed { i, s ->
            s.onChange = { v -> dsp.gainsDb[i] = toDb(v); changed(custom = true) }
            s.onRelease = { Engine.saveDsp(a) }
        }
        preset.text = Engine.prefs(a).getString("eq_preset", "Flat") ?: "Flat"
        syncFromDsp()
    }

    private fun column(s: WoodSlider, label: String): LinearLayout = a.vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(s, lp(WRAP, 0, 1f))
        addView(a.text(11f, Colors.IVORY).apply { text = label; gravity = Gravity.CENTER }, lp(WRAP, a.dpi(18f)).margins(a, 0f, 2f, 0f, 0f))
    }

    private fun toDb(v: Float) = ((v - 0.5f) * 24f * 10f).roundToInt() / 10f
    private fun toPos(db: Float) = db / 24f + 0.5f

    private fun changed(custom: Boolean) {
        dsp.changed()
        graph.invalidate()
        if (custom && preset.text != "Custom") setPresetName("Custom")
    }

    private fun setPresetName(n: String) {
        preset.text = n
        Engine.prefs(a).edit().putString("eq_preset", n).apply()
    }

    private fun syncFromDsp() {
        onSwitch.setImageBitmap(Skin.bmp(a, if (dsp.enabled) R.drawable.switch_on else R.drawable.switch_off))
        pre.value = toPos(dsp.preampDb)
        bands.forEachIndexed { i, s -> s.value = toPos(dsp.gainsDb[i]) }
        graph.invalidate()
    }

    fun setEnabled(on: Boolean) {
        dsp.enabled = on
        Engine.saveDsp(a)
        syncFromDsp()
        a.playerPage.refreshState()
    }

    /** The preset the sliders started from; kept while they read "Custom" so "Save" can overwrite it. */
    private var basePreset: String
        get() = Engine.prefs(a).getString("eq_base", preset.text) ?: preset.text
        set(v) { Engine.prefs(a).edit().putString("eq_base", v).apply() }

    private fun apply(name: String, values: FloatArray) {
        for (i in 0 until WoodDsp.BANDS) dsp.gainsDb[i] = values[i]
        if (name == "Flat") dsp.preampDb = 0f   // "Flat" means no change to the sound at all
        dsp.changed()
        Engine.saveDsp(a)
        setPresetName(name)
        basePreset = name
        syncFromDsp()
    }

    private val autoOn get() = Engine.prefs(a).getBoolean("eq_auto", false)

    /** Auto-load: when a song starts, load the preset named like its genre (e.g. "Rock"). */
    fun autoLoad(genre: String) {
        if (!autoOn || genre.isBlank()) return
        val all = Engine.PRESETS + Engine.customPresets(a)
        val hit = all.entries.firstOrNull { it.key.equals(genre.trim(), ignoreCase = true) } ?: return
        apply(hit.key, hit.value)
    }

    /**
     * Menu order: Auto-load toggle ═ then, once the sliders/preamp were changed ("Custom"), "Save" (only when they
     * started from one of your own presets) and "Save as…", then your presets A–Z (+ "Delete…") ═ built-in presets A–Z.
     */
    private fun presetMenu(anchor: View) {
        val custom = Engine.customPresets(a)
        val all = LinkedHashMap(Engine.PRESETS).apply { putAll(custom) }
        val current = preset.text
        val modified = current == "Custom"
        val base = basePreset
        val saveOver = "Save \"$base\""; val saveAs = "Save as…"; val delete = "Delete…"
        val auto = "Auto-load by genre: " + if (autoOn) "ON" else "OFF"
        val tick = { n: String -> if (n == current) "✓ $n" else n }
        val mine = buildList {
            if (modified && base in custom) add(saveOver)
            if (modified) add(saveAs)
            custom.keys.sortedBy { it.lowercase() }.forEach { add(tick(it)) }
            if (custom.isNotEmpty()) add(delete)
        }
        val builtIn = Engine.PRESETS.keys.sortedBy { it.lowercase() }.map(tick)
        val items = listOf(auto, WOOD_MENU_BREAK) + mine + (if (mine.isEmpty()) emptyList() else listOf(WOOD_MENU_BREAK)) + builtIn
        a.woodMenu(anchor, items) { choice ->
            when (choice) {
                saveOver -> {
                    custom[base] = dsp.gainsDb.copyOf(); Engine.saveCustomPresets(a, custom); setPresetName(base)
                    a.toast("Saved \"$base\"")
                }
                saveAs -> a.askText("Save preset", "") { n ->
                    custom[n] = dsp.gainsDb.copyOf(); Engine.saveCustomPresets(a, custom); setPresetName(n); basePreset = n
                }
                delete -> {
                    val n = custom.keys.toList()
                    a.pick("Delete preset", n) { i ->
                        a.confirm("Delete preset", "Delete your preset \"${n[i]}\"? This can't be undone.", "DELETE") {
                            custom.remove(n[i]); Engine.saveCustomPresets(a, custom)
                            if (preset.text == n[i]) setPresetName("Custom")
                        }
                    }
                }
                auto -> Engine.prefs(a).edit().putBoolean("eq_auto", !autoOn).apply()
                else -> choice.removePrefix("✓ ").let { n -> all[n]?.let { apply(n, it) } }
            }
        }
    }
}
