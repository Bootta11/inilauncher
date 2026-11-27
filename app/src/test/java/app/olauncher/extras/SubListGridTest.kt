package app.olauncher.extras

import app.olauncher.extras.sublist.SubListGrid
import app.olauncher.extras.sublist.SubListGrid.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class SubListGridTest {

    @Test
    fun columnsFitWithGaps() {
        assertEquals(4, SubListGrid.columns(available = 330, cellWidth = 76, gap = 6))
        assertEquals(3, SubListGrid.columns(available = 300, cellWidth = 96, gap = 6))
        assertEquals(1, SubListGrid.columns(available = 10, cellWidth = 76, gap = 6))
    }

    @Test
    fun nothingSavedKeepsTheOrder() {
        assertEquals(listOf(0, 1, 2), SubListGrid.arrange(listOf("a", "b", "c"), emptyMap(), 4))
    }

    @Test
    fun savedCellsKeepTheirGaps() {
        val saved = mapOf("a" to Cell(0, 0), "b" to Cell(1, 0))
        assertEquals(listOf(0, 4, 1), SubListGrid.arrange(listOf("a", "b", "c"), saved, 4))
    }

    @Test
    fun cellsTooFarRightFillTheFirstGaps() {
        val saved = mapOf("a" to Cell(0, 3), "b" to Cell(0, 1))
        // Three columns now: "a" no longer fits and goes to the first free cell
        assertEquals(listOf(0, 1), SubListGrid.arrange(listOf("a", "b"), saved, 3))
    }

    @Test
    fun aTakenCellGoesToTheFirstApp() {
        val saved = mapOf("a" to Cell(0, 2), "b" to Cell(0, 2))
        assertEquals(listOf(2, 0), SubListGrid.arrange(listOf("a", "b"), saved, 4))
    }

    @Test
    fun swapTradesPlaces() {
        assertEquals(listOf(4, 1, 0), SubListGrid.swap(listOf(0, 1, 4), from = 0, to = 4))
    }

    @Test
    fun swapIntoAnEmptyCellMovesOnlyOne() {
        assertEquals(listOf(0, 5, 2), SubListGrid.swap(listOf(0, 1, 2), from = 1, to = 5))
    }

    @Test
    fun rowsAndReadingOrder() {
        assertEquals(2, SubListGrid.rows(listOf(0, 4, 1), 4))
        assertEquals(0, SubListGrid.rows(emptyList(), 4))
        assertEquals(listOf(0, 2, 1), SubListGrid.readingOrder(listOf(0, 4, 1)))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val cells = mapOf("com.a|0" to Cell(0, 1), "com.b|10" to Cell(2, 0))
        assertEquals(cells, SubListGrid.decode(SubListGrid.encode(cells)))
        assertEquals(emptyMap<String, Cell>(), SubListGrid.decode(null))
        assertEquals(emptyMap<String, Cell>(), SubListGrid.decode("junk;x=1"))
    }
}
