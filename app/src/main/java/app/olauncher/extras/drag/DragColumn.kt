package app.olauncher.extras.drag

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** A column whose rows can be dragged into another order. */
class DragColumn(context: Context) : LinearLayout(context) {

    init { orientation = VERTICAL }

    /**
     * Moves a row. The column's size stays the same, so only the rows are placed again: asking for
     * a whole layout makes a dialog's window wobble.
     */
    @SuppressLint("WrongCall")
    fun moveChild(from: Int, to: Int) {
        val child = getChildAt(from) ?: return
        detachViewFromParent(from)
        attachViewToParent(child, to, child.layoutParams)
        onLayout(true, left, top, right, bottom)
        invalidate()
    }

    /** Where every row would sit in [order] (row numbers). */
    fun slots(order: List<Int>): List<DragSlots.Pos> {
        val rows = (0 until childCount).map<Int, View> { getChildAt(it) }
        return DragSlots.column(rows.map { it.height }, order).map { DragSlots.Pos(it.x, it.y + paddingTop) }
    }
}
