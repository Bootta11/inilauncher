package app.olauncher.extras.drag

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.endTransitions
import kotlin.math.hypot

/**
 * One item of [container] being held and dragged to another place: it lifts with a buzz, follows
 * the finger while the others make room, and settles into its new place on release. Nothing is
 * laid out until the drop. The container's children are the items, in order.
 *
 * [candidates]: the places it may go to. [slots]: where every child would sit in a given order.
 * [vertical]: moves up and down only. [keepInside]: stays within the container (plus a little).
 * [onDrop]: move the child and save it, (from, to). [onEnd]: always, after everything else.
 */
class DragSession(
    private val container: ViewGroup,
    private val view: View,
    private val candidates: List<Int>,
    private val slots: (List<Int>) -> List<DragSlots.Pos>,
    private val liftScale: Float = 1.08f,
    private val vertical: Boolean = false,
    private val keepInside: Boolean = false,
    private val onDrop: (from: Int, to: Int) -> Unit,
    private val onEnd: () -> Unit = {},
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val density = view.resources.displayMetrics.density
    private val items = (0 until container.childCount).map { container.getChildAt(it) }
    private val from = items.indexOf(view)
    private val sizes = items.map { DragSlots.Size(it.width, it.height) }
    private val home = slots(items.indices.toList())
    private val scroll = EdgeScroller.around(container)
    private val edge = scroll?.let { EdgeScroller(it) { follow() } }
    private val scrollNow get() = scroll?.scrollY ?: 0
    private var scrollAtLift = 0
    private var downX = 0f
    private var downY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var target = from
    private var over = false

    /** The finger has moved, so letting go drops it instead of opening a menu. */
    var dragging = false
        private set

    // The item went away (its app was uninstalled, the screen closed): nothing to settle
    private val gone = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = stopNow()
    }

    fun lift(x: Float, y: Float) {
        container.setTag(R.id.drag_session, this)
        container.endTransitions()
        view.addOnAttachStateChangeListener(gone)
        downX = x
        downY = y
        rawX = x
        rawY = y
        scrollAtLift = scrollNow
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.parent?.requestDisallowInterceptTouchEvent(true)
        view.animate().cancel()
        view.translationZ = 8 * density
        if (view.context.animationsOn())
            view.animate().scaleX(liftScale).scaleY(liftScale).alpha(LIFT_ALPHA).setStartDelay(0)
                .setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
        else { view.scaleX = liftScale; view.scaleY = liftScale; view.alpha = LIFT_ALPHA }
    }

    fun move(x: Float, y: Float) {
        if (over) return
        rawX = x
        rawY = y
        if (!dragging) {
            if (hypot(x - downX, y - downY) <= slop) return
            dragging = true
        }
        follow()
        edge?.update(y)
    }

    private fun follow() {
        if (over || !dragging) return
        var dx = if (vertical) 0f else rawX - downX
        var dy = rawY - downY + (scrollNow - scrollAtLift)
        if (keepInside) {
            val room = 8 * density
            dx = within(dx, -view.left - room, container.width - view.right + room)
            dy = within(dy, -view.top - room, container.height - view.bottom + room)
        }
        view.translationX = dx
        view.translationY = dy
        val cx = home[from].x + sizes[from].width / 2f + dx
        val cy = home[from].y + sizes[from].height / 2f + dy
        val next = DragSlots.target(from, candidates, sizes, cx, cy, slots)
        if (next == target) return
        target = next
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        makeRoom()
    }

    private fun within(value: Float, low: Float, high: Float) = if (low > high) 0f else value.coerceIn(low, high)

    /** The others slide to where they'd be if it were dropped now. */
    private fun makeRoom() {
        val places = slots(DragSlots.moved(items.size, from, target))
        items.forEachIndexed { i, item ->
            if (i == from) return@forEachIndexed
            val x = (places[i].x - home[i].x).toFloat()
            val y = (places[i].y - home[i].y).toFloat()
            if (item.context.animationsOn())
                item.animate().translationX(x).translationY(y).setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
            else { item.translationX = x; item.translationY = y }
        }
    }

    /** Finger lifted. True when it was a drag (dropped), false when it was only held. */
    fun release(): Boolean {
        if (over) return true
        if (!dragging) {
            settle(from)
            return false
        }
        settle(target)
        return true
    }

    /** The gesture was taken away: back to where it was. */
    fun cancel() {
        if (over) return
        target = from
        makeRoom()
        settle(from)
    }

    private fun settle(to: Int) {
        over = true
        edge?.stop()
        val place = slots(DragSlots.moved(items.size, from, to))[from]
        if (!view.context.animationsOn()) return finish(to)
        view.animate().translationX((place.x - home[from].x).toFloat()).translationY((place.y - home[from].y).toFloat())
            .scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.easing)
            .withEndAction { finish(to) }.start()
    }

    /** Ends it at once, without a move (the list is closing or the item is gone). */
    fun stopNow() = finish(from)

    private var finished = false

    private fun finish(to: Int) {
        if (finished) return
        finished = true
        over = true
        edge?.stop()
        view.removeOnAttachStateChangeListener(gone)
        items.forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.translationZ = 0f
        view.isPressed = false
        if (container.getTag(R.id.drag_session) === this) container.setTag(R.id.drag_session, null)
        if (to != from) onDrop(from, to)
        onEnd()
    }

    companion object {
        private const val LIFT_ALPHA = 0.85f

        /** The drag running in [container] (also while it settles), if any. */
        fun of(container: ViewGroup) = container.getTag(R.id.drag_session) as? DragSession
    }
}
