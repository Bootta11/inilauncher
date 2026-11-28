package app.olauncher.extras.config

import android.view.Gravity
import app.olauncher.extras.ExtrasKeys
import org.json.JSONArray
import org.json.JSONObject

/** The parts of a configuration file; each can be left out, and each has its own tick box. */
enum class Part(val key: String) { LOOK("look"), SETTINGS("settings"), LAYOUT("layout") }

/** What turning a setting on needs on this phone; without it the setting is skipped, never forced. */
enum class Requirement { NONE, CONTACTS, CALENDAR, NOTIFICATION_ACCESS, USAGE_ACCESS }

/** Read access to one preferences file (SharedPreferences in the app, a map in tests). */
interface KeyValues {
    fun bool(key: String, default: Boolean): Boolean
    fun int(key: String, default: Int): Int
    fun float(key: String, default: Float): Float
    fun string(key: String): String?
}

/** Writes to one preferences file, committed together after everything was checked. */
interface KeyEdits {
    fun putBool(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putFloat(key: String, value: Float)
    fun putString(key: String, value: String?)
}

/** Upstream's preferences ("app.olauncher") and Inilauncher's ([app.olauncher.extras.ExtrasPrefs]). */
class Store(val upstream: KeyValues, val extras: KeyValues)
class Edits(val upstream: KeyEdits, val extras: KeyEdits)

/**
 * One exportable setting: a stable public [key], its [part], its [type] (the limits), where it lives
 * ([read]/[write]), and what turning it on needs. [online]: it sends something off the phone, so the
 * import preview points it out.
 */
class Setting<T : Any>(
    val key: String,
    val part: Part,
    val type: Type<T>,
    val internalKeys: List<String>,
    val requires: Requirement = Requirement.NONE,
    val online: Boolean = false,
    val read: (Store) -> T,
    val write: (Edits, T) -> Unit,
) {
    /** True when [value] switches something on that needs [requires]. */
    fun turnsOn(value: Any) = requires != Requirement.NONE && value == true

    @Suppress("UNCHECKED_CAST")
    fun writeAny(edits: Edits, value: Any) = write(edits, value as T)
}

/**
 * The declared v1 schema: every setting a file may contain. Anything not listed here is never read
 * from a file and never written. See [NEVER_EXPORTED] for what's left out on purpose.
 */
object ConfigSchema {

    const val FORMAT = "inilauncher-config"
    const val VERSION = 1

    // Upstream preference keys (data/Prefs.kt keeps them private)
    private const val APP_THEME = "APP_THEME"
    private const val TEXT_SIZE_SCALE = "TEXT_SIZE_SCALE"
    private const val BOLD_FONT = "BOLD_FONT"
    private const val HOME_ALIGNMENT = "HOME_ALIGNMENT"
    private const val APP_LABEL_ALIGNMENT = "APP_LABEL_ALIGNMENT"
    private const val HOME_BOTTOM_ALIGNMENT = "HOME_BOTTOM_ALIGNMENT"
    private const val DATE_TIME_VISIBILITY = "DATE_TIME_VISIBILITY"
    private const val STATUS_BAR = "STATUS_BAR"
    private const val AUTO_SHOW_KEYBOARD = "AUTO_SHOW_KEYBOARD"
    private const val SWIPE_LEFT_ENABLED = "SWIPE_LEFT_ENABLED"
    private const val SWIPE_RIGHT_ENABLED = "SWIPE_RIGHT_ENABLED"

    // AppCompatDelegate.MODE_NIGHT_NO / _YES and upstream's Constants.DateTime
    private const val NIGHT_NO = 1
    private const val NIGHT_YES = 2

    val FONTS = listOf("default", "sans", "condensed", "serif", "mono", "casual")
    val ICON_STYLES = mapOf(
        "off" to "", "system" to "system", "mono" to "mono", "gray" to "gray",
        "letter" to "letter", "letter_color" to "lettercolor", "letter_outline" to "letteroutline",
    )
    /** An icon pack is written as "pack:" + its package. */
    const val ICON_PACK_PREFIX = "pack:"
    private val ICONS_PATTERN = "^(${ICON_STYLES.keys.joinToString("|")}|pack:" + S.PACKAGE_PATTERN.removePrefix("^").removeSuffix("$") + ")$"

    private fun upBool(key: String, part: Part, internal: String, default: Boolean) =
        Setting(key, part, S.bool(), listOf(internal), read = { it.upstream.bool(internal, default) }, write = { e, v -> e.upstream.putBool(internal, v) })

    private fun exBool(key: String, part: Part, internal: String, requires: Requirement = Requirement.NONE, inverted: Boolean = false, online: Boolean = false) =
        Setting(key, part, S.bool(), listOf(internal), requires, online,
            read = { it.extras.bool(internal, false) != inverted }, write = { e, v -> e.extras.putBool(internal, v != inverted) })

    /** A choice stored as an extras string; [values] maps public value → stored value ("" = unset). */
    private fun exChoice(key: String, part: Part, internal: String, values: Map<String, String>) =
        Setting(key, part, S.choice(*values.keys.toTypedArray()), listOf(internal),
            read = { s -> val v = s.extras.string(internal).orEmpty(); values.entries.firstOrNull { it.value == v }?.key ?: values.keys.first() },
            write = { e, v -> e.extras.putString(internal, values.getValue(v).ifEmpty { null }) })

    val settings: List<Setting<*>> = listOf(
        // Look
        Setting("theme", Part.LOOK, S.choice("dark", "light"), listOf(APP_THEME),
            read = { if (it.upstream.int(APP_THEME, NIGHT_YES) == NIGHT_NO) "light" else "dark" },
            write = { e, v -> e.upstream.putInt(APP_THEME, if (v == "light") NIGHT_NO else NIGHT_YES) }),
        Setting("textSize", Part.LOOK, S.decimal(0.5, 2.0), listOf(TEXT_SIZE_SCALE),
            read = { Math.round(it.upstream.float(TEXT_SIZE_SCALE, 1f) * 10) / 10.0 },
            write = { e, v -> e.upstream.putFloat(TEXT_SIZE_SCALE, Math.round(v * 10) / 10f) }),
        upBool("boldFont", Part.LOOK, BOLD_FONT, false),
        Setting("font", Part.LOOK, S.choice(*FONTS.toTypedArray()), listOf(ExtrasKeys.FONT),
            read = { s -> s.extras.string(ExtrasKeys.FONT)?.lowercase()?.takeIf { it in FONTS } ?: "default" },
            write = { e, v -> e.extras.putString(ExtrasKeys.FONT, v.uppercase()) }),
        Setting("alignment", Part.LOOK, S.choice("left", "center", "right"), listOf(HOME_ALIGNMENT, APP_LABEL_ALIGNMENT),
            read = { when (it.upstream.int(HOME_ALIGNMENT, Gravity.START)) { Gravity.CENTER -> "center"; Gravity.END -> "right"; else -> "left" } },
            write = { e, v ->
                val g = when (v) { "center" -> Gravity.CENTER; "right" -> Gravity.END; else -> Gravity.START }
                e.upstream.putInt(HOME_ALIGNMENT, g)
                e.upstream.putInt(APP_LABEL_ALIGNMENT, g)
            }),
        upBool("bottomAlignment", Part.LOOK, HOME_BOTTOM_ALIGNMENT, false),
        Setting("dateTime", Part.LOOK, S.choice("on", "off", "date"), listOf(DATE_TIME_VISIBILITY),
            read = { when (it.upstream.int(DATE_TIME_VISIBILITY, 1)) { 0 -> "off"; 2 -> "date"; else -> "on" } },
            write = { e, v -> e.upstream.putInt(DATE_TIME_VISIBILITY, when (v) { "off" -> 0; "date" -> 2; else -> 1 }) }),
        upBool("statusBar", Part.LOOK, STATUS_BAR, false),
        Setting("appIcons", Part.LOOK, S.text(270, ICONS_PATTERN), listOf(ExtrasKeys.ICONS),
            read = { s ->
                val v = s.extras.string(ExtrasKeys.ICONS).orEmpty()
                ICON_STYLES.entries.firstOrNull { it.value == v }?.key ?: if ('.' in v) ICON_PACK_PREFIX + v else "off"
            },
            write = { e, v -> e.extras.putString(ExtrasKeys.ICONS, if (v.startsWith(ICON_PACK_PREFIX)) v.removePrefix(ICON_PACK_PREFIX) else ICON_STYLES.getValue(v).ifEmpty { null }) }),
        exBool("blur", Part.LOOK, ExtrasKeys.BLUR),
        exBool("eink", Part.LOOK, ExtrasKeys.FORCE_EINK),
        exBool("gestureMotion", Part.LOOK, ExtrasKeys.GESTURE_MOTION),
        exChoice("sublist.button", Part.LOOK, ExtrasKeys.SUBLIST_BUTTON, linkedMapOf("dots" to "", "grid" to "grid", "count" to "count", "peek" to "peek")),
        exChoice("sublist.style", Part.LOOK, ExtrasKeys.SUBLIST_STYLE, linkedMapOf("icons" to "", "icons_names" to "icons_names", "names" to "names")),
        exChoice("notifications.dotStyle", Part.LOOK, ExtrasKeys.NOTIFICATION_DOT_STYLE, linkedMapOf("dot" to "", "count" to "count")),

        // Settings
        upBool("keyboard.autoShow", Part.SETTINGS, AUTO_SHOW_KEYBOARD, true),
        upBool("gestures.swipeLeft", Part.SETTINGS, SWIPE_LEFT_ENABLED, true),
        upBool("gestures.swipeRight", Part.SETTINGS, SWIPE_RIGHT_ENABLED, true),
        exBool("note.enabled", Part.SETTINGS, ExtrasKeys.NOTE_ENABLED),
        exBool("weather.fahrenheit", Part.SETTINGS, ExtrasKeys.WEATHER_FAHRENHEIT),
        exBool("events.enabled", Part.SETTINGS, ExtrasKeys.EVENTS_ENABLED, Requirement.CALENDAR),
        exBool("widgets.enabled", Part.SETTINGS, ExtrasKeys.WIDGETS_ENABLED),
        exBool("notifications.enabled", Part.SETTINGS, ExtrasKeys.NOTIFICATIONS_ENABLED, Requirement.NOTIFICATION_ACCESS),
        exBool("notifications.line", Part.SETTINGS, ExtrasKeys.NOTIFICATIONS_NO_LINE, inverted = true),
        exBool("notifications.dots", Part.SETTINGS, ExtrasKeys.NOTIFICATIONS_NO_DOTS, inverted = true),
        exBool("notifications.folders", Part.SETTINGS, ExtrasKeys.NOTIFICATION_DOTS_FOLDERS),
        exBool("media.enabled", Part.SETTINGS, ExtrasKeys.MEDIA_ENABLED, Requirement.NOTIFICATION_ACCESS),
        exBool("sublist.enabled", Part.SETTINGS, ExtrasKeys.SUBLIST_ENABLED),
        exBool("sublist.free", Part.SETTINGS, ExtrasKeys.SUBLIST_FREE),
        exBool("sublist.auto", Part.SETTINGS, ExtrasKeys.SUBLIST_AUTO, Requirement.USAGE_ACCESS),
        exBool("search.contacts", Part.SETTINGS, ExtrasKeys.SEARCH_CONTACTS, Requirement.CONTACTS),
        exBool("search.calculator", Part.SETTINGS, ExtrasKeys.SEARCH_CALCULATOR),
        exBool("search.web", Part.SETTINGS, ExtrasKeys.SEARCH_WEB, online = true),
        exBool("search.shortcuts", Part.SETTINGS, ExtrasKeys.SEARCH_SHORTCUTS),
        exBool("search.holdToggle", Part.SETTINGS, ExtrasKeys.SEARCH_HOLD_TOGGLE),
        exBool("search.holdActions", Part.SETTINGS, ExtrasKeys.SEARCH_HOLD_ACTIONS),
        exBool("az.enabled", Part.SETTINGS, ExtrasKeys.AZ_SCROLLER),
        exChoice("az.side", Part.SETTINGS, ExtrasKeys.AZ_SIDE, linkedMapOf("auto" to "", "left" to "left", "right" to "right")),
        exChoice("az.height", Part.SETTINGS, ExtrasKeys.AZ_HEIGHT, linkedMapOf("full" to "", "thumb" to "thumb")),
        exBool("suggested.enabled", Part.SETTINGS, ExtrasKeys.SUGGESTED_APPS, Requirement.USAGE_ACCESS),
        exBool("sortByUsage", Part.SETTINGS, ExtrasKeys.SORT_BY_USAGE, Requirement.USAGE_ACCESS),
    )

    val byKey: Map<String, Setting<*>> = settings.associateBy { it.key }

    /** Extras keys the layout part reads and writes (through [ConfigLayout]). */
    val LAYOUT_KEYS = setOf(ExtrasKeys.SUBLIST_APPS, ExtrasKeys.SUBLIST_CELLS, ExtrasKeys.FOLDERS, ExtrasKeys.SLOT_SWIPES)

    /**
     * Extras keys a file can never contain, with the reason: private, device-specific, security,
     * bookkeeping, or a feature whose setup can't be shared (location, folders on this phone).
     */
    val NEVER_EXPORTED: Map<String, String> = mapOf(
        ExtrasKeys.LOCK_HIDDEN_APPS to "security",
        ExtrasKeys.NOTE_TEXT to "private",
        ExtrasKeys.NOTIFICATIONS_HIDDEN to "private",
        ExtrasKeys.WEATHER_ENABLED to "needs a place on this phone",
        ExtrasKeys.WEATHER_PLACE_NAME to "private", ExtrasKeys.WEATHER_PLACE_LAT to "private", ExtrasKeys.WEATHER_PLACE_LON to "private",
        ExtrasKeys.WEATHER_CACHE_TEMP to "cache", ExtrasKeys.WEATHER_CACHE_CODE to "cache", ExtrasKeys.WEATHER_CACHE_TIME to "cache",
        ExtrasKeys.WEATHER_APP to "app on this phone",
        ExtrasKeys.DAILY_WALLPAPER_SOURCE to "folders on this phone",
        ExtrasKeys.DAILY_WALLPAPER_LIGHT_DIR to "folders on this phone", ExtrasKeys.DAILY_WALLPAPER_DARK_DIR to "folders on this phone",
        ExtrasKeys.DAILY_WALLPAPER_LIST_FETCHED to "cache",
        ExtrasKeys.DAYNIGHT_ENABLED to "needs a location", ExtrasKeys.DAYNIGHT_LAST_PHASE to "state",
        ExtrasKeys.DAYNIGHT_LAT to "private", ExtrasKeys.DAYNIGHT_LON to "private", ExtrasKeys.DAYNIGHT_FIXED to "needs a location",
        ExtrasKeys.DAYNIGHT_DAY_START to "needs a location", ExtrasKeys.DAYNIGHT_NIGHT_START to "needs a location",
        ExtrasKeys.DAYNIGHT_DAILY_DAY_URL to "state", ExtrasKeys.DAYNIGHT_DAILY_NIGHT_URL to "state",
        ExtrasKeys.WIDGET_IDS to "device-specific", ExtrasKeys.WIDGET_RECENT_APPS to "usage history",
        ExtrasKeys.SUBLIST_NO_SUGGEST to "usage history", ExtrasKeys.SUGGESTED_NO_SUGGEST to "usage history",
        ExtrasKeys.SUGGESTED_PINNED to "usage history",
        ExtrasKeys.ASKED_POST_NOTIFICATIONS to "state", ExtrasKeys.UPDATE_DISMISSED to "state", ExtrasKeys.WHATS_NEW_SEEN to "state",
        ExtrasKeys.SHOWCASE to "screenshot setup on this phone",
    )

    /** The whole file as JSON Schema 2020-12 (docs/config.schema.json), generated from the types above. */
    fun jsonSchema(): JSONObject {
        fun partSchema(part: Part) = JSONObject().put("type", "object").put("additionalProperties", false).put("properties",
            JSONObject().apply { settings.filter { it.part == part }.forEach { put(it.key, it.type.jsonSchema()) } })
        return JSONObject()
            .put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("title", "Inilauncher configuration")
            .put("type", "object")
            .put("required", JSONArray(listOf("format", "version")))
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("format", JSONObject().put("const", FORMAT))
                .put("version", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", VERSION))
                .put("app", S.text(40).jsonSchema())
                .put("meta", JSONObject().put("type", "object").put("additionalProperties", false).put("properties",
                    JSONObject().put("name", ConfigMeta.NAME.jsonSchema()).put("description", ConfigMeta.DESCRIPTION.jsonSchema())))
                .put(Part.LOOK.key, partSchema(Part.LOOK))
                .put(Part.SETTINGS.key, partSchema(Part.SETTINGS))
                .put(Part.LAYOUT.key, ConfigLayout.jsonSchema()))
    }
}

/** A file's name and description: plain text, shown as text only. */
data class ConfigMeta(val name: String = "", val description: String = "") {
    companion object {
        val NAME = S.text(40)
        val DESCRIPTION = S.text(200)
    }
}
