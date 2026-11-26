package app.olauncher.extras.sublist

import android.content.Context
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.FolderJson
import app.olauncher.extras.sublist.SubListEntries.same

/** The home sub list's apps and settings, saved in [ExtrasPrefs]. */
class SubListStore(context: Context) {

    private val prefs = ExtrasPrefs(context)

    val enabled get() = prefs.bool(ExtrasKeys.SUBLIST_ENABLED)

    fun apps(): List<FolderApp> = FolderJson.decodeApps(prefs.string(ExtrasKeys.SUBLIST_APPS))

    private fun save(apps: List<FolderApp>) = prefs.setString(ExtrasKeys.SUBLIST_APPS, FolderJson.encodeApps(apps))

    fun contains(app: FolderApp) = apps().any { it.same(app) }

    fun add(app: FolderApp) {
        val apps = apps()
        if (apps.none { it.same(app) }) save(apps + app)
    }

    fun remove(app: FolderApp) = save(apps().filterNot { it.same(app) })

    /** Adds [app], or removes it when it's already there. True when it was added. */
    fun toggle(app: FolderApp): Boolean {
        val added = !contains(app)
        if (added) add(app) else remove(app)
        return added
    }

    /** Puts [app] right before [before], or at the end; adds it if it wasn't picked yet. */
    fun place(app: FolderApp, before: FolderApp?) = save(SubListEntries.place(apps(), app, before))

    var button: String
        get() = prefs.string(ExtrasKeys.SUBLIST_BUTTON).orEmpty()
        set(value) = prefs.setString(ExtrasKeys.SUBLIST_BUTTON, value.ifEmpty { null })

    var style: String
        get() = prefs.string(ExtrasKeys.SUBLIST_STYLE).orEmpty()
        set(value) = prefs.setString(ExtrasKeys.SUBLIST_STYLE, value.ifEmpty { null })

    var auto: Boolean
        get() = prefs.bool(ExtrasKeys.SUBLIST_AUTO)
        set(value) = prefs.setBool(ExtrasKeys.SUBLIST_AUTO, value)

    /** Packages that "Add most-used apps" should leave out. */
    fun noSuggest(): Set<String> =
        prefs.string(ExtrasKeys.SUBLIST_NO_SUGGEST).orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun addNoSuggest(packageName: String) =
        prefs.setString(ExtrasKeys.SUBLIST_NO_SUGGEST, (noSuggest() + packageName).joinToString(","))

    companion object {
        const val BUTTON_DOTS = ""
        const val BUTTON_GRID = "grid"
        const val BUTTON_COUNT = "count"
        const val BUTTON_PEEK = "peek"

        const val STYLE_ICONS = ""
        const val STYLE_ICONS_NAMES = "icons_names"
        const val STYLE_NAMES = "names"
    }
}
