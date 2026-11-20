package app.adc.woodbeat

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hourglass lightbox (same as the PriKosh app): dims the screen and blocks it while something slow runs
 * (radio search, reading a folder…). Android 9+ animates the hourglass; Android 8 shows its first frame.
 */
class Busy(private val activity: Activity) {
    private var dialog: Dialog? = null
    private var label: TextView? = null

    fun show(text: String) {
        dialog?.let { label?.text = text; return }
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = ctx.dpi(200f)
            setPadding(ctx.dpi(24f), ctx.dpi(16f), ctx.dpi(24f), ctx.dpi(16f))
            background = GradientDrawable().apply { setColor(0xFF30251F.toInt()); cornerRadius = ctx.dp(8f) }
        }
        val image = ImageView(ctx)
        if (Build.VERSION.SDK_INT >= 28) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.resources, R.raw.hourglass))
            image.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.start()
        } else {
            image.setImageBitmap(BitmapFactory.decodeResource(ctx.resources, R.raw.hourglass))
        }
        box.addView(image, LinearLayout.LayoutParams(ctx.dpi(72f), ctx.dpi(72f)))
        label = TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFFFF8EF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        box.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ctx.dpi(10f) })
        val d = Dialog(ctx).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(box)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setDimAmount(0.6f)
        }
        d.show()
        dialog = d
    }

    fun hide() {
        if (!activity.isDestroyed) dialog?.dismiss()
        dialog = null
    }
}
