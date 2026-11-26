package app.olauncher.extras.drag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Hold an item until it lifts, then drag it; holding and letting go without moving calls [onHold]
 * (its menu). Taps and scrolls work as usual. It lifts just before Android's own long press would
 * fire and cancels the view's touch, so the view neither clicks nor long-clicks for that gesture;
 * a long-click listener still serves the keyboard and TalkBack.
 *
 * [start]: the drag for a view, or null when it can't be dragged now.
 */
class HoldDrag(private val start: (View) -> DragSession?, private val onHold: (View) -> Unit) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var session: DragSession? = null
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private var lift: Runnable? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forget()
                held = false
                session = null
                downX = event.rawX
                downY = event.rawY
                lift = Runnable { lift(view, event.downTime) }
                    .also { handler.postDelayed(it, (ViewConfiguration.getLongPressTimeout() - 60L).coerceAtLeast(150L)) }
            }
            MotionEvent.ACTION_MOVE ->
                if (held) session?.move(event.rawX, event.rawY)
                else if (hypot(event.rawX - downX, event.rawY - downY) > ViewConfiguration.get(view.context).scaledTouchSlop) forget()
            MotionEvent.ACTION_UP -> {
                forget()
                if (held) {
                    if (session?.release() == false) onHold(view)
                    session = null
                    held = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                forget()
                if (held) {
                    session?.cancel()
                    session = null
                    held = false
                    return true
                }
            }
        }
        return held
    }

    private fun lift(view: View, downTime: Long) {
        lift = null
        if (!view.isAttachedToWindow) return
        session = start(view) ?: return
        held = true
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        view.onTouchEvent(cancel)
        cancel.recycle()
        session?.lift(downX, downY)
    }

    private fun forget() {
        lift?.let { handler.removeCallbacks(it) }
        lift = null
    }
}
