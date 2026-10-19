package app.xfeedguard

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Switch
import kotlin.math.abs

/** Make the full labelled row a tap target while retaining native switch accessibility. */
open class FullRowSwitch(context: Context) : Switch(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var cancelled = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; cancelled = false; isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    cancelled = true; isPressed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = !cancelled && event.x >= 0 && event.x < width && event.y >= 0 && event.y < height
                isPressed = false
                if (tap) performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; isPressed = false }
        }
        return true
    }
}
