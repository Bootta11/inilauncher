package app.olauncher.extras

import app.olauncher.extras.widgets.PlacedWidget
import app.olauncher.extras.widgets.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStoreTest {

    @Test
    fun roundTrip() {
        val widgets = listOf(PlacedWidget(5, 0), PlacedWidget(9, 180, 2))
        assertEquals(widgets, WidgetStore.decode(WidgetStore.encode(widgets)))
    }

    @Test
    fun toleratesOldAndBadValues() {
        assertTrue(WidgetStore.decode(null).isEmpty())
        assertTrue(WidgetStore.decode("").isEmpty())
        assertEquals(listOf(PlacedWidget(3, 0), PlacedWidget(4, 0)), WidgetStore.decode("3, x:1, 4:-5, 3:200"))
        assertEquals(listOf(PlacedWidget(7, 120, 4)), WidgetStore.decode("7:120:9"))
    }

    @Test
    fun autoSpan() {
        assertEquals(1, WidgetStore.autoSpan(40f, 90f))
        assertEquals(2, WidgetStore.autoSpan(110f, 90f))
        assertEquals(4, WidgetStore.autoSpan(500f, 90f))
    }

    @Test
    fun packsIntoRows() {
        val rows = WidgetStore.rows(listOf(1, 1, 2, 3, 4, 1)) { it }
        assertEquals(listOf(listOf(1, 1, 2), listOf(3), listOf(4), listOf(1)), rows)
    }
}
