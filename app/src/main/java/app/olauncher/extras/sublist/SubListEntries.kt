package app.olauncher.extras.sublist

import app.olauncher.extras.folders.FolderApp

/** Pure rules for "Add most-used apps", unit tested. */
object SubListEntries {

    /** Picked and most-used apps together fill the sub list up to this many. */
    const val FILL_TO = 10

    /**
     * [apps] with [app] right before [before] (at the end when null), added if it wasn't there.
     * Not by index: apps that aren't installed now are kept in [apps] but not shown.
     */
    fun place(apps: List<FolderApp>, app: FolderApp, before: FolderApp?): List<FolderApp> {
        val kept = apps.firstOrNull { it.same(app) } ?: app
        val rest = apps.filterNot { it.same(app) }
        val at = before?.let { b -> rest.indexOfFirst { it.same(b) } } ?: -1
        return if (at < 0) rest + kept else rest.subList(0, at) + kept + rest.subList(at, rest.size)
    }

    fun FolderApp.same(other: FolderApp) = packageName == other.packageName && userSerial == other.userSerial

    /** Packages by launch count, most first (ties A–Z, so the order doesn't jump around). */
    fun rank(counts: Map<String, Int>): List<String> =
        counts.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /**
     * The most-used packages to add after the [picked] ones: in [ranked] order, skipping picked
     * apps and [exclude] (home apps, "Don't suggest", Inilauncher), until there are [fillTo] in all.
     */
    fun autoPackages(picked: List<String>, ranked: List<String>, exclude: Set<String>, fillTo: Int = FILL_TO): List<String> {
        val room = fillTo - picked.size
        if (room <= 0) return emptyList()
        val skip = picked.toSet() + exclude
        return ranked.asSequence().filter { it !in skip }.distinct().take(room).toList()
    }
}
