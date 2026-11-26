package app.olauncher.extras.widgets

import android.content.Context
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs

/**
 * A placed widget: its id, height in dp (0 = the widget's own minimum height) and width in grid
 * columns (0 = worked out from the widget's own size).
 */
data class PlacedWidget(val id: Int, val heightDp: Int, val span: Int = 0)

/** Widgets on the panel, in order, saved as "id:height:span,…" (older "id:height" still reads). */
class WidgetStore(context: Context) {

    private val prefs = ExtrasPrefs(context)

    fun all(): List<PlacedWidget> = decode(prefs.string(ExtrasKeys.WIDGET_IDS))

    fun save(widgets: List<PlacedWidget>) = prefs.setString(ExtrasKeys.WIDGET_IDS, encode(widgets))

    fun add(id: Int) = save(all() + PlacedWidget(id, 0))
    fun remove(id: Int) = save(all().filterNot { it.id == id })
    fun setHeight(id: Int, heightDp: Int) = save(all().map { if (it.id == id) it.copy(heightDp = heightDp) else it })
    fun setSpan(id: Int, span: Int) = save(all().map { if (it.id == id) it.copy(span = span.coerceIn(1, COLUMNS)) else it })
    fun resize(id: Int, span: Int, heightDp: Int) =
        save(all().map { if (it.id == id) it.copy(span = span.coerceIn(1, COLUMNS), heightDp = heightDp) else it })
    fun move(id: Int, by: Int) = moveTo(id, all().indexOfFirst { it.id == id } + by)
    fun moveTo(id: Int, index: Int) = save(moved(all(), id, index))

    /** Apps whose widgets were added lately, most recent first (the picker's "Recent"). */
    fun recentApps(): List<String> = prefs.string(ExtrasKeys.WIDGET_RECENT_APPS).orEmpty().split(',').filter { it.isNotBlank() }

    fun addRecentApp(packageName: String) =
        prefs.setString(ExtrasKeys.WIDGET_RECENT_APPS, pushRecent(recentApps(), packageName).joinToString(","))

    companion object {
        /** How many apps the picker's "Recent" keeps. */
        const val RECENT_APPS = 5

        /** [packageName] moved to the front of [recent], at most [RECENT_APPS] kept. */
        fun pushRecent(recent: List<String>, packageName: String): List<String> =
            (listOf(packageName) + recent.filter { it != packageName }).take(RECENT_APPS)

        /** [widgets] with [id] at place [to]; unchanged when there's no such widget or place. */
        fun moved(widgets: List<PlacedWidget>, id: Int, to: Int): List<PlacedWidget> {
            val from = widgets.indexOfFirst { it.id == id }
            if (from < 0 || to !in widgets.indices) return widgets
            return widgets.toMutableList().apply { add(to, removeAt(from)) }
        }

        /** Columns in the panel's grid. */
        const val COLUMNS = 4

        fun encode(widgets: List<PlacedWidget>) = widgets.joinToString(",") { "${it.id}:${it.heightDp}:${it.span}" }

        fun decode(value: String?): List<PlacedWidget> =
            value.orEmpty().split(',').mapNotNull { part ->
                val bits = part.split(':')
                val id = bits.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                PlacedWidget(
                    id,
                    bits.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    bits.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(0, COLUMNS) ?: 0,
                )
            }.distinctBy { it.id }

        /** Columns a widget needs for [minWidthDp] when each column is [cellWidthDp] wide. */
        fun autoSpan(minWidthDp: Float, cellWidthDp: Float): Int =
            if (cellWidthDp <= 0f) COLUMNS else kotlin.math.ceil(minWidthDp / cellWidthDp).toInt().coerceIn(1, COLUMNS)

        /** Splits widgets (with their resolved spans) into rows of at most [COLUMNS] columns, in order; [startsRow]: always begins a new one. */
        fun <T> rows(items: List<T>, startsRow: (T) -> Boolean = { false }, span: (T) -> Int): List<List<T>> {
            val rows = mutableListOf<MutableList<T>>()
            var used = COLUMNS
            items.forEach { item ->
                val s = span(item).coerceIn(1, COLUMNS)
                if (used + s > COLUMNS || startsRow(item)) { rows += mutableListOf<T>(); used = 0 }
                rows.last() += item
                used += s
            }
            return rows
        }
    }
}
