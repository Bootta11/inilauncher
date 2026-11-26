package app.olauncher.extras.search

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.getColorFromAttr

/**
 * A soft rounded box over one app of the suggested row: the app picked by pulling off the A–Z
 * strip. Drawn in the row's overlay, so its items are never touched. Moving to another app glides
 * the box across; clearing fades it out. Instant on e-ink / with animations off.
 * The list's counterpart is [RowHighlight].
 */
class DockHighlight(private val row: ViewGroup) {

    private val dp = row.resources.displayMetrics.density
    private val maxAlpha = 0x26
    private val pill = GradientDrawable().apply {
        cornerRadius = 16 * dp
        setColor(row.context.getColorFromAttr(R.attr.primaryColor))
        alpha = 0
    }
    private var added = false
    private var current: View? = null
    private val from = Rect()
    private val to = Rect()
    private var moveAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null

    /** Put the box on [item] (a child of the row), or fade it out with null. */
    fun show(item: View?) {
        if (item === current) return
        val animate = row.context.animationsOn()
        if (!added) { row.overlay.add(pill); added = true }
        if (item == null) {
            current = null
            fade(0, animate)
            return
        }
        val wasShown = current != null && pill.alpha > 0
        current = item
        val inset = (4 * dp).toInt()
        to.set(item.left + inset, item.top, item.right - inset, item.bottom)
        moveAnim?.cancel()
        if (wasShown && animate) {
            from.set(pill.bounds)
            moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SHORT
                interpolator = Motion.easing
                addUpdateListener {
                    val f = it.animatedValue as Float
                    pill.setBounds(
                        lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                        lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f),
                    )
                    row.invalidate()
                }
                start()
            }
        } else { pill.bounds = to; row.invalidate() }
        fade(maxAlpha, animate)
    }

    private fun fade(target: Int, animate: Boolean) {
        fadeAnim?.cancel()
        if (!animate) { pill.alpha = target; row.invalidate(); return }
        fadeAnim = ValueAnimator.ofInt(pill.alpha, target).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { pill.alpha = it.animatedValue as Int; row.invalidate() }
            start()
        }
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    fun release() {
        moveAnim?.cancel(); fadeAnim?.cancel()
        if (added) row.overlay.remove(pill)
        added = false
    }
}
