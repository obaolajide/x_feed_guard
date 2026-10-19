package app.xfeedguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Native checkable control: touch, keyboard, and accessibility share performClick(). */
class PowerToggle(context: Context) : FullRowSwitch(context) {
    private val density = resources.displayMetrics.density
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buttonDrawable = null
        contentDescription = "Protection"
        isFocusable = true
        isClickable = true
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.BLACK, Color.rgb(222, 222, 222))))
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(45, 128, 128, 128)), circle, mask)
        stateListAnimator = null
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * 0.43f
        val radius = 23f * density
        pen.color = if (isChecked) Color.WHITE else Color.rgb(80, 80, 80)
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = 3f * density
        pen.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), -45f, 270f, false, pen)
        canvas.drawLine(cx, cy - 27f * density, cx, cy - 3f * density, pen)
        pen.style = Paint.Style.FILL
        pen.textAlign = Paint.Align.CENTER
        pen.textSize = 13f * resources.displayMetrics.scaledDensity
        canvas.drawText(if (isChecked) "On" else "Off", cx, height * 0.76f, pen)
    }
}
