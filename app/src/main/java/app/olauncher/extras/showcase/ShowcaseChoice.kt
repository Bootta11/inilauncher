package app.olauncher.extras.showcase

import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.FolderJson
import org.json.JSONArray
import org.json.JSONObject

/** What's behind the showcase screens. Never the system wallpaper, which apps can't read anyway. */
sealed interface ShowcaseWallpaper {
    /** Plain black or white, from the theme. */
    data object Theme : ShowcaseWallpaper
    /** Today's photo pair from Inilauncher's list (never the user's own folders). */
    data object Daily : ShowcaseWallpaper
    /** One pair from Inilauncher's list: the light photo for the light theme, the dark one for dark. */
    data class ListPair(val lightUrl: String, val darkUrl: String) : ShowcaseWallpaper
    /** A picture the user picked, copied into the showcase's folder. */
    data object Image : ShowcaseWallpaper
}

/**
 * The stand-in apps and background of the showcase screens, saved in the real extras preferences
 * ([app.olauncher.extras.ExtrasKeys.SHOWCASE]). Never exported. Pure, so it's unit tested.
 */
data class ShowcaseChoice(
    val home: List<FolderApp>,
    val sublist: List<FolderApp>,
    val folderName: String,
    val folder: List<FolderApp>,
    val drawer: List<FolderApp>,
    val wallpaper: ShowcaseWallpaper,
) {
    fun encode(): String = JSONObject()
        .put("home", JSONArray(FolderJson.encodeApps(home)))
        .put("sublist", JSONArray(FolderJson.encodeApps(sublist)))
        .put("folderName", folderName)
        .put("folder", JSONArray(FolderJson.encodeApps(folder)))
        .put("drawer", JSONArray(FolderJson.encodeApps(drawer)))
        .put("wallpaper", when (wallpaper) {
            ShowcaseWallpaper.Theme -> JSONObject().put("kind", "theme")
            ShowcaseWallpaper.Daily -> JSONObject().put("kind", "daily")
            ShowcaseWallpaper.Image -> JSONObject().put("kind", "image")
            is ShowcaseWallpaper.ListPair -> JSONObject().put("kind", "list").put("light", wallpaper.lightUrl).put("dark", wallpaper.darkUrl)
        })
        .toString()

    companion object {
        const val MAX_HOME = 7 // the eighth slot holds the folder
        const val DEFAULT_FOLDER = "Tools"

        /** Null when nothing (or nothing readable) is saved: the caller uses [defaults] then. */
        fun decode(json: String?): ShowcaseChoice? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                fun apps(key: String) = FolderJson.decodeApps(o.optJSONArray(key)?.toString())
                val w = o.optJSONObject("wallpaper")
                val wallpaper = when (w?.optString("kind")) {
                    "theme" -> ShowcaseWallpaper.Theme
                    "image" -> ShowcaseWallpaper.Image
                    "list" -> {
                        val light = w.optString("light")
                        val dark = w.optString("dark")
                        if (light.startsWith("https://") && dark.startsWith("https://")) ShowcaseWallpaper.ListPair(light, dark) else ShowcaseWallpaper.Daily
                    }
                    else -> ShowcaseWallpaper.Daily
                }
                ShowcaseChoice(
                    home = apps("home").take(MAX_HOME),
                    sublist = apps("sublist"),
                    folderName = o.optString("folderName").trim().take(40).ifBlank { DEFAULT_FOLDER },
                    folder = apps("folder"),
                    drawer = apps("drawer"),
                    wallpaper = wallpaper,
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * The starting set from ordinary apps found by role ([found], in [Role] order, one app per role):
         * a few on home, a few in the sub list, a few in the folder, all of them in the app list.
         */
        fun defaults(found: Map<Role, FolderApp>, wallpaper: ShowcaseWallpaper = ShowcaseWallpaper.Daily): ShowcaseChoice {
            fun pick(roles: List<Role>) = roles.mapNotNull { found[it] }
            val home = pick(listOf(Role.PHONE, Role.MESSAGES, Role.CAMERA, Role.BROWSER))
            val sublist = pick(listOf(Role.MAPS, Role.PHOTOS, Role.CALENDAR, Role.CLOCK, Role.MUSIC))
            val folder = pick(listOf(Role.CALCULATOR, Role.FILES, Role.CONTACTS, Role.SETTINGS, Role.EMAIL))
            val drawer = Role.entries.mapNotNull { found[it] }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            return ShowcaseChoice(home.distinctBy { it.packageName }, sublist.distinctBy { it.packageName }, DEFAULT_FOLDER,
                folder.distinctBy { it.packageName }, drawer, wallpaper)
        }
    }

    /** Ordinary apps every phone has, found through standard intents rather than by name. */
    enum class Role { PHONE, MESSAGES, CAMERA, BROWSER, MAPS, PHOTOS, CALENDAR, CLOCK, MUSIC, CALCULATOR, FILES, CONTACTS, SETTINGS, EMAIL }
}
