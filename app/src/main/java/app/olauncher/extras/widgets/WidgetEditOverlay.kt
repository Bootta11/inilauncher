package app.olauncher.extras.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.olauncher.R
import app.olauncher.extras.drag.DragSession
import app.olauncher.extras.drag.EdgeScroller
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.beginTransition
import app.olauncher.helper.fadeVisible
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isEinkDisplay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Covers the widget panel while a widget is edited: a frame around it with three handles (end edge
 * = width, bottom edge = height, corner = both) and a bar with Settings and Remove. The frame
 * follows the finger; the widget steps along in whole columns and [WidgetGridLayout.HEIGHT_STEP_DP].
 * Its top and start edge can't move, as widgets pack from there. Outside the handles: a tap ends
 * it, a drag scrolls the panel, and a hold lifts the widget under the finger.
 */
class WidgetEditOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    interface Host {
        val scroll: ScrollView
        val grid: WidgetGrid
        fun hasSettings(cell: View): Boolean
        fun openSettings(cell: View)
        fun remove(cell: View)
        fun resized(cell: View)
        fun startDrag(cell: View): DragSession?
        fun onEditing(editing: Boolean)
    }

    private enum class Handle(val width: Boolean, val height: Boolean) { END(true, false), BOTTOM(false, true), CORNER(true, true) }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val inset = 4 * density // the cell's own side padding
    private val color = context.getColorFromAttr(R.attr.primaryColor)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = this@WidgetEditOverlay.color }
    /** Under the line and dots, so they show on any widget or wallpaper. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        color = context.getColorFromAttr(R.attr.primaryInverseColor)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@WidgetEditOverlay.color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColorFromAttr(R.attr.primaryInverseColor) }

    private lateinit var host: Host
    private var cell: View? = null

    /** A widget is being edited. */
    val editing get() = cell != null

    private val frame = RectF()
    /** The widget's own place now (it moves by itself while the panel scrolls or others make room). */
    private val actual = RectF()
    /** The frame glides onto the widget: how far its edges still are from the widget's when it starts. */
    private val offset = RectF()
    private var measureOffset = false
    private var excluded = false
    private var settle = 1f
    private var settleAnim: android.animation.ValueAnimator? = null
    private val here = IntArray(2)
    private val there = IntArray(2)

    private val settings = button(R.string.widgets_edit_settings) { cell?.let { host.openSettings(it) } }
    private val remove = button(R.string.widgets_remove) { cell?.let { host.remove(it) } }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundResource(R.drawable.rounded_rectangle_dark)
        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        addView(settings)
        addView(remove)
    }

    init {
        setWillNotDraw(false)
        contentDescription = context.getString(R.string.widgets_resize)
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)) // placed by translation, also right-to-left
    }

    private fun button(label: Int, action: () -> Unit) = TextView(context, null, 0, R.style.TextSmall).apply {
        setText(label)
        gravity = Gravity.CENTER
        minHeight = (44 * density).toInt()
        setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
        setOnClickListener { action() }
    }

    private val follow = ViewTreeObserver.OnPreDrawListener { track(); true }

    fun bind(host: Host) { this.host = host }

    /** Shows the frame around [target] (gliding there when another widget was being edited). */
    fun edit(target: View) {
        val previous = cell
        cell = target
        settings.visibility = if (host.hasSettings(target)) View.VISIBLE else View.GONE
        if (previous == null) {
            settleAnim?.cancel()
            settle = 1f
            measureOffset = false
            viewTreeObserver.removeOnPreDrawListener(follow)
            viewTreeObserver.addOnPreDrawListener(follow)
            host.onEditing(true)
        } else if (previous !== target) glide()
        bar.fadeVisible(true)
        fadeVisible(true)
        reveal(target)
        track()
    }

    /** Ends editing, with a fade. */
    fun done() {
        if (cell == null) return
        stopResize(save = true)
        forgetHold()
        cell = null
        viewTreeObserver.removeOnPreDrawListener(follow)
        ViewCompat.setSystemGestureExclusionRects(this, emptyList())
        excluded = false
        fadeVisible(false)
        host.onEditing(false)
    }

    override fun onDetachedFromWindow() {
        forgetHold()
        settleAnim?.cancel()
        viewTreeObserver.removeOnPreDrawListener(follow)
        super.onDetachedFromWindow()
    }

    /** Scrolls so the bottom handles of [target] are on the screen. */
    private fun reveal(target: View) {
        val scroll = host.scroll
        val bottom = host.grid.top + (host.grid.parent as View).top + target.bottom + (72 * density).toInt()
        if (bottom <= scroll.scrollY + scroll.height) return
        if (context.animationsOn()) scroll.smoothScrollTo(0, bottom - scroll.height) else scroll.scrollTo(0, bottom - scroll.height)
    }

    // ---- The frame ----

    /** Keeps the frame and the bar on the widget, wherever it is this frame. */
    private fun track() {
        val target = cell ?: return
        // It went away (its app was uninstalled)
        if (target.parent == null) return done()
        getLocationInWindow(here)
        target.getLocationInWindow(there)
        val x = (there[0] - here[0]).toFloat()
        val y = (there[1] - here[1]).toFloat()
        actual.set(x + inset, y, x + target.width - inset, y + target.height)
        val before = if (excluded) RectF(frame) else RectF()
        if (measureOffset) {
            measureOffset = false
            offset.set(frame.left - actual.left, frame.top - actual.top, frame.right - actual.right, frame.bottom - actual.bottom)
        }
        if (resizing != null) frameAtFinger()
        else {
            val left = 1f - settle
            frame.set(
                actual.left + offset.left * left, actual.top + offset.top * left,
                actual.right + offset.right * left, actual.bottom + offset.bottom * left,
            )
        }
        val barX = (frame.centerX() - bar.width / 2f).coerceIn(inset, (width - bar.width - inset).coerceAtLeast(inset))
        val top = 36 * density
        val barY = (frame.top - bar.height - 10 * density).coerceIn(top, (height - bar.height - top).coerceAtLeast(top))
        if (bar.translationX != barX) bar.translationX = barX
        if (bar.translationY != barY) bar.translationY = barY
        if (before != frame) {
            invalidate()
            keepBackGestureOff()
        }
    }

    /** A wide widget's end handles lie where Android's Back swipe starts; a drag there is for the handle. */
    private fun keepBackGestureOff() {
        excluded = true
        val reach = (32 * density).toInt()
        ViewCompat.setSystemGestureExclusionRects(this, listOf(Handle.END, Handle.CORNER).map {
            val x = handleX(it).toInt()
            val y = handleY(it).toInt()
            Rect(x - reach, y - reach, x + reach, y + reach)
        })
    }

    /** The frame glides from where it is onto the widget, and moves with it meanwhile. */
    private fun glide() {
        settleAnim?.cancel()
        settle = 1f
        if (!context.animationsOn()) return invalidate()
        measureOffset = true
        settle = 0f
        settleAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.medium(context)
            interpolator = Motion.easing
            addUpdateListener {
                settle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val endX get() = if (rtl) frame.left else frame.right
    /** The end handle stays in the part of the frame that's on the screen. */
    private val endY get() = (frame.top.coerceAtLeast(0f) + frame.bottom.coerceAtMost(height.toFloat())) / 2

    private fun handleX(handle: Handle) = if (handle.width) endX else frame.centerX()
    private fun handleY(handle: Handle) = if (handle.height) frame.bottom else endY

    private fun handleAt(x: Float, y: Float): Handle? =
        Handle.entries.minByOrNull { hypot(handleX(it) - x, handleY(it) - y) }
            ?.takeIf { hypot(handleX(it) - x, handleY(it) - y) <= 32 * density }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 12 * density
        canvas.drawRoundRect(frame, radius, radius, halo)
        canvas.drawRoundRect(frame, radius, radius, line)
        Handle.entries.forEach {
            val size = (if (it == resizing) 10 else 8) * density
            canvas.drawCircle(handleX(it), handleY(it), size + 2 * density, ring)
            canvas.drawCircle(handleX(it), handleY(it), size, dot)
        }
    }

    // ---- Resizing ----

    private var resizing: Handle? = null
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var maxSpan = WidgetStore.COLUMNS
    private var edge: EdgeScroller? = null

    private val maxHeightDp get() = (host.scroll.height / density).toInt()

    private fun startResize(handle: Handle, event: MotionEvent) {
        val target = cell ?: return
        settleAnim?.cancel()
        settle = 1f
        measureOffset = false
        resizing = handle
        fingerX = event.x
        fingerY = event.y
        // Keep the finger as far from the edge as it landed
        grabX = event.x - endX
        grabY = event.y - frame.bottom
        val column = host.grid.columnOf(target)
        maxSpan = WidgetStore.COLUMNS - column
        host.grid.rowStart = if (column == 0) target else null
        removeCallbacks(unreserve)
        host.grid.reservedHeight = host.grid.height
        edge = if (handle.height) EdgeScroller(host.scroll, zoneDp = 28) { track(); step() } else null
        bar.fadeVisible(false)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** The edges being dragged are at the finger, within the sizes a widget may have. */
    private fun frameAtFinger() {
        val handle = resizing ?: return
        val grid = host.grid
        frame.set(actual)
        if (handle.width) {
            val min = WidgetGridLayout.edge(1, grid.width) - 2 * inset
            val max = WidgetGridLayout.edge(maxSpan, grid.width) - 2 * inset
            if (rtl) frame.left = (fingerX - grabX).coerceIn(frame.right - max, frame.right - min)
            else frame.right = (fingerX - grabX).coerceIn(frame.left + min, frame.left + max)
        }
        if (handle.height) {
            val min = WidgetGridLayout.MIN_HEIGHT_DP * density
            frame.bottom = (fingerY - grabY).coerceIn(frame.top + min, frame.top + (maxHeightDp * density).coerceAtLeast(min))
        }
    }

    /** The widget takes the nearest size it can have; the others make room. */
    private fun step() {
        val target = cell ?: return
        val handle = resizing ?: return
        val grid = host.grid
        val params = grid.cell(target)
        val span = if (handle.width) WidgetGridLayout.snapSpan(frame.width() + 2 * inset, grid.width, maxSpan) else params.span
        val height = if (handle.height) WidgetGridLayout.snapHeight(frame.height() / density, maxHeightDp) else params.heightDp
        if (span == params.span && height == params.heightDp) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        transition()
        params.span = span
        params.heightDp = height
        grid.requestLayout()
    }

    private fun transition() = (host.grid.parent as ViewGroup).beginTransition(excludeInside = host.grid.cells)

    private fun stopResize(save: Boolean) {
        val target = cell ?: return
        if (resizing == null) return
        resizing = null
        edge?.stop()
        edge = null
        glide()
        transition()
        host.grid.rowStart = null
        host.grid.requestLayout()
        giveBackRoom()
        if (save) host.resized(target)
        bar.fadeVisible(true)
    }

    private val unreserve = Runnable {
        if (host.grid.reservedHeight == 0) return@Runnable
        transition()
        host.grid.reservedHeight = 0
        host.grid.requestLayout()
    }

    /**
     * Lets the panel shrink to its widgets again. When it's scrolled further than that leaves, it
     * scrolls back first, or the widgets would jump down.
     */
    private fun giveBackRoom() {
        val scroll = host.scroll
        val content = scroll.getChildAt(0).height - (host.grid.height - host.grid.neededHeight()).coerceAtLeast(0)
        val furthest = (content - scroll.height).coerceAtLeast(0)
        if (scroll.scrollY <= furthest || !context.animationsOn()) {
            removeCallbacks(unreserve)
            host.grid.reservedHeight = 0
            return
        }
        scroll.smoothScrollTo(0, furthest)
        postDelayed(unreserve, 300)
    }

    // ---- Touches ----

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var velocity: VelocityTracker? = null
    private var session: DragSession? = null
    private var sessionCell: View? = null
    private var hold: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cell == null) return false
                downX = event.x
                downY = event.y
                lastY = event.y
                moved = false
                session = null
                val handle = handleAt(event.x, event.y)
                if (handle != null) startResize(handle, event) else watchHold(event)
            }
            MotionEvent.ACTION_MOVE -> when {
                resizing != null -> {
                    fingerX = event.x
                    fingerY = event.y
                    track()
                    step()
                    edge?.update(event.rawY)
                }
                session != null -> session?.move(event.rawX, event.rawY)
                else -> scrollPanel(event)
            }
            MotionEvent.ACTION_UP -> {
                forgetHold()
                val drag = session
                when {
                    resizing != null -> stopResize(save = true)
                    drag != null -> if (!drag.release()) sessionCell?.let { edit(it) }
                    moved -> fling()
                    !frame.contains(event.x, event.y) -> done()
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                forgetHold()
                stopResize(save = true)
                session?.cancel()
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        session = null
        sessionCell = null
        velocity?.recycle()
        velocity = null
    }

    private fun watchHold(event: MotionEvent) {
        forgetHold()
        host.scroll.fling(0) // a panel still scrolling stops under the finger
        velocity = VelocityTracker.obtain().apply { addMovement(event) }
        val rawX = event.rawX
        val rawY = event.rawY
        hold = Runnable {
            hold = null
            val under = cellAt(downX, downY) ?: return@Runnable
            val drag = host.startDrag(under) ?: return@Runnable
            done()
            session = drag
            sessionCell = under
            drag.lift(rawX, rawY)
        }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
    }

    private fun forgetHold() {
        hold?.let { removeCallbacks(it) }
        hold = null
    }

    private fun cellAt(x: Float, y: Float): View? {
        getLocationInWindow(here)
        return host.grid.cells.firstOrNull {
            it.getLocationInWindow(there)
            val left = there[0] - here[0]
            val top = there[1] - here[1]
            x >= left && x < left + it.width && y >= top && y < top + it.height
        }
    }

    private fun scrollPanel(event: MotionEvent) {
        velocity?.addMovement(event)
        if (!moved) {
            if (hypot(event.x - downX, event.y - downY) <= slop) return
            moved = true
            forgetHold()
            lastY = event.y
            return
        }
        host.scroll.scrollBy(0, (lastY - event.y).roundToInt())
        lastY = event.y
    }

    private fun fling() {
        val tracker = velocity ?: return
        val config = ViewConfiguration.get(context)
        tracker.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        val speed = tracker.yVelocity
        if (abs(speed) > config.scaledMinimumFlingVelocity && !context.isEinkDisplay()) host.scroll.fling(-speed.toInt())
    }
}
