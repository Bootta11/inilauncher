package app.olauncher.extras.search

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn

/**
 * The hold-search status pill next to the app search: outlined and dimmed "Auto" while apps open
 * by themselves, filled "Hold" while auto-open is paused. Switching fades the fill and colours.
 */
object HoldPill {
    private const val AUTO_ALPHA = 0.6f

    private class State(
        val fill: GradientDrawable,
        val autoColor: Int,
        val holdColor: Int,
        var hold: Boolean? = null,
        var progress: Float = 0f,
        var animator: ValueAnimator? = null,
    )

    fun set(view: TextView, hold: Boolean) {
        val state = state(view)
        if (state.hold == hold) return
        val first = state.hold == null
        state.hold = hold
        view.setText(if (hold) R.string.hold_search_off else R.string.hold_search_on)
        view.contentDescription = view.context.getString(
            if (hold) R.string.hold_search_off_description else R.string.hold_search_on_description)
        state.animator?.cancel()
        val target = if (hold) 1f else 0f
        if (first || !view.isShown || !view.context.animationsOn()) {
            apply(view, state, target)
            return
        }
        state.animator = ValueAnimator.ofFloat(state.progress, target).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { apply(view, state, it.animatedValue as Float) }
            start()
        }
    }

    private fun apply(view: TextView, state: State, progress: Float) {
        state.progress = progress
        state.fill.alpha = (progress * 255).toInt()
        val color = ArgbEvaluator().evaluate(progress, state.autoColor, state.holdColor) as Int
        view.setTextColor(color)
        val icon = if (progress >= 0.5f) R.drawable.ic_hold_pause else R.drawable.ic_hold_auto
        if (view.getTag(R.id.hold_pill_icon) != icon) {
            view.setTag(R.id.hold_pill_icon, icon)
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
        view.compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    private fun state(view: TextView): State {
        (view.getTag(R.id.hold_pill_state) as? State)?.let { return it }
        val context = view.context
        val primary = context.themeColor(R.attr.primaryColor)
        val inverse = context.themeColor(R.attr.primaryInverseColor)
        val density = context.resources.displayMetrics.density
        val radius = 100 * density // fully rounded ends at any height
        val outline = GradientDrawable().apply {
            cornerRadius = radius
            setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(primary, (AUTO_ALPHA * 255).toInt()))
        }
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(primary)
            alpha = 0
        }
        view.background = LayerDrawable(arrayOf(outline, fill))
        view.setShadowLayer(0f, 0f, 0f, 0) // the theme's text shadow blurs light text on the fill
        return State(fill, ColorUtils.setAlphaComponent(primary, (AUTO_ALPHA * 255).toInt()), inverse)
            .also { view.setTag(R.id.hold_pill_state, it) }
    }

    private fun android.content.Context.themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }
}
