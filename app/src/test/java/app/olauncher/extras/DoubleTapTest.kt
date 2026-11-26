package app.olauncher.extras

import app.olauncher.extras.search.DoubleTap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapTest {

    private fun detector() = DoubleTap(timeoutMs = 300, tapSlop = 10f, doubleSlop = 100f)

    @Test
    fun twoQuickTapsAreADoubleTap() {
        val d = detector()
        assertFalse(d.down(0, 500f)); d.up(80, 502f)
        assertTrue(d.down(200, 530f))
    }

    @Test
    fun tooSlowOrTooFarIsNot() {
        val d = detector()
        d.down(0, 500f); d.up(80, 500f)
        assertFalse(d.down(500, 500f))  // too late
        d.up(560, 500f)
        assertFalse(d.down(700, 800f))  // too far from the first tap
    }

    @Test
    fun aDragIsNeverTheFirstTap() {
        val d = detector()
        d.down(0, 500f); d.move(700f); d.up(150, 700f)
        assertFalse(d.down(250, 700f))
    }

    @Test
    fun longPressIsNotATap() {
        val d = detector()
        d.down(0, 500f); d.up(600, 500f)
        assertFalse(d.down(700, 500f))
    }

    @Test
    fun thirdTapStartsOver() {
        val d = detector()
        d.down(0, 500f); d.up(80, 500f)
        assertTrue(d.down(150, 500f)); d.up(220, 500f)
        assertFalse(d.down(300, 500f))
    }
}
