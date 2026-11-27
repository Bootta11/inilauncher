package app.olauncher.extras.sublist

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.olauncher.extras.drag.DragSlots
import kotlin.math.max
import kotlin.math.min

/**
 * Lays its children out in rows that wrap, each row aligned like the home apps
 * ([alignment]: start, center or end). Used for every look of the home sub list.
 *
 * With "Free placement" ([setGrid]) every child instead sits in its own cell of a grid of
 * [cellWidth]-wide columns, and cells may stay empty. The grid as a whole is aligned.
 */
class SubListFlow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    var alignment: Int = Gravity.START
        set(value) { field = value; requestLayout() }
    var horizontalGap = 0
    var verticalGap = 0

    /** Free placement: every child's key, in child order, and the saved cells. Null: flow. */
    private var gridKeys: List<String>? = null
    private var gridSaved: Map<String, SubListGrid.Cell> = emptyMap()
    var cellWidth = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** One more empty row below, to drop an app on while dragging. */
    var extraRow = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** Columns and every child's cell, as last measured (free placement only). */
    var columns = 1
        private set
    var cells: List<Int> = emptyList()
        private set

    val isGrid get() = gridKeys != null

    fun sameGrid(keys: List<String>?, saved: Map<String, SubListGrid.Cell>) =
        keys == gridKeys && (keys == null || saved == gridSaved)

    /** Turns free placement on ([keys] per child) or off (null). False when nothing changed. */
    fun setGrid(keys: List<String>?, saved: Map<String, SubListGrid.Cell>): Boolean {
        if (sameGrid(keys, saved)) return false
        gridKeys = keys
        gridSaved = saved
        if (keys != null) cells = SubListGrid.arrange(keys, saved, columns)
        requestLayout()
        return true
    }

    private fun gridColumns(available: Int) = SubListGrid.columns(available, cellWidth, horizontalGap)

    /** Positions of the children for [cells] in a grid of [cols], and its height. */
    private fun gridPlace(cells: List<Int>, cols: Int, available: Int, extra: Boolean): DragSlots.Placed {
        val rowHeight = (0 until childCount).maxOfOrNull { getChildAt(it).measuredHeight } ?: 0
        val block = cols * cellWidth + (cols - 1) * horizontalGap
        val inner = if (available == Int.MAX_VALUE) block else available
        val start = when (Gravity.getAbsoluteGravity(alignment, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.CENTER_HORIZONTAL -> (inner - block) / 2
            Gravity.RIGHT -> inner - block
            else -> 0
        }
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val positions = cells.mapIndexed { i, cell ->
            val col = cell % cols
            val shownCol = if (rtl) cols - 1 - col else col
            val child = getChildAt(i) ?: return@mapIndexed DragSlots.Pos(0, 0)
            DragSlots.Pos(
                start + shownCol * (cellWidth + horizontalGap) + (cellWidth - child.measuredWidth) / 2,
                (cell / cols) * (rowHeight + verticalGap) + (rowHeight - child.measuredHeight) / 2,
            )
        }
        val rows = SubListGrid.rows(cells, cols) + if (extra) 1 else 0
        val height = if (rows == 0) 0 else rows * rowHeight + (rows - 1) * verticalGap
        return DragSlots.Placed(positions, block, height)
    }

    /** Where every child would sit with [cells] (free placement), for dragging. */
    fun slotsForCells(cells: List<Int>): List<DragSlots.Pos> =
        gridPlace(cells, columns, width - paddingLeft - paddingRight, extraRow).positions
            .map { DragSlots.Pos(it.x + paddingLeft, it.y + paddingTop) }

    /** Rows shown now (free placement), including the [extraRow]. */
    val gridRows get() = SubListGrid.rows(cells, columns) + if (extraRow) 1 else 0

    private fun shown() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }

    private fun place(views: List<View>, order: List<Int>, available: Int) = DragSlots.flow(
        views.map { DragSlots.Size(it.measuredWidth, it.measuredHeight) }, order, available, horizontalGap, verticalGap,
        when (Gravity.getAbsoluteGravity(alignment, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.CENTER_HORIZONTAL -> DragSlots.Align.CENTER
            Gravity.RIGHT -> DragSlots.Align.RIGHT
            else -> DragSlots.Align.LEFT
        },
        rtl = layoutDirection == LAYOUT_DIRECTION_RTL,
    )

    /** Where every child would sit in [order] (child indices), for dragging. */
    fun slots(order: List<Int>): List<DragSlots.Pos> =
        place(shown(), order, width - paddingLeft - paddingRight).positions.map { DragSlots.Pos(it.x + paddingLeft, it.y + paddingTop) }

    /** Moves a child to another place without taking it off the screen, so nothing blinks. */
    fun moveChild(from: Int, to: Int) {
        val child = getChildAt(from) ?: return
        detachViewFromParent(from)
        attachViewToParent(child, to, child.layoutParams)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: Int.MAX_VALUE
        val available = if (maxWidth == Int.MAX_VALUE) maxWidth else max(maxWidth - paddingLeft - paddingRight, 0)
        val keys = gridKeys
        if (keys != null) {
            columns = gridColumns(available.coerceAtMost(MAX_MEASURE))
            cells = SubListGrid.arrange(keys, gridSaved, columns)
            (0 until childCount).forEach {
                getChildAt(it).measure(
                    MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            val placed = gridPlace(cells, columns, available, extraRow)
            val width = if (maxWidth == Int.MAX_VALUE) placed.width + paddingLeft + paddingRight else maxWidth
            setMeasuredDimension(width, resolveSize(placed.height + paddingTop + paddingBottom, heightMeasureSpec))
            return
        }
        val views = shown()
        views.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(available.coerceAtMost(MAX_MEASURE), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        val placed = place(views, views.indices.toList(), available)
        val width = if (maxWidth == Int.MAX_VALUE) placed.width + paddingLeft + paddingRight else maxWidth
        setMeasuredDimension(width, resolveSize(placed.height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (isGrid) {
            slotsForCells(cells).forEachIndexed { i, pos ->
                val child = getChildAt(i) ?: return@forEachIndexed
                child.layout(pos.x, pos.y, pos.x + child.measuredWidth, pos.y + child.measuredHeight)
            }
            return
        }
        val views = shown()
        place(views, views.indices.toList(), (r - l) - paddingLeft - paddingRight).positions.forEachIndexed { i, pos ->
            val x = pos.x + paddingLeft
            val y = pos.y + paddingTop
            views[i].layout(x, y, x + views[i].measuredWidth, y + views[i].measuredHeight)
        }
    }

    private companion object {
        /** The largest size a MeasureSpec can hold. */
        const val MAX_MEASURE = (1 shl 30) - 1
    }
}

/**
 * A ScrollView no taller than [maxHeight] that only takes touches when it has something to
 * scroll, so swipes on the home screen around a short sub list still reach upstream's gestures.
 */
class SubListScroll @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    var maxHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight <= 0) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
        else min(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    private val scrollable get() = canScrollVertically(1) || canScrollVertically(-1)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = scrollable && super.onInterceptTouchEvent(ev)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = scrollable && super.onTouchEvent(ev)
}
