package app.olauncher.extras.notifications

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LayoutDirection
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn

/**
 * An app icon with a notification badge on its top-end corner: a dot, or a small pill with the
 * count (the "count" dot style). Used where the dot after a name could be cut off (the sub list's
 * fixed-width "icons + names" cells). The badge is in [color] (the home text colour) with a thin
 * ring in the opposite colour, so it shows on any wallpaper and any icon. [setBadge] fades it.
 */
class BadgedIcon(private val context: Context, val icon: Drawable, private val color: Int) : Drawable(), Drawable.Callback {

    private val halo = if (ColorUtils.calculateLuminance(color) > 0.5) 0xE6000000.toInt() else 0xE6FFFFFF.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BadgedIcon.color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = halo }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = halo or 0xFF000000.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pill = RectF()

    private var label = ""
    private var count = 0
    /** 0 = hidden, 1 = fully shown. */
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init {
        icon.callback = this
    }

    /** Shows [count] notifications in [style] (0 hides the badge); fades when [animate] and animations are on. */
    fun setBadge(count: Int, style: String, animate: Boolean) {
        val newLabel = NotificationDots.badgeText(count, style)
        val target = if (count > 0) 1f else 0f
        // Keep the old label while fading out, so the badge doesn't change as it goes
        if (count > 0) label = newLabel
        if (this.count == count && shown == target && animator == null) return
        this.count = count
        animator?.cancel()
        animator = null
        if (!animate || !context.animationsOn()) {
            shown = target
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        icon.bounds = bounds
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        icon.layoutDirection = layoutDirection
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        if (shown <= 0f) return
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val radius = size * 0.14f
        val ringWidth = (size * 0.035f).coerceAtLeast(1.5f * context.resources.displayMetrics.density)
        val height = if (label.isEmpty()) radius * 2 else radius * 2.6f
        text.textSize = height * 0.68f
        val width = if (label.isEmpty()) height else maxOf(height, text.measureText(label) + height * 0.5f)
        val rtl = layoutDirection == LayoutDirection.RTL
        val left = if (rtl) b.left.toFloat() + ringWidth else b.right - ringWidth - width
        pill.set(left, b.top + ringWidth, left + width, b.top + ringWidth + height)
        val alpha = (shown * 255).toInt()
        ring.alpha = (shown * (halo ushr 24)).toInt()
        fill.alpha = alpha
        text.alpha = alpha
        val r = height / 2
        canvas.drawRoundRect(pill.left - ringWidth, pill.top - ringWidth, pill.right + ringWidth, pill.bottom + ringWidth, r + ringWidth, r + ringWidth, ring)
        canvas.drawRoundRect(pill, r, r, fill)
        if (label.isNotEmpty()) {
            val baseline = pill.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, pill.centerX(), baseline, text)
        }
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
    override fun setAlpha(alpha: Int) { icon.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
