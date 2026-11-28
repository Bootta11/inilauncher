package app.olauncher.extras.showcase

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.config.AppRef
import app.olauncher.extras.config.ConfigLayout
import app.olauncher.extras.config.ConfigSchema
import app.olauncher.extras.config.Edits
import app.olauncher.extras.config.FolderSpec
import app.olauncher.extras.config.HomeEntry
import app.olauncher.extras.config.LauncherConfig
import app.olauncher.extras.config.Layout
import app.olauncher.extras.config.Part
import app.olauncher.extras.config.Skip
import app.olauncher.extras.folders.FolderApp
import java.io.File
import java.util.Calendar

/**
 * Writes the showcase's own preference files, in the launcher's process, before the showcase
 * starts: the real Look and Settings, the stand-in apps as the home layout, and everything that
 * would show personal data turned off. The showcase process reads only these files.
 */
object ShowcaseSetup {

    private const val UPSTREAM = "app.olauncher"
    /** In the showcase's extras file: it has been set up at least once (the live showcase keeps its changes). */
    private const val PREPARED = "showcase_prepared"
    /** In the showcase's extras file: the entries its app list shows ("package/activity"), one per line. */
    private const val DRAWER = "showcase_drawer"

    /**
     * The showcase's files, opened from the launcher's process. The showcase process writes them
     * too (settings changed inside it), so they're re-read from disk when they changed; otherwise a
     * cached copy would be written back over those changes.
     */
    @Suppress("DEPRECATION") // still the only way to make SharedPreferences reload a file another process wrote
    private fun showcasePrefs(context: Context, name: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(Showcase.prefsName(name), Context.MODE_MULTI_PROCESS)

    fun isPrepared(context: Context) = showcasePrefs(context, ExtrasPrefs.FILE_NAME).getBoolean(PREPARED, false)

    /** The showcase's own folder in the app's files: downloaded and picked backgrounds. */
    fun dir(context: Context) = File(context.filesDir, "showcase").apply { mkdirs() }

    /**
     * Lays out the stand-in apps in the showcase files and, with [copyLook], first replaces
     * everything with the real look (without it, settings changed inside the showcase stay). Slow
     * (reads the installed apps): call it off the main thread. Only ever writes the `showcase.*` files.
     */
    @SuppressLint("ApplySharedPref") // committed before the showcase process starts and reads them
    fun prepare(context: Context, choice: ShowcaseChoice, copyLook: Boolean = true) {
        val app = context.applicationContext
        val up = showcasePrefs(app, UPSTREAM).edit()
        val ex = showcasePrefs(app, ExtrasPrefs.FILE_NAME).edit()
        val edits = Edits(LauncherConfig.Editor(up), LauncherConfig.Editor(ex))

        if (copyLook) {
            up.clear()
            ex.clear()
            // The look and how it behaves, exactly as on the phone
            val real = LauncherConfig.store(app)
            ConfigSchema.settings.filter { it.part == Part.LOOK || it.part == Part.SETTINGS }
                .forEach { it.writeAny(edits, it.read(real)) }
        }

        // The stand-in apps as the home layout (matched against installed apps like an import)
        val device = LauncherConfig.PhoneDevice(app)
        ConfigLayout.apply(ConfigLayout.resolve(layout(choice), device, mutableListOf<Skip>()), edits, device)

        // Nothing that shows personal data, and none of upstream's first-run messages
        for (key in listOf(
            ExtrasKeys.NOTE_ENABLED, ExtrasKeys.EVENTS_ENABLED, ExtrasKeys.WIDGETS_ENABLED, ExtrasKeys.NOTIFICATIONS_ENABLED,
            ExtrasKeys.MEDIA_ENABLED, ExtrasKeys.SUBLIST_AUTO, ExtrasKeys.SUGGESTED_APPS, ExtrasKeys.SORT_BY_USAGE,
            ExtrasKeys.SEARCH_CONTACTS,
        )) ex.putBoolean(key, false)
        ex.putBoolean(ExtrasKeys.ASKED_POST_NOTIFICATIONS, true)
        ex.putBoolean(PREPARED, true)
        ex.putString(DRAWER, choice.drawer.joinToString("\n") { drawerKey(it.packageName, it.activityClassName) })
        // The status bar would show notification icons in a screenshot; the keyboard would cover the app list
        up.putBoolean("STATUS_BAR", false)
        up.putBoolean("AUTO_SHOW_KEYBOARD", false)
        up.putBoolean("DAILY_WALLPAPER", false)
        up.putBoolean("FIRST_OPEN", false)
        up.putBoolean("FIRST_SETTINGS_OPEN", false)
        up.putBoolean("FIRST_HIDE", false)
        up.putBoolean("KEYBOARD_MESSAGE", true)
        up.putBoolean("WALLPAPER_MSG_SHOWN", true)
        up.putBoolean("ABOUT_CLICKED", true)
        up.putBoolean("HIDE_SET_DEFAULT_LAUNCHER", true)
        up.putString("USER_STATE", "REVIEW")
        val now = System.currentTimeMillis()
        up.putLong("FIRST_OPEN_TIME", now)
        up.putLong("LAUNCHER_RECREATE_TIMESTAMP", now)
        up.putInt("SHOWN_ON_DAY_OF_YEAR", Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
        up.commit()
        ex.commit()
    }

    /** The stand-in apps as a layout: home apps, then the folder; the sub list; one folder. */
    fun layout(choice: ShowcaseChoice): Layout {
        fun ref(app: FolderApp) = AppRef(app.packageName, app.activityClassName)
        val home = choice.home.take(ShowcaseChoice.MAX_HOME).map { HomeEntry.App(ref(it)) } +
            (if (choice.folder.isNotEmpty()) listOf(HomeEntry.Folder(choice.folderName)) else emptyList())
        return Layout(
            homeAppsCount = home.size,
            homeApps = home,
            slotSwipes = emptyList(),
            sublistApps = choice.sublist.map(::ref),
            sublistCells = emptyList(),
            folders = listOf(FolderSpec(choice.folderName, choice.folder.map(::ref))),
        )
    }

    /** The folder's id in the showcase files, for opening it. */
    fun folderId(context: Context): String? =
        app.olauncher.extras.folders.FolderJson.decode(ExtrasPrefs(context).string(ExtrasKeys.FOLDERS)).firstOrNull()?.id

    /** Showcase process: keeps only the stand-in apps in the app list (no shortcuts, no other apps). */
    fun keepDrawerApps(context: Context, list: MutableList<app.olauncher.data.AppModel>) {
        val keep = ExtrasPrefs(context).string(DRAWER).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        val me = android.os.Process.myUserHandle()
        // The exact entry: some apps have several (a phone app that also opens contacts)
        list.retainAll { it is app.olauncher.data.AppModel.App && it.user == me &&
            (drawerKey(it.appPackage, it.activityClassName) in keep || drawerKey(it.appPackage, null) in keep) }
    }

    fun drawerKey(packageName: String, activity: String?) = if (activity.isNullOrBlank()) packageName else "$packageName/$activity"
}
