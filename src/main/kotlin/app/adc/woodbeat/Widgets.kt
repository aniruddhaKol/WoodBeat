package app.adc.woodbeat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- basics

object Colors {
    const val LCD = 0xFF3FE03F.toInt()
    const val LCD_BRIGHT = 0xFF9CFF7A.toInt()
    const val LCD_DIM = 0xFF163A16.toInt()
    const val LCD_GHOST = 0xFF0E220E.toInt()
    const val IVORY = 0xFFF2D7A6.toInt()
    const val IVORY_DIM = 0xFF9C7A55.toInt()
    const val COPPER = 0xFFD98A4E.toInt()
    const val YELLOW = 0xFFE8D42A.toInt()
    const val ORANGE = 0xFFF08A24.toInt()
    const val ROW_PLAYING = 0xFF1E4A12.toInt()
    const val ROW_SELECTED = 0xFF4A2A12.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).roundToInt()
fun View.dp(v: Float): Float = context.dp(v)

val LABEL_FONT: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
val MONO_FONT: Typeface = Typeface.MONOSPACE

object Skin {
    private val cache = HashMap<Int, Bitmap>()
    fun bmp(ctx: Context, id: Int): Bitmap = cache.getOrPut(id) {
        BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }

    fun wood(ctx: Context): Drawable = BitmapDrawable(ctx.resources, bmp(ctx, R.drawable.wood_tile)).apply {
        setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun raised(ctx: Context) = NineSlice(bmp(ctx, R.drawable.frame_raised), 48, 48, 48, 48, ctx.dp(16f), ctx.dp(16f))
    fun inset(ctx: Context) = NineSlice(bmp(ctx, R.drawable.frame_inset), 40, 40, 40, 40, ctx.dp(13f), ctx.dp(13f))
    fun plaque(ctx: Context, pressed: Boolean) = NineSlice(
        bmp(ctx, if (pressed) R.drawable.plaque_pressed else R.drawable.plaque), 26, 22, 26, 22, ctx.dp(9f), ctx.dp(7.5f),
    )
}

/** Stretchable image: corners keep their size, edges and centre stretch. */
class NineSlice(
    private val bmp: Bitmap,
    private val sl: Int, private val st: Int, private val sr: Int, private val sb: Int,
    private val dx: Float, private val dy: Float,
    private val dxRight: Float = dx,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = bmp.width; val h = bmp.height
        val xs = intArrayOf(0, sl, w - sr, w)
        val ys = intArrayOf(0, st, h - sb, h)
        val l = min(dx, b.width() / 2f); val r = min(dxRight, b.width() / 2f)
        val t = if (st == 0) 0f else min(dy, b.height() / 2f)
        val bo = if (sb == 0) 0f else min(dy, b.height() / 2f)
        val xd = floatArrayOf(b.left.toFloat(), b.left + l, b.right - r, b.right.toFloat())
        val yd = floatArrayOf(b.top.toFloat(), b.top + t, b.bottom - bo, b.bottom.toFloat())
        for (i in 0..2) for (j in 0..2) {
            src.set(xs[i], ys[j], xs[i + 1], ys[j + 1])
            if (src.width() <= 0 || src.height() <= 0) continue
            dst.set(xd[i], yd[j], xd[i + 1], yd[j + 1])
            if (dst.width() <= 0f || dst.height() <= 0f) continue
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------- buttons

/** Square wooden button with a glyph. Toggle buttons are "lit" (bright wood, full glyph) or dim. */
@SuppressLint("ViewConstructor")
class WoodButton(ctx: Context, glyph: Int, private val square: Boolean = true) : View(ctx) {
    private val normal = Skin.bmp(ctx, R.drawable.button)
    private val pressedBmp = Skin.bmp(ctx, R.drawable.button_pressed)
    private var icon = Skin.bmp(ctx, glyph)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.IVORY; typeface = LABEL_FONT; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    var toggle = false
    var lit = false
        set(v) { field = v; invalidate() }
    var badgeText: String? = null
        set(v) { field = v; invalidate() }

    init { isClickable = true; isFocusable = true }

    fun setGlyph(id: Int) { icon = Skin.bmp(context, id); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (square) {
            val s = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) measuredWidth else measuredHeight
            setMeasuredDimension(s, s)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val left = (width - s) / 2f; val top = (height - s) / 2f
        rect.set(left, top, left + s, top + s)
        paint.alpha = if (isEnabled) 255 else 120
        canvas.drawBitmap(if (isPressed || (toggle && lit && isEnabled)) pressedBmp else normal, null, rect, paint)
        val g = s * 0.56f
        rect.set(width / 2f - g / 2, height / 2f - g / 2 + if (isPressed) s * 0.015f else 0f, width / 2f + g / 2, height / 2f + g / 2)
        paint.alpha = if (!isEnabled) 90 else if (toggle && !lit) 110 else 255
        canvas.drawBitmap(icon, null, rect, paint)
        badgeText?.let {
            badge.textSize = s * 0.22f
            canvas.drawText(it, width / 2f, height / 2f + s * 0.09f, badge)
        }
        if (toggle) {
            // Winamp-style toggle light: small square at the top-left of the button face
            val l = s * 0.13f
            val x = left + s * 0.17f; val y = top + s * 0.17f
            rect.set(x, y, x + l, y + l)
            val on = lit && isEnabled                              // disabled = light off, whatever the setting
            led.color = if (on) Colors.LCD_BRIGHT else 0xFF2A4A22.toInt()
            led.setShadowLayer(if (on) s * 0.06f else 0.01f, 0f, 0f, if (on) 0xCC9CFF7A.toInt() else 0)
            led.alpha = if (isEnabled) 255 else 110
            ledEdge.alpha = if (isEnabled) 255 else 90
            canvas.drawRoundRect(rect, l * 0.15f, l * 0.15f, led)
            ledEdge.strokeWidth = maxOf(dp(1f), s * 0.018f)        // ivory frame so the light stands out on the wood
            canvas.drawRoundRect(rect, l * 0.15f, l * 0.15f, ledEdge)
        }
    }

    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Colors.IVORY }
}

/** Ready-made square icon button art (back, search, URL). Long-press shows its name. */
@SuppressLint("ViewConstructor")
class IconButton(ctx: Context, icon: Int, label: String, pressedIcon: Int = 0) : View(ctx) {
    private val bmp = Skin.bmp(ctx, icon)
    private val pressedBmp = if (pressedIcon != 0) Skin.bmp(ctx, pressedIcon) else null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pressedTint = android.graphics.PorterDuffColorFilter(0x40000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
    private val rect = RectF()

    init {
        isClickable = true; isFocusable = true
        contentDescription = label
        tooltipText = label
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        rect.set((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        paint.alpha = if (isEnabled) 255 else 110
        if (isPressed && pressedBmp != null) {          // supplied pressed art (lit wood)
            paint.colorFilter = null
            canvas.drawBitmap(pressedBmp, null, rect, paint)
        } else {                                         // otherwise: sink a little and darken
            val off = if (isPressed) s * 0.03f else 0f
            rect.offset(off, off)
            paint.colorFilter = if (isPressed) pressedTint else null
            canvas.drawBitmap(bmp, null, rect, paint)
        }
    }
}

/** Wooden plaque with a caption: buttons, toggles, tabs, section titles. */
@SuppressLint("ViewConstructor")
class Plaque(ctx: Context, text: String, private val sizeSp: Float = 13f) : View(ctx) {
    private val up = Skin.plaque(ctx, false)
    private val down = Skin.plaque(ctx, true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = LABEL_FONT; textAlign = Paint.Align.CENTER; letterSpacing = 0.16f
    }
    private val chevron = Skin.bmp(ctx, R.drawable.glyph_chevron)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    var text: String = text
        set(v) { field = v; requestLayout(); invalidate() }
    var toggle = false
    var lit = false
        set(v) { field = v; invalidate() }
    var dropDown = false
    /** Secondary action (e.g. CANCEL): dimmed caption so the main button stands out. */
    var secondary = false
        set(v) { field = v; invalidate() }
    /** Winamp-style toggle light at the left of the caption (same look as on shuffle / repeat). */
    var led = false
    private val ledFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Colors.IVORY }

    init { isClickable = true; isFocusable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        val w = paint.measureText(text) + dp(26f) + if (dropDown) dp(22f) else 0f
        val h = dp(34f)
        setMeasuredDimension(resolveSize(w.roundToInt(), widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val d = if (isPressed || (toggle && lit)) down else up
        d.setBounds(0, 0, width, height)
        d.alpha = if (isEnabled) 255 else 130
        d.draw(canvas)
        paint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        paint.color = when {
            !isEnabled -> Colors.IVORY_DIM
            toggle && lit -> Colors.LCD_BRIGHT
            toggle -> Colors.IVORY_DIM
            secondary -> Colors.IVORY_DIM
            else -> Colors.IVORY
        }
        var cx = width / 2f
        if (led) {
            val l = height * 0.26f
            val x = dp(9f); val y = (height - l) / 2f
            val r = RectF(x, y, x + l, y + l)
            ledFill.color = if (lit) Colors.LCD_BRIGHT else 0xFF2A4A22.toInt()
            ledFill.setShadowLayer(if (lit) l * 0.45f else 0.01f, 0f, 0f, if (lit) 0xCC9CFF7A.toInt() else 0)
            canvas.drawRoundRect(r, l * 0.15f, l * 0.15f, ledFill)
            ledEdge.strokeWidth = dp(1f)
            canvas.drawRoundRect(r, l * 0.15f, l * 0.15f, ledEdge)
            cx = (x + l + width) / 2f                              // caption centred in the space right of the light
        }
        if (dropDown) {
            cx -= dp(9f)
            val s = dp(16f)
            canvas.save()
            canvas.rotate(90f, width - dp(18f), height / 2f)
            canvas.drawBitmap(chevron, null, RectF(width - dp(18f) - s / 2, height / 2f - s / 2, width - dp(18f) + s / 2, height / 2f + s / 2), bmpPaint)
            canvas.restore()
        }
        // Shrink long captions to fit (down to 65 %), then shorten with "…" if still too long.
        val room = width - dp(20f) - if (dropDown) dp(30f) else 0f
        var caption = text
        val full = paint.measureText(caption)
        if (full > room && room > 0) {
            paint.textSize *= maxOf(0.65f, room / full)
            if (paint.measureText(caption) > room) {
                while (caption.length > 1 && paint.measureText("$caption…") > room) caption = caption.dropLast(1)
                caption = caption.trimEnd() + "…"
            }
        }
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(caption, cx, y, paint)
    }
}

/** Ornament — plaque — ornament, as in the mock-up's section titles. */
@SuppressLint("ViewConstructor")
class SectionHeader(ctx: Context, title: String) : View(ctx) {
    private val orn = NineSlice(Skin.bmp(ctx, R.drawable.ornament), 178, 0, 10, 0, ctx.dp(52f), 0f, ctx.dp(2f))
    private val plaque = Skin.plaque(ctx, false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = LABEL_FONT; textAlign = Paint.Align.CENTER; letterSpacing = 0.3f; color = Colors.IVORY
        textSize = ctx.dp(15f)
    }
    var title = title
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(38f).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pw = paint.measureText(title) + dp(48f)
        val pl = (width - pw) / 2f
        val oh = height * 0.62f
        val oy = (height - oh) / 2f
        orn.setBounds(0, oy.toInt(), (pl - dp(2f)).toInt(), (oy + oh).toInt())
        orn.draw(canvas)
        canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f)
        orn.draw(canvas)
        canvas.restore()
        plaque.setBounds(pl.toInt(), dp(2f).toInt(), (pl + pw).toInt(), height - dp(2f).toInt())
        plaque.draw(canvas)
        canvas.drawText(title, width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    }
}

// ---------------------------------------------------------------- sliders

/** Wooden slider (seek, volume, balance, EQ). value 0..1; vertical sliders have 1 at the top. */
class WoodSlider(ctx: Context, private val vertical: Boolean) : View(ctx) {
    private val track = NineSlice(Skin.bmp(ctx, R.drawable.track), 16, 0, 16, 0, ctx.dp(5f), 0f)
    private val thumb = Skin.bmp(ctx, R.drawable.thumb)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.COPPER }
    private val tick = Paint().apply { color = 0x55F2D7A6 }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF120703.toInt() }
    private val slotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAB06A38.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.dp(1f)
    }
    private val rect = RectF()
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var dragging = false
        private set
    var snapCenter = false
    var showFill = false
    var centerTick = false
    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val thick = dp(30f).roundToInt()
        if (vertical) setMeasuredDimension(resolveSize(thick, widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(thick, heightMeasureSpec))
    }

    private fun len() = (if (vertical) height else width).toFloat()
    private fun thick() = (if (vertical) width else height).toFloat()
    private fun thumbLong() = min(thick(), dp(30f))
    private fun thumbShort() = thumbLong() * 0.42f

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        if (vertical) { canvas.translate(0f, height.toFloat()); canvas.rotate(-90f) }
        val l = len(); val t = thick()
        val pad = thumbShort() / 2 + dp(2f)
        val cy = t / 2
        val th = dp(if (vertical) 16f else 12f) // the track art is a thin groove inside a taller image
        track.setBounds(pad.toInt() - dp(3f).toInt(), (cy - th / 2).toInt(), (l - pad).toInt() + dp(3f).toInt(), (cy + th / 2).toInt())
        // Carved slot behind the groove art so the track reads clearly on wood.
        val slot = dp(if (vertical) 6f else 5f)
        rect.set(pad - dp(2f), cy - slot / 2, l - pad + dp(2f), cy + slot / 2)
        canvas.drawRoundRect(rect, slot / 2, slot / 2, slotFill)
        canvas.drawRoundRect(rect, slot / 2, slot / 2, slotEdge)
        track.alpha = if (isEnabled) 255 else 110
        track.draw(canvas)
        val x = pad + value * (l - 2 * pad)
        if (showFill && isEnabled) canvas.drawRect(pad, cy - dp(1.2f), x, cy + dp(1.2f), fill)
        if (centerTick) canvas.drawRect(l / 2 - dp(0.8f), cy - th, l / 2 + dp(0.8f), cy + th, tick)
        if (isEnabled) {
            val tw = thumbShort(); val tl = thumbLong()
            rect.set(x - tw / 2, cy - tl / 2, x + tw / 2, cy + tl / 2)
            canvas.drawBitmap(thumb, null, rect, paint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; track(e) }
            MotionEvent.ACTION_MOVE -> track(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(e); dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(value)
            }
        }
        return true
    }

    private fun track(e: MotionEvent) {
        val pad = thumbShort() / 2 + dp(2f)
        val pos = if (vertical) height - e.y else e.x
        var v = ((pos - pad) / (len() - 2 * pad)).coerceIn(0f, 1f)
        if (snapCenter && kotlin.math.abs(v - 0.5f) < 0.04f) v = 0.5f
        value = v
        onChange?.invoke(value)
    }
}

// ---------------------------------------------------------------- LCD pieces

/** Seven-segment time with play-state indicator; tap toggles elapsed / remaining. */
class LcdTime(ctx: Context) : View(ctx) {
    enum class State { PLAY, PAUSE, STOP }
    var state = State.STOP
    var seconds = 0L
    var remaining = false
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.LCD }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.LCD_GHOST }
    private val path = Path()
    private val r = RectF()

    // segments: a b c d e f g
    private val digits = arrayOf(
        "1111110", "0110000", "1101101", "1111001", "0110011", "1011011", "1011111", "1110000", "1111111", "1111011",
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(38f)
        setMeasuredDimension(resolveSize((h * 3.55f).roundToInt(), widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    fun update(state: State, seconds: Long, remainingMode: Boolean) {
        this.state = state; this.seconds = seconds; this.remaining = remainingMode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val dh = h * 0.86f
        val top = (h - dh) / 2
        // state indicator
        val ix = dp(2f); val iw = h * 0.36f; val ic = h / 2
        path.reset()
        when (state) {
            State.PLAY -> { path.moveTo(ix, ic - iw / 2); path.lineTo(ix + iw * 0.85f, ic); path.lineTo(ix, ic + iw / 2); path.close(); canvas.drawPath(path, on) }
            State.PAUSE -> { canvas.drawRect(ix, ic - iw / 2, ix + iw * 0.3f, ic + iw / 2, on); canvas.drawRect(ix + iw * 0.55f, ic - iw / 2, ix + iw * 0.85f, ic + iw / 2, on) }
            State.STOP -> canvas.drawRect(ix, ic - iw * 0.4f, ix + iw * 0.8f, ic + iw * 0.4f, on)
        }
        val blinkOff = state == State.PAUSE && (SystemClock.uptimeMillis() / 500) % 2 == 0L
        val s = seconds.coerceAtLeast(0)
        val mm = (s / 60).coerceAtMost(99); val ss = s % 60
        var x = ix + iw + h * 0.12f
        val dw = dh * 0.52f
        val gap = dh * 0.14f
        // minus sign slot
        drawSeg(canvas, x, top, dw * 0.55f, dh, "0000001", remaining && !blinkOff)
        x += dw * 0.55f + gap
        for ((i, d) in listOf((mm / 10).toInt(), (mm % 10).toInt(), (ss / 10).toInt(), (ss % 10).toInt()).withIndex()) {
            if (i == 2) {
                val c = dh * 0.09f
                val p = if (blinkOff) ghost else on
                canvas.drawRect(x, top + dh * 0.3f, x + c, top + dh * 0.3f + c, p)
                canvas.drawRect(x, top + dh * 0.66f, x + c, top + dh * 0.66f + c, p)
                x += c + gap
            }
            drawSeg(canvas, x, top, dw, dh, if (blinkOff) "0000000" else digits[d], true)
            x += dw + gap
        }
        if (state == State.PAUSE) postInvalidateDelayed(250)
    }

    private fun drawSeg(c: Canvas, x: Float, y: Float, w: Float, h: Float, segs: String, show: Boolean) {
        val t = h * 0.11f
        val g = t * 0.25f
        val mid = y + h / 2
        val rects = arrayOf(
            floatArrayOf(x + g + t / 2, y, x + w - g - t / 2, y + t),                 // a
            floatArrayOf(x + w - t, y + g + t / 2, x + w, mid - g),                   // b
            floatArrayOf(x + w - t, mid + g, x + w, y + h - g - t / 2),               // c
            floatArrayOf(x + g + t / 2, y + h - t, x + w - g - t / 2, y + h),         // d
            floatArrayOf(x, mid + g, x + t, y + h - g - t / 2),                       // e
            floatArrayOf(x, y + g + t / 2, x + t, mid - g),                           // f
            floatArrayOf(x + g + t / 2, mid - t / 2, x + w - g - t / 2, mid + t / 2), // g
        )
        for (i in 0 until 7) {
            r.set(rects[i][0], rects[i][1], rects[i][2], rects[i][3])
            c.drawRoundRect(r, t / 2, t / 2, if (show && segs[i] == '1') on else ghost)
        }
    }
}

/** Spectrum analyser / oscilloscope; tap cycles spectrum → oscilloscope → off. */
class Spectrum(ctx: Context) : View(ctx) {
    var mode = 0
        set(v) { field = v; invalidate() }
    var playing = false
        set(v) { field = v; invalidate() }
    private val shown = FloatArray(WoodDsp.BARS)
    private val peaks = FloatArray(WoodDsp.BARS)
    private val peakAge = IntArray(WoodDsp.BARS)
    private val p = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.LCD; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.6f) }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            0 -> drawBars(canvas)
            1 -> drawWave(canvas)
        }
    }

    private fun drawBars(canvas: Canvas) {
        val bars = Engine.dsp.bars
        val n = WoodDsp.BARS
        val gapX = dp(3f)
        val bw = (width - gapX * (n - 1)) / n
        val block = dp(3.2f); val gapY = dp(1.6f)
        val rows = max(1, ((height + gapY) / (block + gapY)).toInt())
        var moving = false
        for (i in 0 until n) {
            val target = if (playing) bars[i] else 0f
            shown[i] = max(target, shown[i] - 0.03f)
            if (shown[i] >= peaks[i]) { peaks[i] = shown[i]; peakAge[i] = 0 } else if (++peakAge[i] > 18) peaks[i] = max(0f, peaks[i] - 0.015f)
            if (shown[i] > 0f || peaks[i] > 0f) moving = true
            val lit = (shown[i] * rows).roundToInt()
            val peakRow = (peaks[i] * rows).roundToInt() - 1
            val x = i * (bw + gapX)
            for (r in 0 until rows) {
                val y = height - (r + 1) * (block + gapY) + gapY
                val f = r / rows.toFloat()
                p.color = when {
                    r < lit -> if (f < 0.6f) Colors.LCD else if (f < 0.85f) Colors.YELLOW else Colors.ORANGE
                    r == peakRow && peakRow > 0 -> 0xFFB8B8B8.toInt()
                    else -> Colors.LCD_GHOST
                }
                canvas.drawRect(x, y, x + bw, y + block, p)
            }
        }
        if (playing || moving) postInvalidateOnAnimation()
    }

    private fun drawWave(canvas: Canvas) {
        val w = Engine.dsp.wave
        path.reset()
        val mid = height / 2f
        for (i in w.indices) {
            val x = i * width / (w.size - 1f)
            val y = mid - (if (playing) w[i] else 0f) * height * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        if (playing) postInvalidateOnAnimation()
    }
}

/** Small EQ response curve. */
class EqGraph(ctx: Context) : View(ctx) {
    private val grid = Paint().apply { color = Colors.LCD_GHOST; strokeWidth = ctx.dp(1f) }
    private val pre = Paint().apply { color = 0x88C8A050.toInt(); strokeWidth = ctx.dp(1f) }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f) }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        curve.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Colors.ORANGE, Colors.YELLOW, Colors.LCD, Colors.YELLOW, Colors.ORANGE), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val d = Engine.dsp
        val h = height.toFloat(); val w = width.toFloat()
        canvas.drawLine(0f, h / 2, w, h / 2, grid)
        val py = h / 2 - d.preampDb / 12f * h * 0.45f
        canvas.drawLine(0f, py, w, py, pre)
        val n = WoodDsp.BANDS
        val xs = FloatArray(n) { dp(6f) + it * (w - dp(12f)) / (n - 1) }
        val ys = FloatArray(n) { h / 2 - d.gainsDb[it] / 12f * h * 0.45f }
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val p0 = if (i == 0) 0 else i - 1
            val p3 = min(n - 1, i + 2)
            val c1x = xs[i] + (xs[i + 1] - xs[p0]) / 6; val c1y = ys[i] + (ys[i + 1] - ys[p0]) / 6
            val c2x = xs[i + 1] - (xs[p3] - xs[i]) / 6; val c2y = ys[i + 1] - (ys[p3] - ys[i]) / 6
            path.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        }
        curve.alpha = if (d.enabled) 255 else 90
        canvas.drawPath(path, curve)
    }
}
