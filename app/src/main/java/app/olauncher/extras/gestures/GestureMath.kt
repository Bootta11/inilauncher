package app.olauncher.extras.gestures

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/** Pure maths behind [GestureMotion], kept apart so it can be unit tested. */
object GestureMath {

    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    /** Which way a drag goes once it has moved past [slop]; NONE until then. */
    fun axis(dx: Float, dy: Float, slop: Float): Axis = when {
        abs(dx) <= slop && abs(dy) <= slop -> Axis.NONE
        abs(dx) > abs(dy) -> Axis.HORIZONTAL
        else -> Axis.VERTICAL
    }

    /**
     * How far the home screen moves for a finger [distance]: it starts at [ratio] of the finger's
     * speed and flattens out towards [max] (never reaching it), keeping the finger's direction.
     */
    fun rubberBand(distance: Float, max: Float, ratio: Float = 0.5f): Float {
        if (max <= 0f || distance == 0f) return 0f
        val damped = abs(distance) * ratio
        return sign(distance) * max * (1f - exp(-damped / max))
    }

    /** 0..1: how far [offset] is towards [max]. */
    fun progress(offset: Float, max: Float): Float =
        if (max <= 0f) 0f else (abs(offset) / max).coerceIn(0f, 1f)
}
