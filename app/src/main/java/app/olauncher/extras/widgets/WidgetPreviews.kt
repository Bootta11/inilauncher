package app.olauncher.extras.widgets

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

/**
 * Previews for the widget picker. Android 12+ widgets can ship a live preview layout: it is
 * inflated as is and scaled to fit the card (like Launcher3). Others ship a preview picture:
 * it is loaded off the main thread, shrunk to card size and cached. Without either, the app icon.
 */
object WidgetPreviews {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<ComponentName, Bitmap>(8 * 1024) {
        override fun sizeOf(key: ComponentName, value: Bitmap) = value.byteCount / 1024
    }

    /** Width × height in panel cells (columns of the 4-column grid × ~74dp rows), e.g. "4×2". */
    fun sizeLabel(context: Context, info: AppWidgetProviderInfo, cellWidthDp: Float): String {
        val (w, h) = cells(context, info, cellWidthDp)
        return "$w×$h"
    }

    fun cells(context: Context, info: AppWidgetProviderInfo, cellWidthDp: Float): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0)
            return info.targetCellWidth.coerceIn(1, WidgetStore.COLUMNS) to info.targetCellHeight.coerceAtLeast(1)
        val density = context.resources.displayMetrics.density
        val w = WidgetStore.autoSpan(info.minWidth / density, cellWidthDp)
        val h = ceil(info.minHeight / density / ROW_DP).toInt().coerceAtLeast(1)
        return w to h
    }

    /** Short description (Android 12+), or null. */
    fun description(context: Context, info: AppWidgetProviderInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            try { info.loadDescription(context)?.toString()?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
        else null

    /** Fills a card's preview area ([frame] for live previews, [image] for pictures; at most [maxW] × [maxH] px). */
    fun bind(frame: ScaledFrame, image: ImageView, info: AppWidgetProviderInfo, cellWidthDp: Float, maxW: Int, maxH: Int) {
        val context = frame.context
        frame.removeAllViews()
        frame.naturalWidth = 0
        frame.naturalHeight = 0
        frame.tag = info.provider
        image.setImageDrawable(null)
        image.visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0) {
            val live = try {
                RemoteViews(info.provider.packageName, info.previewLayout).apply(context.applicationContext, frame)
            } catch (e: Exception) { null }
            if (live != null) {
                val density = context.resources.displayMetrics.density
                val (w, h) = cells(context, info, cellWidthDp)
                frame.naturalWidth = (w * cellWidthDp * density).toInt()
                frame.naturalHeight = (h * ROW_DP * density).toInt()
                frame.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                image.visibility = View.GONE
                return
            }
        }

        cache.get(info.provider)?.let { return image.setImageBitmap(it) }
        val app = context.applicationContext
        val dpi = context.resources.displayMetrics.densityDpi
        executor.execute {
            val bitmap = try {
                val drawable = info.loadPreviewImage(app, dpi) ?: info.loadIcon(app, dpi)
                drawable?.let { d ->
                    val iw = d.intrinsicWidth.takeIf { it > 0 } ?: maxW
                    val ih = d.intrinsicHeight.takeIf { it > 0 } ?: maxH
                    val scale = min(1f, min(maxW.toFloat() / iw, maxH.toFloat() / ih))
                    val bw = (iw * scale).toInt().coerceAtLeast(1)
                    val bh = (ih * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { b ->
                        d.setBounds(0, 0, bw, bh)
                        d.draw(Canvas(b))
                    }
                }
            } catch (e: Throwable) { null }
            main.post {
                if (bitmap != null) cache.put(info.provider, bitmap)
                // The card may have been reused for another widget meanwhile
                if (frame.tag == info.provider) image.setImageBitmap(bitmap)
            }
        }
    }

    /** Height of one panel row in dp, for the size label of widgets that don't state it. */
    private const val ROW_DP = 74f

    /**
     * Never takes touches, so tapping it taps the card. Lays its child out at the widget's natural size ([naturalWidth] × [naturalHeight]) and
     * scales it down to fit, centred, so a live preview looks like the real widget, only smaller.
     */
    class ScaledFrame(context: Context) : FrameLayout(context) {
        var naturalWidth = 0
        var naturalHeight = 0

        init {
            // A preview is a picture: its buttons and lists must not take the card's tap or swipe
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isFocusable = false
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?) = true

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: android.view.MotionEvent?) = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val nw = if (naturalWidth > 0) naturalWidth else w
            val nh = if (naturalHeight > 0) naturalHeight else h
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(nw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(nh, MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = right - left
            val h = bottom - top
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                child.layout(0, 0, cw, ch)
                val scale = if (cw > 0 && ch > 0) min(1f, min(w.toFloat() / cw, h.toFloat() / ch)) else 1f
                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = scale
                child.scaleY = scale
                child.translationX = (w - cw * scale) / 2
                child.translationY = (h - ch * scale) / 2
            }
        }
    }
}
