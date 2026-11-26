package app.olauncher.extras.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import app.olauncher.extras.drag.DragSlots
import kotlin.math.roundToInt

/**
 * The widget panel's 4-column grid. Its children are the widgets' cells ([WidgetTouchFrame]) in
 * order, each with a [Cell] telling how many columns and how tall. Cells stay the same views when
 * the order or sizes change, so nothing blinks.
 */
class WidgetGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    class Cell(val id: Int, var span: Int, var heightDp: Int) : LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private val density = resources.displayMetrics.density
    private val rowGap = (12 * density).toInt()

    /** While it's resized: this cell keeps starting its row (instead of jumping up when it gets narrow). */
    var rowStart: View? = null

    /** While resizing: never shorter than this, so the panel can't scroll away under the finger. */
    var reservedHeight = 0

    val cells: List<View> get() = (0 until childCount).map { getChildAt(it) }

    fun cell(view: View) = view.layoutParams as Cell

    fun columnOf(view: View): Int = placed().cells.getOrNull(indexOfChild(view))?.column ?: 0

    private fun heightPx(view: View) = (cell(view).heightDp * density).roundToInt()

    private fun placed(order: List<Int> = (0 until childCount).toList()): WidgetGridLayout.Placed {
        val views = cells
        return WidgetGridLayout.place(views.map { cell(it).span }, views.map { heightPx(it) }, rowGap, order, rowStart?.let { indexOfChild(it) } ?: -1)
    }

    private fun left(column: Int, span: Int, width: Int): Int =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) width - WidgetGridLayout.edge(column + span, width)
        else WidgetGridLayout.edge(column, width)

    /** The height its widgets need now, whatever is reserved. */
    fun neededHeight() = placed().height

    /** Where every cell would sit in [order] (child indices), for dragging. */
    fun slots(order: List<Int>): List<DragSlots.Pos> =
        placed(order).cells.mapIndexed { i, at -> DragSlots.Pos(left(at.column, cell(getChildAt(i)).span, width), at.y) }

    /** Moves a cell to another place without taking it off the screen. */
    fun moveChild(from: Int, to: Int) {
        val child = getChildAt(from) ?: return
        detachViewFromParent(from)
        attachViewToParent(child, to, child.layoutParams)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val placed = placed()
        cells.forEachIndexed { i, view ->
            val column = placed.cells[i].column
            val w = WidgetGridLayout.edge(column + cell(view).span, width) - WidgetGridLayout.edge(column, width)
            view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(heightPx(view), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, placed.height.coerceAtLeast(reservedHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val placed = placed()
        cells.forEachIndexed { i, view ->
            val x = left(placed.cells[i].column, cell(view).span, r - l)
            val y = placed.cells[i].y
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is Cell
    override fun generateDefaultLayoutParams(): LayoutParams = Cell(0, 1, WidgetGridLayout.MIN_HEIGHT_DP)
}
