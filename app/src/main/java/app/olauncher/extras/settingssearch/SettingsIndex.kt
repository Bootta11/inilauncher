package app.olauncher.extras.settingssearch

import android.os.Build
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import app.olauncher.R
import app.olauncher.helper.appLabelMatches
import java.text.Normalizer

enum class SettingsScreen { MAIN, EXTRAS }

/**
 * One searchable setting. [label] is the row's own text (on Extras it also finds the row, see
 * ExtrasSettingsFragment.addRow). [keywords]: the row's hint, so "blur" or "sunrise" find it too.
 * [viewId]: the main screen's value view. [fallback]: the always-shown row to land on when this one
 * only appears while a parent setting is on. [minSdk]: rows the screen leaves out on older Android.
 */
data class SettingEntry(
    @StringRes val label: Int,
    @StringRes val section: Int,
    val screen: SettingsScreen,
    val keywords: List<Int> = emptyList(),
    @IdRes val viewId: Int = 0,
    @StringRes val fallback: Int = 0,
    val minSdk: Int = 0,
)

/** Every setting on upstream's Settings screen and on the Extras screen, for the settings search. */
object SettingsIndex {

    val entries: List<SettingEntry> by lazy { main() + extras() }

    private fun main(): List<SettingEntry> {
        fun m(label: Int, section: Int, viewId: Int) = SettingEntry(label, section, SettingsScreen.MAIN, viewId = viewId)
        val general = R.string.settings_search_general
        return listOf(
            m(R.string.hidden_apps, general, R.id.olauncherHiddenApps),
            m(R.string.set_as_default_launcher, general, R.id.setLauncher),
            m(R.string.about_inilauncher, general, R.id.aboutOlauncher),
            m(R.string.extras_settings_row, general, R.id.moreFeatures),
            m(R.string.update_check, general, R.id.rate),
            m(R.string.whats_new, general, R.id.share),
            m(R.string.feedback, general, R.id.twitter),
            m(R.string.apps_on_home_screen, R.string.home_screen, R.id.homeAppsNum),
            m(R.string.show_date_time, R.string.home_screen, R.id.dateTime),
            m(R.string.home_layout_alignment, R.string.home_screen, R.id.alignment),
            m(R.string.screen_time, R.string.home_screen, R.id.screenTimeOnOff),
            m(R.string.auto_show_keyboard, R.string.appearance, R.id.autoShowKeyboard),
            m(R.string.icons, R.string.appearance, R.id.appIcons),
            m(R.string.daily_wallpaper_update, R.string.appearance, R.id.dailyWallpaper),
            m(R.string.notification_bar, R.string.appearance, R.id.statusBar),
            m(R.string.theme_mode, R.string.appearance, R.id.appThemeText),
            m(R.string.bold_font, R.string.appearance, R.id.boldFont),
            m(R.string.text_size, R.string.appearance, R.id.textSizeValue),
            m(R.string.swipe_left_app, R.string.gestures, R.id.swipeLeftApp),
            m(R.string.swipe_right_app, R.string.gestures, R.id.swipeRightApp),
            m(R.string.double_tap_to_lock_screen, R.string.gestures, R.id.toggleLock),
        )
    }

    private fun extras(): List<SettingEntry> {
        val list = mutableListOf<SettingEntry>()
        fun e(section: Int, label: Int, hint: Int = 0, fallback: Int = 0, minSdk: Int = 0) {
            list += SettingEntry(label, section, SettingsScreen.EXTRAS, listOfNotNull(hint.takeIf { it != 0 }), fallback = fallback, minSdk = minSdk)
        }
        val q = Build.VERSION_CODES.Q

        val home = R.string.extras_section_home
        e(home, R.string.weather, R.string.weather_hint)
        e(home, R.string.weather_place, R.string.weather_hint, fallback = R.string.weather)
        e(home, R.string.weather_units, R.string.weather_hint, fallback = R.string.weather)
        e(home, R.string.weather_app, R.string.weather_hint, fallback = R.string.weather)
        e(home, R.string.events, R.string.events_hint)
        e(home, R.string.note_on_home)
        e(home, R.string.widgets, R.string.widgets_hint)
        e(home, R.string.notifications_on_home, R.string.notifications_hint)
        e(home, R.string.notifications_line, R.string.notification_dots_hint, fallback = R.string.notifications_on_home)
        e(home, R.string.notification_dots, R.string.notification_dots_hint, fallback = R.string.notifications_on_home)
        e(home, R.string.notification_dot_style, R.string.notification_dots_hint, fallback = R.string.notifications_on_home)
        e(home, R.string.notification_dots_folders, R.string.notification_dots_hint, fallback = R.string.notifications_on_home)
        e(home, R.string.media_controls, R.string.media_hint)
        e(home, R.string.sublist, R.string.sublist_hint)
        e(home, R.string.sublist_apps, R.string.sublist_hint, fallback = R.string.sublist)
        e(home, R.string.sublist_button, R.string.sublist_hint, fallback = R.string.sublist)
        e(home, R.string.sublist_style, R.string.sublist_hint, fallback = R.string.sublist)
        e(home, R.string.sublist_free, R.string.sublist_free_hint, fallback = R.string.sublist)
        e(home, R.string.sublist_auto, R.string.sublist_auto_hint, fallback = R.string.sublist, minSdk = q)

        val search = R.string.extras_section_search
        e(search, R.string.search_contacts, R.string.search_contacts_hint)
        e(search, R.string.search_calculator, R.string.search_calculator_hint)
        e(search, R.string.search_web, R.string.search_web_hint)
        e(search, R.string.search_hold_toggle, R.string.search_hold_toggle_hint)
        e(search, R.string.search_hold_actions, R.string.search_hold_actions_hint)
        e(search, R.string.search_shortcuts, R.string.search_shortcuts_hint)
        e(search, R.string.az_scroller, R.string.az_scroller_hint)
        e(search, R.string.az_side, R.string.az_scroller_hint)
        e(search, R.string.az_height, R.string.az_scroller_hint)
        e(search, R.string.suggested_apps, R.string.suggested_apps_hint, minSdk = q)
        e(search, R.string.suggested_no_suggest, R.string.suggested_no_suggest_hint, minSdk = q)
        e(search, R.string.sort_by_usage, R.string.sort_by_usage_hint, minSdk = q)
        e(search, R.string.slot_swipes, R.string.slot_swipes_hint)
        e(search, R.string.folders, R.string.folders_hint)

        val look = R.string.extras_section_look
        e(look, R.string.font, R.string.font_bold_hint)
        e(look, R.string.background_blur, R.string.background_blur_hint)
        e(look, R.string.force_eink, R.string.force_eink_hint)
        e(look, R.string.gesture_motion, R.string.gesture_motion_hint)
        e(look, R.string.daily_wallpaper_photos, R.string.daily_wallpaper_folders_hint)
        e(look, R.string.daily_wallpaper_light_folder, R.string.daily_wallpaper_folders_hint, fallback = R.string.daily_wallpaper_photos)
        e(look, R.string.daily_wallpaper_dark_folder, R.string.daily_wallpaper_folders_hint, fallback = R.string.daily_wallpaper_photos)
        e(look, R.string.daynight, R.string.daynight_hint)
        e(look, R.string.daynight_times, R.string.daynight_hint, fallback = R.string.daynight)
        e(look, R.string.daynight_day_wallpaper, R.string.daynight_hint, fallback = R.string.daynight)
        e(look, R.string.daynight_night_wallpaper, R.string.daynight_hint, fallback = R.string.daynight)

        e(R.string.extras_section_privacy, R.string.lock_hidden_apps, R.string.lock_hidden_apps_hint)
        e(R.string.extras_section_backup, R.string.config_export, R.string.config_export_hint)
        e(R.string.extras_section_backup, R.string.config_import, R.string.config_import_hint)
        e(R.string.extras_section_backup, R.string.config_undo, fallback = R.string.config_import)
        return list
    }

    /** Shortest query that also looks in the hints (shorter ones would match half the list). */
    private const val KEYWORD_MIN = 3

    /**
     * The entries matching [query], best first: label starts with it, label contains it, then every
     * word of the query starts a word of the label, section or hint. [text] resolves string ids.
     */
    fun search(query: String, entries: List<SettingEntry>, text: (Int) -> String): List<SettingEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val qNorm = normalize(q)
        val qWords = words(q)
        return entries.mapNotNull { entry ->
            val label = text(entry.label)
            val labelNorm = normalize(label)
            val score = when {
                labelNorm.startsWith(qNorm) -> 0
                appLabelMatches(label, q) -> 1
                qWords.isNotEmpty() && run {
                    val useKeywords = q.length >= KEYWORD_MIN
                    val haystack = buildList {
                        addAll(words(label))
                        addAll(words(text(entry.section)))
                        if (useKeywords) entry.keywords.forEach { addAll(words(text(it))) }
                    }
                    qWords.all { w -> haystack.any { it.startsWith(w) } }
                } -> 2
                else -> return@mapNotNull null
            }
            score to entry
        }.sortedBy { it.first }.map { it.second }
    }

    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "").lowercase()

    private fun words(s: String) = normalize(s).split(nonWord).filter { it.isNotEmpty() }

    /** The entries this build and device actually show. */
    fun available(updatesSupported: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<SettingEntry> =
        entries.filter { sdk >= it.minSdk && (updatesSupported || it.label != R.string.update_check) }
}
