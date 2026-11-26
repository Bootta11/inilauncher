package app.olauncher.extras

/** Keys in [ExtrasPrefs]. Every boolean defaults to false (feature off). */
object ExtrasKeys {
    // Look
    const val FONT = "font"
    const val BLUR = "blur"
    const val FORCE_EINK = "force_eink"
    const val GESTURE_MOTION = "gesture_motion"

    // Look: where upstream's daily wallpaper comes from (extras/wallpaper)
    const val DAILY_WALLPAPER_SOURCE = "daily_wallpaper_source"
    const val DAILY_WALLPAPER_LIGHT_DIR = "daily_wallpaper_light_dir"
    const val DAILY_WALLPAPER_DARK_DIR = "daily_wallpaper_dark_dir"
    const val DAILY_WALLPAPER_LIST_FETCHED = "daily_wallpaper_list_fetched"

    // Look: day & night by the sun
    const val DAYNIGHT_ENABLED = "daynight_enabled"
    const val DAYNIGHT_LAST_PHASE = "daynight_last_phase"
    const val DAYNIGHT_LAT = "daynight_lat"
    const val DAYNIGHT_LON = "daynight_lon"
    const val DAYNIGHT_FIXED = "daynight_fixed"
    const val DAYNIGHT_DAY_START = "daynight_day_start"
    const val DAYNIGHT_NIGHT_START = "daynight_night_start"
    const val DAYNIGHT_DAILY_DAY_URL = "daynight_daily_day_url"
    const val DAYNIGHT_DAILY_NIGHT_URL = "daynight_daily_night_url"

    // Home screen
    const val NOTE_ENABLED = "note_enabled"
    const val NOTE_TEXT = "note_text"
    /** Keys of persistent notifications hidden in Inilauncher (newline-separated); see InilauncherNotificationListener. */
    const val NOTIFICATIONS_HIDDEN = "notifications_hidden"

    // Home: weather
    const val WEATHER_ENABLED = "weather_enabled"
    const val WEATHER_FAHRENHEIT = "weather_fahrenheit"
    const val WEATHER_PLACE_NAME = "weather_place_name"
    const val WEATHER_PLACE_LAT = "weather_place_lat"
    const val WEATHER_PLACE_LON = "weather_place_lon"
    const val WEATHER_CACHE_TEMP = "weather_cache_temp"
    const val WEATHER_CACHE_CODE = "weather_cache_code"
    const val WEATHER_CACHE_TIME = "weather_cache_time"
    const val WEATHER_APP = "weather_app"

    // Home: events, notifications, media, widgets
    const val EVENTS_ENABLED = "events_enabled"
    const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val MEDIA_ENABLED = "media_enabled"
    // Notification dot options ("NO_" keys switch a default-on part off, so false keeps today's behaviour)
    const val NOTIFICATIONS_NO_LINE = "notifications_no_line"
    const val NOTIFICATIONS_NO_DOTS = "notifications_no_dots"
    const val NOTIFICATION_DOT_STYLE = "notification_dot_style"
    const val NOTIFICATION_DOTS_FOLDERS = "notification_dots_folders"
    const val WIDGETS_ENABLED = "widgets_enabled"
    const val WIDGET_IDS = "widget_ids"
    const val WIDGET_RECENT_APPS = "widget_recent_apps"
    const val ASKED_POST_NOTIFICATIONS = "asked_post_notifications"
    const val UPDATE_DISMISSED = "update_dismissed_version_code"

    // Home: sub list under the home apps
    const val SUBLIST_ENABLED = "sublist_enabled"
    const val SUBLIST_APPS = "sublist_apps"
    const val SUBLIST_BUTTON = "sublist_button"
    const val SUBLIST_STYLE = "sublist_style"
    const val SUBLIST_AUTO = "sublist_auto"
    const val SUBLIST_NO_SUGGEST = "sublist_no_suggest"

    // Search & app list
    const val SEARCH_CONTACTS = "search_contacts"
    const val SEARCH_CALCULATOR = "search_calculator"
    const val SEARCH_WEB = "search_web"
    const val SEARCH_SHORTCUTS = "search_shortcuts"
    const val SEARCH_HOLD_TOGGLE = "search_hold_toggle"
    const val SEARCH_HOLD_ACTIONS = "search_hold_actions"
    const val AZ_SCROLLER = "az_scroller"
    const val AZ_SIDE = "az_side"
    const val AZ_HEIGHT = "az_height"
    const val SUGGESTED_APPS = "suggested_apps"
    const val SUGGESTED_NO_SUGGEST = "suggested_no_suggest"
    const val SUGGESTED_PINNED = "suggested_pinned"
    const val SORT_BY_USAGE = "sort_by_usage"
    const val ICONS = "icons"
    const val SLOT_SWIPES = "slot_swipes"

    // Privacy & security
    const val LOCK_HIDDEN_APPS = "lock_hidden_apps"

    // Folders (JSON)
    const val FOLDERS = "folders"
}
