package app.olauncher.extras.notifications

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import kotlin.math.abs

/**
 * Wraps one notification card: drag it sideways to dismiss. Past ~40% of its width (or with a
 * quick fling) it flies off, then the space it took collapses and [onDismiss] runs. When
 * [dismissible] is false (ongoing notifications) it stretches a little and springs back.
 * Taps still reach the card and its buttons; a drag only takes over once it is clearly sideways.
 */
class SwipeDismissLayout(context: Context) : FrameLayout(context) {

    var dismissible = true
    var onDismiss: () -> Unit = {}
    /** Runs as the card's space starts to close, so things below can start moving with it. */
    var onCollapseStart: () -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = 800 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var passedThreshold = false
    private var tracker: VelocityTracker? = null
    private var gone = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (gone) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_MOVE -> { track(ev); if (shouldStartDrag(ev)) beginDrag() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (gone) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { start(ev); return true }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging && shouldStartDrag(ev)) beginDrag()
                if (dragging) follow(ev.rawX - downX)
            }
            MotionEvent.ACTION_UP -> {
                track(ev)
                if (dragging) release() else if (abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop) performClick()
                reset()
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) settleBack(); reset() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun start(ev: MotionEvent) {
        downX = ev.rawX; downY = ev.rawY
        dragging = false; passedThreshold = false
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        track(ev)
        animate().cancel()
    }

    private fun track(ev: MotionEvent) {
        // Raw coordinates: the view itself moves while dragging
        val copy = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        tracker?.addMovement(copy)
        copy.recycle()
    }

    private fun shouldStartDrag(ev: MotionEvent): Boolean {
        val dx = abs(ev.rawX - downX)
        return dx > slop && dx > abs(ev.rawY - downY) * 1.5f
    }

    private fun beginDrag() {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        isPressed = false
    }

    private fun follow(dx: Float) {
        val w = width.coerceAtLeast(1).toFloat()
        val x = if (dismissible) dx else dx * 0.2f
        translationX = x
        val p = (abs(x) / w).coerceIn(0f, 1f)
        alpha = 1f - p * 0.85f
        rotation = x / w * 4f
        scaleX = 1f - p * 0.05f; scaleY = scaleX
        val past = dismissible && p > THRESHOLD
        if (past != passedThreshold) {
            passedThreshold = past
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun release() {
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val x = translationX
        val fling = abs(vx) > minFling && (vx > 0) == (x > 0) && abs(x) > slop
        if (dismissible && (abs(x) > width * THRESHOLD || fling)) flyOff(if (x == 0f) vx else x) else settleBack()
    }

    private fun settleBack() {
        if (!context.animationsOn()) {
            translationX = 0f; alpha = 1f; rotation = 0f; scaleX = 1f; scaleY = 1f
            return
        }
        animate().translationX(0f).alpha(1f).rotation(0f).scaleX(1f).scaleY(1f)
            .setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).setListener(null).start()
    }

    /** Animates out (also used by "Clear all"); [direction] picks the side. */
    fun flyOff(direction: Float = 1f, delay: Long = 0) {
        if (gone) return
        gone = true
        if (!context.animationsOn()) { onCollapseStart(); return onDismiss() }
        val target = (if (direction < 0) -1 else 1) * width * 1.2f
        animate().translationX(target).alpha(0f).rotation(if (direction < 0) -8f else 8f)
            .setStartDelay(delay).setDuration(220).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = collapse()
            }).start()
    }

    private fun collapse() {
        onCollapseStart()
        val lp = layoutParams ?: return onDismiss()
        val start = height
        val margins = (lp as? MarginLayoutParams)?.let { it.topMargin to it.bottomMargin }
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = COLLAPSE_MS
            interpolator = Motion.easing
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.height = (start * f).toInt()
                if (lp is MarginLayoutParams && margins != null) {
                    lp.topMargin = (margins.first * f).toInt(); lp.bottomMargin = (margins.second * f).toInt()
                }
                layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onDismiss()
            })
            start()
        }
    }

    private fun reset() {
        dragging = false
        tracker?.recycle(); tracker = null
    }

    private companion object {
        const val THRESHOLD = 0.4f
        const val COLLAPSE_MS = 220L
    }
}
