package app.olauncher.extras

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drag a home app up or down to reorder: hold it until it lifts (with a buzz), then drag; the
 * other apps make room and it drops where you let go. Holding and letting go without moving
 * opens the app picker as before (now on release).
 *
 * It sits in front of upstream's touch listener: taps and swipes pass through untouched. It lifts
 * the app just before upstream's long press would fire and sends upstream a cancel, so upstream
 * never long-presses, swipes or taps for that gesture.
 */
object HomeReorder {

    /**
     * [views]: the home app views in slot order (slot = index + 1). [upstream]: each view's own
     * touch listener. [onLongPress]: held and released without moving. [onMoved]: after a move,
     * to redraw the home apps.
     */
    fun attach(
        views: List<View>,
        upstream: (View) -> View.OnTouchListener,
        onLongPress: (View) -> Unit,
        onMoved: () -> Unit,
    ) {
        views.forEach { view -> view.setOnTouchListener(Touch(view, views, upstream(view), onLongPress, onMoved)) }
    }

    private class Touch(
        private val view: View,
        private val all: List<View>,
        private val upstream: View.OnTouchListener,
        private val onLongPress: (View) -> Unit,
        private val onMoved: () -> Unit,
    ) : View.OnTouchListener {

        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        private val density = view.resources.displayMetrics.density

        private enum class State { IDLE, PRESSING, LIFTED, DRAGGING }
        private var state = State.IDLE
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var shown: List<View> = emptyList()
        private var from = -1
        private var target = -1

        // Lift a little before upstream's own long press would fire, so it gets cancelled first
        private val lift = Runnable {
            if (state != State.PRESSING) return@Runnable
            state = State.LIFTED
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (view.context.animationsOn())
                view.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.85f).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
            else { view.scaleX = 1.08f; view.scaleY = 1.08f; view.alpha = 0.85f }
            view.translationZ = 8 * density
            view.parent?.requestDisallowInterceptTouchEvent(true)
            // From here on this gesture is ours: upstream must not long-press, swipe or tap
            val cancel = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
            upstream.onTouch(view, cancel)
            cancel.recycle()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    state = State.PRESSING
                    downX = event.rawX
                    downY = event.rawY
                    downTime = event.downTime
                    handler.postDelayed(lift, (ViewConfiguration.getLongPressTimeout() - 60L).coerceAtLeast(150L))
                    return upstream.onTouch(v, event)
                }
                MotionEvent.ACTION_MOVE -> when (state) {
                    State.PRESSING -> {
                        // Moving before the lift is a normal swipe
                        if (hypot(event.rawX - downX, event.rawY - downY) > slop) {
                            handler.removeCallbacks(lift)
                            state = State.IDLE
                        }
                        return upstream.onTouch(v, event)
                    }
                    State.LIFTED -> {
                        if (abs(event.rawY - downY) > slop) startDrag()
                        return true
                    }
                    State.DRAGGING -> { drag(event.rawY - downY); return true }
                    State.IDLE -> return upstream.onTouch(v, event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(lift)
                    val was = state
                    state = State.IDLE
                    return when (was) {
                        State.LIFTED -> {
                            drop()
                            if (event.actionMasked == MotionEvent.ACTION_UP) onLongPress(view)
                            true
                        }
                        State.DRAGGING -> {
                            finish(commit = event.actionMasked == MotionEvent.ACTION_UP)
                            true
                        }
                        else -> upstream.onTouch(v, event)
                    }
                }
            }
            return if (state == State.LIFTED || state == State.DRAGGING) true else upstream.onTouch(v, event)
        }

        private fun startDrag() {
            state = State.DRAGGING
            shown = all.filter { it.visibility == View.VISIBLE }
            from = shown.indexOf(view)
            target = from
        }

        private fun drag(dy: Float) {
            if (from < 0) return
            view.translationY = dy
            val center = view.top + view.height / 2f + dy
            val newTarget = shown.indices.minByOrNull { abs(shown[it].top + shown[it].height / 2f - center) } ?: from
            if (newTarget != target) {
                target = newTarget
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                // The apps between the old and new place slide over by one row
                val step = view.height.toFloat()
                shown.forEachIndexed { i, other ->
                    if (other === view) return@forEachIndexed
                    val shift = when {
                        from < target && i in (from + 1)..target -> -step
                        from > target && i in target until from -> step
                        else -> 0f
                    }
                    if (view.context.animationsOn()) other.animate().translationY(shift).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
                    else other.translationY = shift
                }
            }
        }

        private fun finish(commit: Boolean) {
            val moved = commit && from >= 0 && target >= 0 && target != from
            val fromSlot = all.indexOf(shown.getOrNull(from)) + 1
            val toSlot = all.indexOf(shown.getOrNull(target)) + 1
            val dragged = shown
            // Settle into the drop place first (or back home), then swap the slots: nothing jumps
            val settleY = if (moved) (dragged[target].top - view.top).toFloat() else 0f
            val done = Runnable {
                dragged.forEach { it.animate().cancel(); it.translationY = 0f }
                drop()
                if (moved && fromSlot > 0 && toSlot > 0) {
                    HomeSlots.move(view.context, fromSlot, toSlot)
                    onMoved()
                }
            }
            shown = emptyList()
            from = -1
            target = -1
            if (!view.context.animationsOn()) return done.run()
            if (!moved) dragged.forEach { if (it !== view) it.animate().translationY(0f).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start() }
            view.animate().translationY(settleY).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(Motion.SHORT).setInterpolator(Motion.easing).withEndAction(done).start()
        }

        private fun drop() {
            view.animate().cancel()
            if (view.context.animationsOn() && (view.scaleX != 1f || view.alpha != 1f)) {
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(Motion.SHORT)
                    .setInterpolator(Motion.easing).withEndAction { view.translationZ = 0f }.start()
                view.isPressed = false
                return
            }
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            view.translationY = 0f
            view.translationZ = 0f
            view.isPressed = false
        }
    }
}
