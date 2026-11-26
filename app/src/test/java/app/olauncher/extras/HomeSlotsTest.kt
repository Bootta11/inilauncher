package app.olauncher.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSlotsTest {

    @Test
    fun moveDownShiftsTheOnesBetweenUp() {
        // Slot 1 → 3: new 1 = old 2, new 2 = old 3, new 3 = old 1
        assertEquals(mapOf(1 to 2, 2 to 3, 3 to 1), HomeSlots.sources(1, 3))
    }

    @Test
    fun moveUpShiftsTheOnesBetweenDown() {
        // Slot 4 → 2: new 2 = old 4, new 3 = old 2, new 4 = old 3
        assertEquals(mapOf(2 to 4, 3 to 2, 4 to 3), HomeSlots.sources(4, 2))
    }

    @Test
    fun neighboursSwapAndSamePlaceIsNoChange() {
        assertEquals(mapOf(2 to 3, 3 to 2), HomeSlots.sources(2, 3))
        assertTrue(HomeSlots.sources(5, 5).isEmpty())
    }
}
