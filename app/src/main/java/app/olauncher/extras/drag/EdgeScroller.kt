package app.olauncher.extras.drag

import android.view.View
import android.widget.ScrollView
import kotlin.math.min

/** Scrolls a ScrollView while a finger that's dragging something is near its top or bottom edge. */
class EdgeScroller(private val scroll: ScrollView, private val zoneDp: Int = 64, private val onScrolled: () -> Unit) {

    private val density = scroll.resources.displayMetrics.density
    private val place = IntArray(2)
    private var speed = 0
    private var running = false

    private val step = object : Runnable {
        override fun run() {
            if (speed == 0 || !scroll.isAttachedToWindow) { running = false; return }
            val before = scroll.scrollY
            scroll.scrollBy(0, speed)
            if (scroll.scrollY != before) onScrolled()
            scroll.postOnAnimation(this)
        }
    }

    /** The finger is at [rawY] on the screen. */
    fun update(rawY: Float) {
        scroll.getLocationOnScreen(place)
        val y = rawY - place[1]
        val zone = min(zoneDp * density, scroll.height / 4f)
        val max = 12 * density
        speed = when {
            zone <= 0f -> 0
            y < zone -> -(max * ((zone - y) / zone).coerceIn(0f, 1f)).toInt()
            y > scroll.height - zone -> (max * ((y - scroll.height + zone) / zone).coerceIn(0f, 1f)).toInt()
            else -> 0
        }
        if (speed != 0 && !running) {
            running = true
            scroll.postOnAnimation(step)
        }
    }

    fun stop() {
        speed = 0
        running = false
        scroll.removeCallbacks(step)
    }

    companion object {
        /** The ScrollView around [view], if any. */
        fun around(view: View): ScrollView? =
            generateSequence(view.parent) { it.parent }.firstOrNull { it is ScrollView } as? ScrollView
    }
}
