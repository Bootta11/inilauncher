package app.olauncher.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import android.transition.AutoTransition
import android.transition.TransitionManager
import app.olauncher.R

/**
 * Inilauncher's one place for motion: every show/hide, resize, add/remove and content swap
 * goes through here so it moves smoothly, and changes instantly on e-ink screens or when
 * system animations are off. See CLAUDE.md → Motion.
 */
object Motion {
    /** Small things: a line fading in, an item settling back. */
    const val SHORT = 150L

    /** Bigger moves: dialogs, panels, cards collapsing. Same as the dialog window fade. */
    fun medium(context: Context) = context.resources.getInteger(R.integer.dialog_anim_duration).toLong()

    /** Material "standard" easing: quick start, soft landing. */
    val easing: Interpolator get() = PathInterpolator(0.4f, 0f, 0.2f, 1f)
}

/** False on e-ink screens and when any system animation scale is 0: change things instantly then. */
fun Context.animationsOn(): Boolean = !isEinkDisplay() && !isSystemAnimationsDisabled()

/**
 * Animates this container's children appearing, disappearing and changing size, and the
 * container itself growing or shrinking with them (so views below slide instead of jumping).
 * Turns transitions off when animations are off. Safe to call again on every bind.
 *
 * [changing]: also animate children that resize without being added/removed/hidden (text that
 * gets longer). Leave it off for containers whose children animate their own size every frame
 * (e.g. a collapsing card), or each frame would start a new transition.
 */
fun ViewGroup.animateChanges(duration: Long = Motion.medium(context), changing: Boolean = true) {
    if (!context.animationsOn()) {
        layoutTransition = null
        setTag(R.id.motion_changing, null)
        return
    }
    if (layoutTransition != null && getTag(R.id.motion_changing) == changing) return
    layoutTransition = LayoutTransition().apply {
        if (changing) enableTransitionType(LayoutTransition.CHANGING)
        setDuration(duration)
        setInterpolator(LayoutTransition.CHANGE_APPEARING, Motion.easing)
        setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, Motion.easing)
        setInterpolator(LayoutTransition.CHANGING, Motion.easing)
        // Let the space open before fading in, and fade out before it closes
        setStartDelay(LayoutTransition.APPEARING, duration / 2)
        setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, duration / 3)
        setAnimateParentHierarchy(true)
    }
    setTag(R.id.motion_changing, changing)
}

/**
 * Shows or hides with a fade. Inside an [animateChanges] container the container's transition does
 * the fade and the slide; elsewhere it fades by itself. Showing again mid-fade-out turns it back.
 * [targetAlpha]: the alpha it ends at when shown (for lines drawn faded on purpose).
 */
fun View.fadeVisible(visible: Boolean, targetAlpha: Float = 1f) {
    val hiding = getTag(R.id.motion_hiding) == true
    if (visible && isVisible && !hiding) return
    if (!visible && (!isVisible || hiding)) return
    animate().cancel()
    setTag(R.id.motion_hiding, null)
    val transition = (parent as? ViewGroup)?.layoutTransition
    if (!context.animationsOn()) {
        if (visible) alpha = targetAlpha
        isVisible = visible
        return
    }
    if (transition?.isTransitionTypeEnabled(LayoutTransition.APPEARING) == true) {
        // The container's transition fades and slides; its fade always ends fully opaque, so a
        // line meant to stay faded opens its space with the transition and fades in by itself
        if (!visible || targetAlpha >= 1f) {
            if (visible) alpha = 1f
            isVisible = visible
            return
        }
        transition.disableTransitionType(LayoutTransition.APPEARING)
        alpha = 0f
        isVisible = true
        transition.enableTransitionType(LayoutTransition.APPEARING)
        animate().alpha(targetAlpha).setStartDelay(Motion.SHORT / 2).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
        return
    }
    if (visible) {
        if (!isVisible) { alpha = 0f; isVisible = true }
        animate().alpha(targetAlpha).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
    } else {
        setTag(R.id.motion_hiding, true)
        animate().alpha(0f).setDuration(Motion.SHORT).setInterpolator(Motion.easing).withEndAction {
            setTag(R.id.motion_hiding, null)
            isVisible = false
            alpha = targetAlpha
        }.start()
    }
}

/**
 * Sets how faded a view is: animated while it's on screen, instant while hidden (so a following
 * show fades in to this alpha). Set it before showing the view.
 */
fun View.fadeTo(target: Float) {
    if (alpha == target) return
    if (!isVisible || !isAttachedToWindow || !context.animationsOn()) {
        animate().cancel()
        alpha = target
        return
    }
    animate().alpha(target).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
}


/**
 * Animates whatever this container's next layout changes (bounds, visibility, padding) from
 * how it looks now. Call right before changing the views. No-op when animations are off.
 * [excludeInside]: views that are animated themselves, but not what's inside them.
 */
fun ViewGroup.beginTransition(duration: Long = Motion.SHORT, exclude: View? = null, excludeInside: List<View> = emptyList()) {
    if (!context.animationsOn()) return
    val transition = AutoTransition().apply {
        setDuration(duration)
        setInterpolator(Motion.easing)
        exclude?.let { excludeTarget(it, true); excludeChildren(it, true) }
        excludeInside.forEach { excludeChildren(it, true) }
    }
    TransitionManager.beginDelayedTransition(this, transition)
}

/** Finishes the [beginTransition]s this view is part of, so its bounds are where they'll stay. */
fun ViewGroup.endTransitions() {
    generateSequence<ViewParent>(this) { it.parent }.filterIsInstance<ViewGroup>().forEach { TransitionManager.endTransitions(it) }
}

/**
 * Shows or hides by growing/shrinking the view's own height (and margins) with a fade, frame by
 * frame. Use it inside a container whose other children also animate their height this way
 * (a list where items collapse), where a container transition would aim at a stale size and
 * make the edge overshoot. Elsewhere prefer [fadeVisible] in an [animateChanges] container.
 */
fun View.expandVisible(visible: Boolean, targetAlpha: Float = 1f, duration: Long = Motion.medium(context) - 30) {
    val running = getTag(R.id.motion_expand) as? ValueAnimator
    val showing = running?.let { getTag(R.id.motion_expand_dir) == true } ?: isVisible
    if (showing == visible) return
    running?.cancel()
    val lp = layoutParams
    if (!context.animationsOn() || lp == null || (parent as? View)?.isLaidOut != true) {
        lp?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        alpha = targetAlpha
        isVisible = visible
        return
    }
    val margins = (lp as? ViewGroup.MarginLayoutParams)?.let { it.topMargin to it.bottomMargin }
    val full = getTag(R.id.motion_expand_margins) as? Pair<*, *> ?: margins?.also { setTag(R.id.motion_expand_margins, it) }
    val fullTop = (full?.first as? Int) ?: 0
    val fullBottom = (full?.second as? Int) ?: 0
    val width = ((parent as View).width - (parent as View).paddingLeft - (parent as View).paddingRight).coerceAtLeast(0)
    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val natural = measuredHeight
    val from = if (isVisible) (if (running != null) height.toFloat() / natural.coerceAtLeast(1) else 1f) else 0f
    if (!isVisible) { lp.height = 0; alpha = 0f; isVisible = true }
    val to = if (visible) 1f else 0f
    setTag(R.id.motion_expand_dir, visible)
    val anim = ValueAnimator.ofFloat(from.coerceIn(0f, 1f), to).apply {
        this.duration = duration
        interpolator = Motion.easing
        addUpdateListener {
            val f = it.animatedValue as Float
            lp.height = (natural * f).toInt()
            (lp as? ViewGroup.MarginLayoutParams)?.apply { topMargin = (fullTop * f).toInt(); bottomMargin = (fullBottom * f).toInt() }
            alpha = targetAlpha * f
            layoutParams = lp
        }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                setTag(R.id.motion_expand, null)
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                (lp as? ViewGroup.MarginLayoutParams)?.apply { topMargin = fullTop; bottomMargin = fullBottom }
                layoutParams = lp
                alpha = targetAlpha
                if (!visible) isVisible = false
            }
        })
    }
    setTag(R.id.motion_expand, anim)
    anim.start()
}

