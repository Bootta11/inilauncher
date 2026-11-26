package app.olauncher.extras

import app.olauncher.extras.widgets.PlacedWidget
import app.olauncher.extras.widgets.WidgetGridLayout
import app.olauncher.extras.widgets.WidgetGridLayout.Cell
import app.olauncher.extras.widgets.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridLayoutTest {

    @Test
    fun packsRowsWithTheTallestOnTop() {
        val placed = WidgetGridLayout.place(listOf(2, 2, 4), listOf(100, 150, 80), 12)
        assertEquals(listOf(Cell(0, 12), Cell(2, 12), Cell(0, 174)), placed.cells)
        assertEquals(254, placed.height)
        assertEquals(0, WidgetGridLayout.place(emptyList(), emptyList(), 12).height)
    }

    @Test
    fun followsTheOrder() {
        val placed = WidgetGridLayout.place(listOf(2, 2, 4), listOf(100, 150, 80), 12, order = listOf(2, 1, 0))
        assertEquals(listOf(Cell(2, 104), Cell(0, 104), Cell(0, 12)), placed.cells)
    }

    @Test
    fun resizedWidgetKeepsItsRow() {
        // Narrow enough to fit beside the first, but it stays where it is while being resized
        assertEquals(Cell(0, 124), WidgetGridLayout.place(listOf(2, 2), listOf(100, 100), 12, rowStart = 1).cells[1])
        assertEquals(Cell(2, 12), WidgetGridLayout.place(listOf(2, 2), listOf(100, 100), 12).cells[1])
        assertEquals(Cell(0, 12), WidgetGridLayout.place(listOf(2, 2), listOf(100, 100), 12, rowStart = 0).cells[0])
    }

    @Test
    fun snapsWidths() {
        assertEquals(1, WidgetGridLayout.snapSpan(10f, 400, 4))
        assertEquals(2, WidgetGridLayout.snapSpan(160f, 400, 4))
        assertEquals(3, WidgetGridLayout.snapSpan(390f, 400, 3))
        assertEquals(1, WidgetGridLayout.snapSpan(100f, 0, 4))
        assertEquals(listOf(0, 101, 203, 304, 405), (0..4).map { WidgetGridLayout.edge(it, 405) })
    }

    @Test
    fun snapsHeights() {
        assertEquals(60, WidgetGridLayout.snapHeight(5f, 700))
        assertEquals(120, WidgetGridLayout.snapHeight(112f, 700))
        assertEquals(700, WidgetGridLayout.snapHeight(2000f, 709))
        assertEquals(60, WidgetGridLayout.snapHeight(100f, 30))
    }

    @Test
    fun movesWidgets() {
        val widgets = listOf(1, 2, 3).map { PlacedWidget(it, 0) }
        assertEquals(listOf(2, 3, 1), WidgetStore.moved(widgets, 1, 2).map { it.id })
        assertEquals(listOf(3, 1, 2), WidgetStore.moved(widgets, 3, 0).map { it.id })
        assertEquals(widgets, WidgetStore.moved(widgets, 9, 0))
        assertEquals(widgets, WidgetStore.moved(widgets, 1, 3))
        assertEquals(widgets, WidgetStore.moved(widgets, 1, -1))
    }
}
