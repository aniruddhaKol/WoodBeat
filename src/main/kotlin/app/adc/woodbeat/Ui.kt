package app.adc.woodbeat

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

fun Context.askText(title: String, initial: String = "", hint: String = "", url: Boolean = false, ok: (String) -> Unit) {
    val e = EditText(this).apply {
        setText(initial); this.hint = hint; isSingleLine = true
        inputType = if (url) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
        setSelection(text.length)
    }
    val box = FrameLayout(this).apply { setPadding(dpi(20f), dpi(8f), dpi(20f), 0); addView(e) }
    dialog().setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
        .setNegativeButton("Cancel", null).show()
}

fun Context.askSearch(ok: (String, Boolean) -> Unit) {
    val e = EditText(this).apply { hint = "Station, city or genre (e.g. Kolkata)"; isSingleLine = true }
    val c = CheckBox(this).apply { text = "India only"; isChecked = true }
    val box = vbox().apply { setPadding(dpi(20f), dpi(8f), dpi(20f), 0); addView(e); addView(c) }
    dialog().setTitle("Search radio-browser.info").setView(box)
        .setPositiveButton("Search") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ok(it, c.isChecked) } }
        .setNegativeButton("Cancel", null).show()
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
    popup = android.widget.PopupWindow(frame, w, minOf(frame.measuredHeight, maxH), true).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        elevation = dp(12f)
    }
    if (anchor == null) popup.showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
    else popup.showAsDropDown(anchor, 0, -dpi(4f))
}

fun Context.info(title: String, body: String) {
    dialog().setTitle(title).setMessage(body).setPositiveButton("OK", null).show()
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
