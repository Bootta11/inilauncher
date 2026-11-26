package app.olauncher.extras

import app.olauncher.extras.drag.DragSlots
import app.olauncher.extras.drag.DragSlots.Align
import app.olauncher.extras.drag.DragSlots.Pos
import app.olauncher.extras.drag.DragSlots.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class DragSlotsTest {

    private val cell = Size(100, 50)
    private fun flow(sizes: List<Size>, order: List<Int> = sizes.indices.toList(), align: Align = Align.LEFT, rtl: Boolean = false) =
        DragSlots.flow(sizes, order, 320, 10, 5, align, rtl)

    @Test
    fun wrapsIntoRows() {
        val placed = flow(List(4) { cell })
        assertEquals(listOf(Pos(0, 0), Pos(110, 0), Pos(220, 0), Pos(0, 55)), placed.positions)
        assertEquals(105, placed.height)
        assertEquals(320, placed.width)
    }

    @Test
    fun alignsAndMirrorsRows() {
        val two = List(2) { cell }
        assertEquals(listOf(Pos(55, 0), Pos(165, 0)), flow(two, align = Align.CENTER).positions)
        assertEquals(listOf(Pos(110, 0), Pos(220, 0)), flow(two, align = Align.RIGHT).positions)
        assertEquals(listOf(Pos(220, 0), Pos(110, 0)), flow(two, align = Align.RIGHT, rtl = true).positions)
    }

    @Test
    fun emptyAndUnlimitedWidth() {
        assertEquals(0, flow(emptyList()).height)
        val placed = DragSlots.flow(List(5) { cell }, (0..4).toList(), Int.MAX_VALUE, 10, 5)
        assertEquals(540, placed.width)
        assertEquals(50, placed.height)
    }

    @Test
    fun followsTheOrder() {
        val sizes = listOf(Size(200, 50), Size(50, 30), Size(100, 50))
        val placed = flow(sizes, listOf(1, 2, 0))
        // 50 + 10 + 100, then 200 doesn't fit beside them
        assertEquals(listOf(Pos(0, 55), Pos(0, 10), Pos(60, 0)), placed.positions)
        assertEquals(listOf(Pos(0, 50), Pos(0, 0), Pos(0, 20)), DragSlots.column(listOf(10, 20, 30), listOf(1, 2, 0)))
    }

    @Test
    fun movedOrder() {
        assertEquals(listOf(1, 2, 0, 3), DragSlots.moved(4, 0, 2))
        assertEquals(listOf(3, 0, 1, 2), DragSlots.moved(4, 3, 0))
    }

    @Test
    fun targetIsTheNearestSlot() {
        val sizes = List(5) { cell }
        val slots = { order: List<Int> -> flow(sizes, order).positions }
        val all = sizes.indices.toList()
        assertEquals(0, DragSlots.target(0, all, sizes, 50f, 25f, slots))
        assertEquals(2, DragSlots.target(0, all, sizes, 265f, 30f, slots))
        assertEquals(3, DragSlots.target(0, all, sizes, 40f, 75f, slots))
        // Past the last one: the last place
        assertEquals(4, DragSlots.target(0, all, sizes, 300f, 200f, slots))
        assertEquals(4, DragSlots.target(0, all, sizes, 300f, 88f, slots))
        // Half way keeps the current place
        assertEquals(1, DragSlots.target(1, all, sizes, 105f, 25f, slots))
    }

    @Test
    fun targetStaysInCandidates() {
        val sizes = List(5) { cell }
        val slots = { order: List<Int> -> flow(sizes, order).positions }
        // A most-used app (4) may go among the three picked, right after them, or stay
        assertEquals(1, DragSlots.target(4, (0..3).toList(), sizes, 160f, 25f, slots))
        assertEquals(4, DragSlots.target(4, (0..3).toList(), sizes, 170f, 80f, slots))
        // A picked app can't go past the picked ones
        assertEquals(2, DragSlots.target(0, (0..2).toList(), sizes, 250f, 80f, slots))
    }

    @Test
    fun differentHeights() {
        val heights = listOf(400, 100)
        val sizes = heights.map { Size(300, it) }
        val slots = { order: List<Int> -> DragSlots.column(heights, order) }
        assertEquals(0, DragSlots.target(0, listOf(0, 1), sizes, 150f, 200f + 49, slots))
        assertEquals(1, DragSlots.target(0, listOf(0, 1), sizes, 150f, 200f + 51, slots))
    }
}
