package app.olauncher.extras.search

import kotlin.math.abs

/**
 * Spots a double tap on the A–Z scroller from touch times and positions: a second short, still
 * tap that starts within [timeoutMs] of the first one ending, near the same place. A drag never
 * counts as the first tap, and a third tap starts over. Pure (ms and px), unit tested.
 */
class DoubleTap(
    private val timeoutMs: Long,
    /** How far the finger may move during a tap. */
    private val tapSlop: Float,
    /** How far apart the two taps may be. */
    private val doubleSlop: Float,
    /** Longest press that still counts as a tap. */
    private val maxTapMs: Long = 300,
) {
    private var lastTapUp = NONE
    private var lastTapY = 0f
    private var downTime = 0L
    private var downY = 0f
    private var moved = false
    private var second = false

    /** A finger went down; true when it starts the second tap of a double tap. */
    fun down(time: Long, y: Float): Boolean {
        second = lastTapUp != NONE && time - lastTapUp <= timeoutMs && abs(y - lastTapY) <= doubleSlop
        lastTapUp = NONE
        downTime = time; downY = y; moved = false
        return second
    }

    fun move(y: Float) {
        if (abs(y - downY) > tapSlop) moved = true
    }

    /** The finger lifted: a short, still first tap is remembered for the next [down]. */
    fun up(time: Long, y: Float) {
        move(y)
        val tap = !second && !moved && time - downTime <= maxTapMs
        lastTapUp = if (tap) time else NONE
        lastTapY = y
        second = false
    }

    fun cancel() {
        lastTapUp = NONE
        second = false
    }

    private companion object { const val NONE = Long.MIN_VALUE }
}
