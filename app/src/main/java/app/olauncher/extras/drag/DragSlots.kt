package app.olauncher.extras.drag

import kotlin.math.max

/**
 * Pure maths for dragging things into another order, unit tested. Items are numbered as they are
 * now; an "order" lists those numbers in display order, and results are indexed by item number.
 */
object DragSlots {

    const val ROW_WEIGHT = 3f

    data class Size(val width: Int, val height: Int)
    data class Pos(val x: Int, val y: Int)
    enum class Align { LEFT, CENTER, RIGHT }

    /** Where each item sits, and how big the whole is. */
    class Placed(val positions: List<Pos>, val width: Int, val height: Int)

    /** The order after the item at [from] moves to [to], of [count] items. */
    fun moved(count: Int, from: Int, to: Int): List<Int> =
        (0 until count).toMutableList().apply { add(to, removeAt(from)) }

    /** Rows that wrap at [available], each aligned by [align] and centred vertically. [rtl]: first item on the right. */
    fun flow(
        sizes: List<Size>, order: List<Int>, available: Int, horizontalGap: Int, verticalGap: Int,
        align: Align = Align.LEFT, rtl: Boolean = false,
    ): Placed {
        val rows = mutableListOf<MutableList<Int>>()
        var rowWidth = 0
        var widest = 0
        order.forEach { i ->
            val width = sizes[i].width
            if (rows.isEmpty() || rowWidth + horizontalGap + width > available) {
                rows += mutableListOf(i)
                rowWidth = width
            } else {
                rows.last() += i
                rowWidth += horizontalGap + width
            }
            widest = max(widest, rowWidth)
        }
        val inner = if (available == Int.MAX_VALUE) widest else available
        val positions = MutableList(sizes.size) { Pos(0, 0) }
        var top = 0
        rows.forEachIndexed { r, row ->
            if (r > 0) top += verticalGap
            val width = row.sumOf { sizes[it].width } + horizontalGap * (row.size - 1)
            val height = row.maxOf { sizes[it].height }
            var x = when (align) {
                Align.LEFT -> 0
                Align.CENTER -> (inner - width) / 2
                Align.RIGHT -> inner - width
            }
            (if (rtl) row.reversed() else row).forEach { i ->
                positions[i] = Pos(x, top + (height - sizes[i].height) / 2)
                x += sizes[i].width + horizontalGap
            }
            top += height
        }
        return Placed(positions, widest, top)
    }

    /** One under the other. */
    fun column(heights: List<Int>, order: List<Int>): List<Pos> {
        val positions = MutableList(heights.size) { Pos(0, 0) }
        var y = 0
        order.forEach { i ->
            positions[i] = Pos(0, y)
            y += heights[i]
        }
        return positions
    }

    /**
     * Which of [candidates] the item at [from] should move to when its centre is at ([cx], [cy]):
     * the one whose slot, in the order it would give, is nearest. Only the centre decides, not the
     * order shown now, so it can't flicker between two places. The row counts more than the place
     * in it, so the empty space after the last item means "last". Ties keep the current place.
     */
    fun target(from: Int, candidates: List<Int>, sizes: List<Size>, cx: Float, cy: Float, slots: (List<Int>) -> List<Pos>): Int =
        target(from, from, candidates.filter { it in sizes.indices }, sizes, cx, cy, ROW_WEIGHT) { slots(moved(sizes.size, from, it)) }

    /**
     * The same for any kind of target (e.g. grid cells): [arrange] gives where every item sits for
     * a target, [start] is the target it has now.
     */
    fun target(
        from: Int, start: Int, candidates: List<Int>, sizes: List<Size>, cx: Float, cy: Float,
        rowWeight: Float, arrange: (Int) -> List<Pos>,
    ): Int {
        fun distance(to: Int): Float {
            val pos = arrange(to)[from]
            val dx = pos.x + sizes[from].width / 2f - cx
            val dy = (pos.y + sizes[from].height / 2f - cy) * rowWeight
            return dx * dx + dy * dy
        }
        var best = start
        var bestDistance = distance(start)
        candidates.forEach { to ->
            if (to == start) return@forEach
            val d = distance(to)
            if (d < bestDistance) { best = to; bestDistance = d }
        }
        return best
    }
}
