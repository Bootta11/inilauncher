package app.olauncher.extras.widgets

import kotlin.math.roundToInt

/** Pure maths for the widget panel's grid, unit tested. */
object WidgetGridLayout {

    const val MIN_HEIGHT_DP = 60
    /** Heights snap to this while resizing, so neighbours are easy to match. */
    const val HEIGHT_STEP_DP = 20

    /** A widget's first column and its top. */
    data class Cell(val column: Int, val y: Int)
    class Placed(val cells: List<Cell>, val height: Int)

    /**
     * Places widgets ([spans] in columns, [heights]) in rows, in [order] (widget numbers); [rowGap]
     * above each row. Results are indexed by widget number. [rowStart]: a widget that starts a new
     * row even if it would fit in the one before.
     */
    fun place(spans: List<Int>, heights: List<Int>, rowGap: Int, order: List<Int> = spans.indices.toList(), rowStart: Int = -1): Placed {
        val cells = MutableList(spans.size) { Cell(0, 0) }
        var top = 0
        WidgetStore.rows(order, { it == rowStart }) { spans[it] }.forEach { row ->
            top += rowGap
            var column = 0
            row.forEach { i ->
                cells[i] = Cell(column, top)
                column += spans[i].coerceIn(1, WidgetStore.COLUMNS)
            }
            top += row.maxOf { heights[it] }
        }
        return Placed(cells, top)
    }

    /** The edge of [column] in a grid [width] wide. */
    fun edge(column: Int, width: Int): Int = (column.toFloat() * width / WidgetStore.COLUMNS).roundToInt()

    /** Whole columns nearest to [width], at most [max]. */
    fun snapSpan(width: Float, gridWidth: Int, max: Int): Int =
        if (gridWidth <= 0) 1 else (width * WidgetStore.COLUMNS / gridWidth).roundToInt().coerceIn(1, max.coerceAtLeast(1))

    /** The height step nearest to [heightDp], between the smallest and [maxDp]. */
    fun snapHeight(heightDp: Float, maxDp: Int): Int {
        val top = (maxDp / HEIGHT_STEP_DP * HEIGHT_STEP_DP).coerceAtLeast(MIN_HEIGHT_DP)
        return ((heightDp / HEIGHT_STEP_DP).roundToInt() * HEIGHT_STEP_DP).coerceIn(MIN_HEIGHT_DP, top)
    }
}
