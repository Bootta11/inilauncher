package app.olauncher.extras.search

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn

/**
 * A suggested-row app's icon with a small pin badge on its top end corner while it's pinned.
 * Without an icon (icons off) it is a small pin above the name, in the space the padding used
 * to take, so showing it never moves the name. [shown] fades and grows the badge in and out.
 */
class PinBadge(
    private val context: Context,
    private val icon: Drawable?,
    color: Int,
    pinned: Boolean,
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val size = if (icon != null) icon.intrinsicWidth else (10 * density).toInt()
    private val badgeSize = if (icon != null) (17 * density) else (10 * density)
    private val pin = ContextCompat.getDrawable(context, R.drawable.ic_extras_pin)!!.mutate().apply { setTint(color) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF202124.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ColorUtils.setAlphaComponent(color, 200)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private var alpha = 255
    private var animator: ValueAnimator? = null

    /** 0 = no badge, 1 = full badge. */
    private var amount = if (pinned) 1f else 0f
        set(value) { field = value; invalidateSelf() }

    var pinned = pinned
        private set

    fun show(pinned: Boolean, animate: Boolean = true) {
        if (pinned == this.pinned) return
        this.pinned = pinned
        animator?.cancel()
        val target = if (pinned) 1f else 0f
        if (!animate || !context.animationsOn()) { amount = target; return }
        animator = ValueAnimator.ofFloat(amount, target).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { amount = it.animatedValue as Float }
            start()
        }
    }

    override fun getIntrinsicWidth() = icon?.intrinsicWidth ?: size
    override fun getIntrinsicHeight() = icon?.intrinsicHeight ?: size

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        icon?.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        icon?.draw(canvas)
        if (amount <= 0f) return
        val b = bounds
        val half = badgeSize / 2
        val rtl = layoutDirection == LayoutDirection.RTL
        val cx = if (icon == null) b.exactCenterX() else if (rtl) b.left + half else b.right - half
        val cy = if (icon == null) b.exactCenterY() else b.top + half
        val save = canvas.save()
        val scale = 0.6f + 0.4f * amount
        canvas.scale(scale, scale, cx, cy)
        val a = (amount * alpha).toInt()
        if (icon != null) {
            fill.alpha = a * 0xE6 / 255
            ring.alpha = a * 200 / 255
            canvas.drawCircle(cx, cy, half - density / 2, fill)
            canvas.drawCircle(cx, cy, half - density / 2, ring)
        }
        val glyph = (if (icon != null) badgeSize * 0.62f else badgeSize).toInt()
        val left = (cx - glyph / 2f).toInt()
        val top = (cy - glyph / 2f).toInt()
        pin.setBounds(left, top, left + glyph, top + glyph)
        pin.alpha = a
        pin.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { this.alpha = alpha; icon?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon?.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
