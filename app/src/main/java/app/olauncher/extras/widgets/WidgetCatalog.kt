package app.olauncher.extras.widgets

/**
 * The widget picker's list, worked out without any Android views so it can be unit tested:
 * widgets grouped by their app, sorted, filtered by a search, and flattened into rows.
 *
 * [T] is the provider (AppWidgetProviderInfo in the app, a plain value in tests).
 */
object WidgetCatalog {

    class Entry<T>(val provider: T, val packageName: String, val appLabel: String, val label: String)

    class AppGroup<T>(val packageName: String, val appLabel: String, val widgets: List<Entry<T>>)

    /** How an app's widgets show: hidden, one sideways-scrolling row, or a grid going down. */
    enum class Mode { CLOSED, ROW, GRID }

    sealed class Row<out T> {
        /** A section title ("Recent"); no letter. */
        data class Section(val title: String) : Row<Nothing>()
        /** An app: tap to step through its [Mode]s. */
        data class Header<T>(val group: AppGroup<T>, val mode: Mode, val recent: Boolean) : Row<T>()
        /** The app's widgets in one sideways row. */
        data class Widgets<T>(val group: AppGroup<T>, val recent: Boolean) : Row<T>()
        /** One line of the app's widget grid: up to `columns` widgets. */
        data class Cards<T>(val group: AppGroup<T>, val widgets: List<Entry<T>>, val recent: Boolean) : Row<T>()
    }

    /** Leading quotes, brackets and spaces, ignored when sorting so `"(big) IQAir"` sorts by "big". */
    private val leadingJunk = Regex("^[\\s\"'“”‘’«»()\\[\\]{}<>]+")

    fun sortKey(label: String): String = label.replace(leadingJunk, "").lowercase()

    /** Groups [entries] by app (apps by name, their widgets by name). */
    fun <T> group(entries: List<Entry<T>>): List<AppGroup<T>> =
        entries.groupBy { it.packageName }
            .map { (pkg, list) -> AppGroup(pkg, list.first().appLabel, list.sortedBy { sortKey(it.label) }) }
            .sortedWith(compareBy({ sortKey(it.appLabel) }, { it.packageName }))

    /**
     * Keeps an app with all its widgets when its name matches [query], otherwise only the widgets
     * whose name matches. [matches] is (label, query) → match, the drawer's search rule in the app.
     */
    fun <T> filter(groups: List<AppGroup<T>>, query: String, matches: (String, String) -> Boolean): List<AppGroup<T>> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            if (matches(group.appLabel, q)) group
            else group.widgets.filter { matches(it.label, q) }
                .takeIf { it.isNotEmpty() }
                ?.let { AppGroup(group.packageName, group.appLabel, it) }
        }
    }

    /**
     * Adapter rows. Each app shows as [modes] says (by [expandKey]); without an entry it's closed,
     * except apps with a single widget and every app while searching, which start as a row.
     * A grid is cut into [Row.Cards] of [columns] widgets. [recentPackages] (most recent first) are
     * repeated in a "Recent" section on top while not searching.
     */
    fun <T> rows(
        groups: List<AppGroup<T>>,
        modes: Map<String, Mode>,
        searching: Boolean,
        recentPackages: List<String> = emptyList(),
        recentTitle: String = "Recent",
        allTitle: String = "",
        columns: Int = 2,
    ): List<Row<T>> {
        val out = mutableListOf<Row<T>>()
        fun add(group: AppGroup<T>, recent: Boolean) {
            val mode = modes[key(group.packageName, recent)] ?: defaultMode(group, searching)
            out += Row.Header(group, mode, recent)
            when (mode) {
                Mode.CLOSED -> {}
                Mode.ROW -> out += Row.Widgets(group, recent)
                Mode.GRID -> group.widgets.chunked(columns.coerceAtLeast(1)).forEach { out += Row.Cards(group, it, recent) }
            }
        }
        if (!searching) {
            val byPackage = groups.associateBy { it.packageName }
            val recent = recentPackages.mapNotNull { byPackage[it] }
            if (recent.isNotEmpty()) {
                out += Row.Section(recentTitle)
                recent.forEach { add(it, true) }
                if (allTitle.isNotEmpty()) out += Row.Section(allTitle)
            }
        }
        groups.forEach { add(it, false) }
        return out
    }

    fun <T> defaultMode(group: AppGroup<T>, searching: Boolean): Mode =
        if (searching || group.widgets.size == 1) Mode.ROW else Mode.CLOSED

    /**
     * What tapping an app's title does. Closed → row → grid → closed; apps that can't close
     * (one widget, or while searching) switch between row and grid.
     */
    fun <T> nextMode(header: Row.Header<T>, searching: Boolean): Mode {
        val canClose = defaultMode(header.group, searching) == Mode.CLOSED
        return when (header.mode) {
            Mode.CLOSED -> Mode.ROW
            Mode.ROW -> Mode.GRID
            Mode.GRID -> if (canClose) Mode.CLOSED else Mode.ROW
        }
    }

    /** Key for [rows]' modes: the "Recent" copy of an app opens on its own. */
    fun expandKey(row: Row.Header<*>): String = key(row.group.packageName, row.recent)

    private fun key(packageName: String, recent: Boolean) = if (recent) RECENT_PREFIX + packageName else packageName

    /** Labels for LetterIndex.build: app headers outside "Recent" by their sort key, the rest null. */
    fun letterLabels(rows: List<Row<*>>): List<String?> = rows.map { row ->
        if (row is Row.Header<*> && !row.recent) sortKey(row.group.appLabel) else null
    }

    private const val RECENT_PREFIX = "recent:"
}
