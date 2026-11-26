package app.olauncher.extras.picker

import app.olauncher.extras.folders.FolderApp

/** The picker's rows: section titles and apps. Pure, unit tested. */
object AppPickerRows {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class App(val app: FolderApp) : Row()
    }

    /**
     * [apps] is A–Z. Without a search: the [pinned] apps (already chosen, in their own order) under
     * [selectedTitle], then [suggested] ones not pinned (e.g. most used) under [suggestedTitle], then
     * every app under [allTitle] (headers only when there's a section above it).
     * With a search: just the matching apps, A–Z.
     */
    fun build(
        apps: List<FolderApp>,
        pinned: List<FolderApp>,
        query: String,
        selectedTitle: String,
        allTitle: String,
        matches: (String, String) -> Boolean,
        suggested: List<FolderApp> = emptyList(),
        suggestedTitle: String = "",
    ): List<Row> {
        if (query.isNotBlank()) return apps.filter { matches(it.label, query) }.map { Row.App(it) }
        val pinnedKeys = pinned.map(InstalledApps::key).toSet()
        val extra = suggested.filter { InstalledApps.key(it) !in pinnedKeys }
        if (pinned.isEmpty() && extra.isEmpty()) return apps.map { Row.App(it) }
        val rows = mutableListOf<Row>()
        if (pinned.isNotEmpty()) rows += listOf(Row.Header(selectedTitle)) + pinned.map { Row.App(it) }
        if (extra.isNotEmpty()) rows += listOf(Row.Header(suggestedTitle)) + extra.map { Row.App(it) }
        return rows + Row.Header(allTitle) + apps.map { Row.App(it) }
    }

    /**
     * Labels for LetterIndex: the A–Z part only, so a letter jumps into "All apps", not into the
     * sections on top (null rows are skipped).
     */
    fun letterLabels(rows: List<Row>): List<String?> {
        val lastHeader = rows.indexOfLast { it is Row.Header }
        return rows.mapIndexed { i, row -> if (row is Row.App && i > lastHeader) row.app.label else null }
    }
}
