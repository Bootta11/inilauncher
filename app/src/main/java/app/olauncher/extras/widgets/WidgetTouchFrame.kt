package app.olauncher.extras.widgets

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.olauncher.extras.ExtrasState
import app.olauncher.extras.drag.DragSession
import kotlin.math.abs

/**
 * Holds one widget on the scrolling panel. Widget lists (ListView, GridView…) don't take part in
 * nested scrolling, so the panel's ScrollView would grab every vertical drag once the panel is
 * taller than the screen. A drag that starts on a scrollable part of the widget stays in the
 * widget while that part can still scroll that way; at its end the panel takes over.
 *
 * Also spots a long press anywhere on the widget (its own buttons and rows take the touches, so a
 * plain long-click listener wouldn't fire): it lifts the widget ([startDrag]) so it can be dragged to
 * another place, or calls [onHold] when let go without moving. The widget gets a cancel instead of
 * a tap.
 */
class WidgetTouchFrame(context: Context) : FrameLayout(context) {

    var startDrag: (() -> DragSession?)? = null
    var onHold: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var scrollable: View? = null
    private var downX = 0f
    private var downY = 0f
    private var decided = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false
    private var session: DragSession? = null

    private val checkLongPress = Runnable {
        val drag = startDrag?.invoke() ?: return@Runnable
        longPressed = true
        session = drag
        // Release whatever the widget was pressing so it doesn't also count as a tap
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        parent?.requestDisallowInterceptTouchEvent(true)
        drag.lift(downRawX, downRawY)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                session = null
                decided = false
                longPressed = false
                removeCallbacks(checkLongPress)
                if (startDrag != null) postDelayed(checkLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                scrollable = findScrollable(this, event.x, event.y)
                // Hold the panel off until the drag's direction is known
                if (scrollable != null) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) {
                    session?.move(event.rawX, event.rawY)
                    return true
                }
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) removeCallbacks(checkLongPress)
                val target = scrollable
                if (target != null && !decided) {
                    val dy = event.y - downY
                    if (abs(dy) > slop / 2f) {
                        decided = true
                        // Finger down → content scrolls up (-1); finger up → content scrolls down (+1)
                        val canScroll = target.canScrollVertically(if (dy > 0) -1 else 1)
                        parent?.requestDisallowInterceptTouchEvent(canScroll)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(checkLongPress)
                scrollable = null
                // The widget already got its cancel; swallow the lift so it isn't a click
                if (longPressed) {
                    longPressed = false
                    val drag = session
                    session = null
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) drag?.cancel()
                    else if (drag?.release() == false) onHold?.invoke()
                    return true
                }
                // A tap may open the widget's app; stay on the panel when the launcher leaves for it
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) ExtrasState.markWidgetTap()
            }
        }
        if (longPressed) return true
        val handled = super.dispatchTouchEvent(event)
        // A widget that takes no touches: still get the rest of the gesture, to tell a hold from a tap
        return handled || (event.actionMasked == MotionEvent.ACTION_DOWN && startDrag != null)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(checkLongPress)
        super.onDetachedFromWindow()
    }

    /** The deepest visible view under (x, y), relative to [group], that can scroll vertically. */
    private fun findScrollable(group: ViewGroup, x: Float, y: Float): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cx = x + group.scrollX - child.left - child.translationX
            val cy = y + group.scrollY - child.top - child.translationY
            if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
            if (child is ViewGroup) findScrollable(child, cx, cy)?.let { return it }
            if (child.canScrollVertically(1) || child.canScrollVertically(-1)) return child
        }
        return null
    }
}
