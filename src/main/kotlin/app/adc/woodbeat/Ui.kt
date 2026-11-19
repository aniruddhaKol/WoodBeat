package app.adc.woodbeat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(ctx: Context, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) = apply {
    setMargins(ctx.dpi(l), ctx.dpi(t), ctx.dpi(r), ctx.dpi(b))
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.text(sizeSp: Float, color: Int, mono: Boolean = false, bold: Boolean = false) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = if (mono) MONO_FONT else if (bold) LABEL_FONT else typeface
    includeFontPadding = false
    isSingleLine = true
    ellipsize = TextUtils.TruncateAt.END
}

/** Fixed-size text in dp (ignores the phone's font-size setting) — for LCD read-outs. */
fun TextView.lcdSize(dp: Float): TextView = apply { setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp) }

const val KBPS_DP = 11f

/** Shrinks a one-line text view (down to minFraction of baseDp) until its text fits its width. */
fun fitText(tv: TextView, baseDp: Float, minFraction: Float = 0.7f) {
    val room = tv.width - tv.paddingLeft - tv.paddingRight
    if (room <= 0) return
    val p = android.text.TextPaint(tv.paint)
    var size = baseDp
    while (size > baseDp * minFraction) {
        p.textSize = size * tv.resources.displayMetrics.density
        if (p.measureText(tv.text.toString()) <= room) break
        size -= 0.5f
    }
    val px = size * tv.resources.displayMetrics.density
    if (kotlin.math.abs(tv.textSize - px) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
}

/** Wooden frame around a child (raised panel or dark inset). */
fun Context.framed(child: View, raised: Boolean, padDp: Float): FrameLayout = FrameLayout(this).apply {
    background = if (raised) Skin.raised(this@framed) else Skin.inset(this@framed)
    val p = dpi(padDp)
    setPadding(p, p, p, p)
    addView(child, FrameLayout.LayoutParams(MATCH, MATCH))
}

fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Wooden dialog: raised frame, copper title, carved divider, body, plaque buttons. Always centred on screen
 * (Samsung puts standard dialogs at the bottom). positive returns false to keep the dialog open.
 */
fun Context.woodDialog(
    title: String,
    body: View,
    positive: String,
    negative: String? = "CANCEL",
    keyboard: Boolean = false,
    titleExtra: View? = null,            // optional control on the right of the title row (e.g. a switch)
    subtitle: String? = null,            // optional small second title line (e.g. the data source)
    onPositive: () -> Boolean,
): android.app.Dialog {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val col = vbox()
    // Title row: left-aligned title (wraps if needed); with an extra control, each side gets at most half the width
    val titleText = android.text.SpannableStringBuilder(title.uppercase())
    subtitle?.let {
        val start = titleText.length + 1
        titleText.append("\n").append(it.uppercase())
        titleText.setSpan(android.text.style.RelativeSizeSpan(0.6f), start, titleText.length, 0)
        titleText.setSpan(android.text.style.ForegroundColorSpan(Colors.IVORY_DIM), start, titleText.length, 0)
    }
    val titleView = text(14f, Colors.COPPER, bold = true).lcdSize(14f).apply {
        text = titleText; letterSpacing = 0.1f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isSingleLine = false; maxLines = 3; ellipsize = null
    }
    val titleRow = hbox(Gravity.CENTER_VERTICAL)
    if (titleExtra == null) titleRow.addView(titleView, lp(0, WRAP, 1f))
    else {
        titleRow.addView(titleView, lp(0, WRAP, 1f))
        titleRow.addView(FrameLayout(this).apply {
            addView(titleExtra, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        }, lp(0, WRAP, 1f).margins(this, 8f, 0f, 0f, 0f))
    }
    col.addView(titleRow, lp(MATCH, WRAP).margins(this, 4f, 2f, 4f, 10f))
    col.addView(BevelRule(this), lp(MATCH, WRAP))
    col.addView(body, lp(MATCH, WRAP).margins(this, 4f, 14f, 4f, 16f))
    val row = hbox(Gravity.END or Gravity.CENTER_VERTICAL)
    // Cancel is the secondary action: same plaque, dimmed caption
    negative?.let { n -> row.addView(Plaque(this, n, 12f).apply { secondary = true; setOnClickListener { d.dismiss() } },
        lp(WRAP, dpi(36f)).margins(this, 0f, 0f, 8f, 0f)) }
    val submit = { if (onPositive()) d.dismiss() }
    row.addView(Plaque(this, positive, 12f).apply { setOnClickListener { submit() } }, lp(WRAP, dpi(36f)))
    col.addView(row, lp(MATCH, WRAP))
    // Enter / Done on the keyboard does the same as the main button
    (body as? EditText)?.setOnEditorActionListener { _, id, ev ->
        val enter = id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
            (ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN)
        if (enter) submit()
        enter
    }
    d.setContentView(framed(col, raised = true, padDp = 18f))
    d.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        setGravity(Gravity.CENTER)
        val w = minOf((resources.displayMetrics.widthPixels * 0.9f).toInt(), dpi(440f))
        setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(
            (if (keyboard) android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0) or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }
    d.show()
    return d
}

/** Text box in the app's style: dark inset with ivory text. */
fun Context.woodField(initial: String, hint: String, url: Boolean): EditText = EditText(this).apply {
    setText(initial); this.hint = hint; isSingleLine = true
    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    inputType = if (url) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
    setTextColor(Colors.IVORY); setHintTextColor(Colors.IVORY_DIM)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    background = Skin.inset(this@woodField)
    setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
    highlightColor = 0x66D98A4E
    if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable =
        android.graphics.drawable.GradientDrawable().apply { setColor(Colors.COPPER); setSize(dpi(2f), dpi(20f)) }
    setSelection(text.length)
}

fun Context.askText(title: String, initial: String = "", hint: String = "", url: Boolean = false, ok: (String) -> Unit) {
    val e = woodField(initial, hint, url)
    woodDialog(title, e, "OK", keyboard = true) {
        val v = e.text.toString().trim()
        if (v.isNotEmpty()) ok(v)
        true
    }
    e.requestFocus()
}

fun Context.askSearch(ok: (String, Boolean) -> Unit) {
    val e = woodField("", "Station, city or genre (e.g. Kolkata)", url = false)
    // "India only": label + the Equalizer's ON/OFF switch, right-aligned in the title row
    var india = true
    val sw = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        contentDescription = "India only"
        setImageBitmap(Skin.bmp(this@askSearch, R.drawable.switch_on))
        setOnClickListener {
            india = !india
            setImageBitmap(Skin.bmp(this@askSearch, if (india) R.drawable.switch_on else R.drawable.switch_off))
        }
    }
    val extra = hbox(Gravity.END or Gravity.CENTER_VERTICAL).apply {
        addView(text(12f, Colors.IVORY, bold = true).lcdSize(12f).apply { text = "India only" },
            lp(WRAP, WRAP).margins(this@askSearch, 0f, 0f, 6f, 0f))
        addView(sw, lp(dpi(60f), dpi(30f)))
    }
    woodDialog("Search", e, "SEARCH", keyboard = true, titleExtra = extra, subtitle = "radio-browser.info") {
        val v = e.text.toString().trim()
        if (v.isNotEmpty()) ok(v, india)
        true
    }
    e.requestFocus()
}

/** Asks before something that can't be undone. */
fun Context.confirm(title: String, message: String, action: String, yes: () -> Unit) {
    val msg = text(15f, Colors.IVORY).apply { text = message; isSingleLine = false; maxLines = 6 }
    woodDialog(title, msg, action) { yes(); true }
}

/** List to choose from (presets, playlists): a wooden menu in the middle of the screen. */
fun Context.pick(title: String, items: List<String>, chosen: (Int) -> Unit) {
    if (items.isEmpty()) return
    woodMenu(null, items, title) { chosen(items.indexOf(it)) }
}

/** Put this between items of a woodMenu to start a new section. */
const val WOOD_MENU_BREAK = "\u0000break"

/** The carved divider used everywhere: a 2 dp dark cut with a 1 dp copper lip below (or to the right). */
object Carve {
    val dark = Paint().apply { color = 0xFF050201.toInt() }
    val light = Paint().apply { color = 0xD9B9773F.toInt() }

    /** Horizontal carve with its top edge at y. */
    fun h(c: Canvas, ctx: Context, left: Float, right: Float, y: Float) {
        c.drawRect(left, y, right, y + ctx.dp(2f), dark)
        c.drawRect(left, y + ctx.dp(2f), right, y + ctx.dp(3f), light)
    }

    /** Vertical carve with its left edge at x. */
    fun v(c: Canvas, ctx: Context, x: Float, top: Float, bottom: Float) {
        c.drawRect(x, top, x + ctx.dp(2f), bottom, dark)
        c.drawRect(x + ctx.dp(2f), top, x + ctx.dp(3f), bottom, light)
    }
}

/** Carved divider as a view (menus, EQ PRE separator). */
class BevelRule(ctx: Context, private val vertical: Boolean = false) : View(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (vertical) setMeasuredDimension(context.dpi(3f), MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(3f))
    }
    override fun onDraw(c: Canvas) {
        if (vertical) Carve.v(c, context, 0f, 0f, height.toFloat())
        else Carve.h(c, context, 0f, width.toFloat(), 0f)
    }
}

/**
 * Wooden pop-up menu (raised frame, ivory captions, carved dividers).
 * anchor = null shows it centred with an optional title.
 */
fun Context.woodMenu(anchor: View?, items: List<String>, title: String? = null, act: (String) -> Unit) {
    val activity = this as? android.app.Activity ?: return
    val list = vbox()
    lateinit var popup: android.widget.PopupWindow
    title?.let {
        list.addView(text(13f, Colors.COPPER, bold = true).apply {
            text = it.uppercase(); letterSpacing = 0.15f; gravity = Gravity.CENTER
            setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(10f))
        }, lp(MATCH, WRAP))
        list.addView(BevelRule(this), lp(MATCH, WRAP))
    }
    var afterBreak = false
    items.forEachIndexed { i, item ->
        if (item == WOOD_MENU_BREAK) {
            // Section break: a deeper double groove with some air around it.
            list.addView(BevelRule(this), lp(MATCH, WRAP).margins(this, 0f, 4f, 0f, 2f))
            list.addView(BevelRule(this), lp(MATCH, WRAP).margins(this, 0f, 0f, 0f, 4f))
            afterBreak = true
            return@forEachIndexed
        }
        if (i > 0 && !afterBreak) list.addView(BevelRule(this), lp(MATCH, WRAP))
        afterBreak = false
        list.addView(text(15f, Colors.IVORY, bold = true).apply {
            text = item
            letterSpacing = 0.04f
            setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x55D98A4E))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            setOnClickListener { popup.dismiss(); act(item) }
        }, lp(MATCH, WRAP))
    }
    val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    val frame = framed(scroll, raised = true, padDp = 11f)
    val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
    // Width = widest caption (measured unconstrained) + frame padding — snug, between 110 dp and 85 % of the screen.
    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val w = (list.measuredWidth + frame.paddingLeft + frame.paddingRight)
        .coerceIn(dpi(110f), (resources.displayMetrics.widthPixels * 0.85f).toInt())
    frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    val h = minOf(frame.measuredHeight, maxH)
    popup = android.widget.PopupWindow(frame, w, h, true).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        elevation = dp(12f)
    }
    val decor = activity.window.decorView
    if (anchor == null) { popup.showAtLocation(decor, Gravity.CENTER, 0, 0); return }

    // Place it ourselves so it never touches the screen edge: keep a margin that stays inside the app's
    // panels (page padding 8 dp + frame rim). Menus from right-hand buttons line up with the button's right edge.
    val area = android.graphics.Rect().also { decor.getWindowVisibleDisplayFrame(it) }
    val margin = dpi(16f)
    val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
    val anchorLeft = loc[0]; val anchorRight = loc[0] + anchor.width
    val rightHalf = anchorLeft + anchor.width / 2 > area.centerX()
    val x = (if (rightHalf) anchorRight - w else anchorLeft).coerceIn(area.left + margin, area.right - margin - w)
    val below = loc[1] + anchor.height - dpi(4f)
    val y = if (below + h <= area.bottom - margin) below else (loc[1] - h + dpi(4f)).coerceAtLeast(area.top + margin)
    popup.showAtLocation(decor, Gravity.NO_GRAVITY, x, y)
}

fun Context.info(title: String, body: String) {
    val msg = text(14f, Colors.IVORY).apply { text = body; isSingleLine = false; maxLines = 20; setTextIsSelectable(true) }
    woodDialog(title, msg, "OK", negative = null) { true }
}

/** Thin dark lines between list rows. */
class RowLines(private val ctx: Context) : RecyclerView.ItemDecoration() {
    // Carved line under each row; drawn over the rows so the playing/selected highlight doesn't hide it.
    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            Carve.h(c, ctx, v.left.toFloat(), v.right.toFloat(), v.bottom + v.translationY - ctx.dp(1.5f))
        }
    }
}
