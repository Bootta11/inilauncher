package app.olauncher.extras.search

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.getColorFromAttr

/**
 * A soft pill behind one row of the app list: the app picked by pulling off the A–Z strip.
 * Drawn as an item decoration, so rows are never rebound (no blink). Moving to another row glides
 * from where the pill is now to the row's live position (it follows scrolling); clearing fades it
 * out. Instant on e-ink / with animations off.
 */
class RowHighlight(private val list: RecyclerView) : RecyclerView.ItemDecoration() {

    private val dp = list.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = list.context.getColorFromAttr(R.attr.primaryColor)
    }
    private val maxAlpha = 0x26

    /** Row the pill is on (or fading out from); -1 = none ever shown. */
    private var position = RecyclerView.NO_POSITION
    private var visible = false

    private val from = RectF()       // where the pill was when it started moving
    private val drawn = RectF()      // where it was drawn last frame
    private val target = RectF()
    private var move = 1f            // 0 → 1 from [from] to the row
    private var alpha = 0f           // 0 → 1
    private var moveAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null

    init { list.addItemDecoration(this) }

    /** Put the pill on [adapterPosition], or fade it out with [RecyclerView.NO_POSITION]. */
    fun show(adapterPosition: Int) {
        val animate = list.context.animationsOn()
        if (adapterPosition == RecyclerView.NO_POSITION) {
            if (!visible) return
            visible = false
            fade(0f, animate)
            return
        }
        if (adapterPosition == position && visible) return
        val wasShown = visible && alpha > 0f && !drawn.isEmpty
        position = adapterPosition
        visible = true
        moveAnim?.cancel()
        if (wasShown && animate) {
            from.set(drawn)
            move = 0f
            moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SHORT
                interpolator = Motion.easing
                addUpdateListener { move = it.animatedValue as Float; list.invalidate() }
                start()
            }
        } else move = 1f
        fade(1f, animate)
        list.invalidate()
    }

    private fun fade(to: Float, animate: Boolean) {
        fadeAnim?.cancel()
        if (!animate) { alpha = to; list.invalidate(); return }
        fadeAnim = ValueAnimator.ofFloat(alpha, to).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { alpha = it.animatedValue as Float; list.invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (alpha <= 0f || position == RecyclerView.NO_POSITION) return
        val row = parent.layoutManager?.findViewByPosition(position) ?: return
        val inset = 8 * dp
        val y = row.translationY
        target.set(row.left + inset, row.top + y + 2 * dp, row.right - inset, row.bottom + y - 2 * dp)
        if (move >= 1f) drawn.set(target)
        else drawn.set(
            lerp(from.left, target.left), lerp(from.top, target.top),
            lerp(from.right, target.right), lerp(from.bottom, target.bottom),
        )
        paint.alpha = (maxAlpha * alpha).toInt()
        val r = drawn.height() / 2
        c.drawRoundRect(drawn, r, r, paint)
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * move

    fun release() {
        moveAnim?.cancel(); fadeAnim?.cancel()
        list.removeItemDecoration(this)
    }
}
