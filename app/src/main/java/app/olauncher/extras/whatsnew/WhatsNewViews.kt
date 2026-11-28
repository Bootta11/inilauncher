package app.olauncher.extras.whatsnew

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

/**
 * A screenshot that keeps its picture's shape: as wide as the page, unless that would make it taller
 * than [maxHeightPx], then narrower. Its size is known from [aspect] before the bitmap loads, so the
 * dialog can size every page up front. Rounded corners with a thin outline, so a dark screenshot
 * still reads on a dark dialog.
 */
class ScreenshotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    /** Height / width of the picture; 0 = no picture. */
    var aspect = 0f
        set(value) { if (field != value) { field = value; requestLayout() } }
    var maxHeightPx = Int.MAX_VALUE

    private val radius = 12 * resources.displayMetrics.density

    init {
        scaleType = ScaleType.FIT_XY
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        foreground = GradientDrawable().apply {
            cornerRadius = radius
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), context.getColorFromAttr(R.attr.primaryColorTrans50))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (aspect <= 0f) return setMeasuredDimension(0, 0)
        var w = MeasureSpec.getSize(widthMeasureSpec)
        var h = (w * aspect).toInt()
        if (h > maxHeightPx) {
            h = maxHeightPx
            w = (h / aspect).toInt()
        }
        setMeasuredDimension(w, h)
    }
}

/**
 * Holds the page pager and makes it as tall as the tallest page (pages can't wrap their own height
 * in a ViewPager2), so swiping never resizes the dialog. [tallest] measures the pages at a width.
 */
class TallestPageFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var tallest: ((width: Int) -> Int)? = null
    private var cachedWidth = -1
    private var cachedHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val measure = tallest ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (width != cachedWidth) {
            cachedWidth = width
            cachedHeight = measure(width - paddingLeft - paddingRight) + paddingTop + paddingBottom
        }
        val exactW = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(cachedHeight, MeasureSpec.EXACTLY)
        super.onMeasure(exactW, exactH)
    }
}
