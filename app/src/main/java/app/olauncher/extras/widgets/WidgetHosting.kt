package app.olauncher.extras.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.SparseArray
import android.view.ViewGroup

/**
 * One widget host and its widget views for the whole app, so reopening the panel shows the
 * widgets as they were instead of creating them again (which flashes their loading layout).
 */
object WidgetHosting {

    private var host: AppWidgetHost? = null
    private val views = SparseArray<AppWidgetHostView>()
    /** The last size told to each widget, so an unchanged size isn't sent again (widgets redraw on it). */
    private val sizes = SparseArray<Pair<Float, Float>>()

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, WidgetPanel.HOST_ID).also { host = it }

    /** The widget's view, made once and reused; taken off its old panel so it can be added to the new one. */
    fun view(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        val cached = views[id]
        if (cached != null && cached.appWidgetInfo?.provider == info.provider) {
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        sizes.remove(id)
        return host(context).createView(context.applicationContext, id, info).also { views.put(id, it) }
    }

    /** True when [widthDp] × [heightDp] differs from what the widget was last told. */
    fun sizeChanged(id: Int, widthDp: Float, heightDp: Float): Boolean {
        val size = widthDp to heightDp
        if (sizes[id] == size) return false
        sizes.put(id, size)
        return true
    }

    fun forget(id: Int) {
        views[id]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        views.remove(id)
        sizes.remove(id)
    }

    /** Drops views of widgets no longer on the panel. */
    fun keepOnly(ids: Set<Int>) {
        for (i in views.size() - 1 downTo 0) views.keyAt(i).let { if (it !in ids) forget(it) }
    }
}
