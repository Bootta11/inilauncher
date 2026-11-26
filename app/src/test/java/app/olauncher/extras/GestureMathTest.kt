package app.olauncher.extras

import app.olauncher.extras.gestures.GestureMath
import app.olauncher.extras.gestures.GestureMath.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    @Test
    fun axisWaitsForSlop() {
        assertEquals(Axis.NONE, GestureMath.axis(5f, -5f, 10f))
        assertEquals(Axis.HORIZONTAL, GestureMath.axis(-30f, 12f, 10f))
        assertEquals(Axis.VERTICAL, GestureMath.axis(4f, 20f, 10f))
    }

    @Test
    fun rubberBandFollowsThenFlattens() {
        assertEquals(0f, GestureMath.rubberBand(0f, 100f), 0f)
        // Small drags follow at about the ratio
        assertEquals(5f, GestureMath.rubberBand(10f, 100f, 0.5f), 0.2f)
        // Keeps the direction
        assertTrue(GestureMath.rubberBand(-50f, 100f) < 0f)
        // Grows with the finger but never passes max
        var last = 0f
        for (d in listOf(10f, 100f, 400f, 2000f, 10000f)) {
            val out = GestureMath.rubberBand(d, 100f)
            assertTrue(out > last)
            assertTrue(out < 100f + 1e-3f)
            last = out
        }
        assertEquals(0f, GestureMath.rubberBand(50f, 0f), 0f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0.5f, GestureMath.progress(-50f, 100f), 1e-6f)
        assertEquals(1f, GestureMath.progress(500f, 100f), 0f)
        assertEquals(0f, GestureMath.progress(5f, 0f), 0f)
    }
}
