package app.olauncher.extras

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.extras.daynight.DayNight
import app.olauncher.extras.daynight.DayNightWallpapers
import app.olauncher.extras.daynight.Phase
import app.olauncher.extras.folders.FolderStore
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.search.SlotSwipes
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.extras.notifications.InilauncherNotificationListener
import app.olauncher.extras.notifications.NotificationDots
import app.olauncher.extras.notifications.NotificationsDialog
import app.olauncher.extras.picker.AppPickerScreen
import app.olauncher.extras.sublist.SubListMenu
import app.olauncher.extras.sublist.SubListStore
import app.olauncher.extras.weather.WeatherPlaceDialog
import app.olauncher.extras.weather.WeatherRepository
import app.olauncher.extras.weather.WeatherWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.olauncher.extras.wallpaper.DailyWallpapers
import app.olauncher.helper.showMessageDialog
import app.olauncher.extras.wallpaper.WallpaperFolders
import app.olauncher.extras.look.BackgroundBlur
import app.olauncher.extras.look.FontChoice
import app.olauncher.extras.privacy.HiddenAppsLock
import app.olauncher.extras.search.DrawerExtras
import app.olauncher.extras.search.SuggestedBlocklist
import app.olauncher.helper.showToast
import app.olauncher.databinding.FragmentExtrasSettingsBinding
import app.olauncher.ui.BaseFragment

/**
 * Inilauncher's Extras screen, opened from the "Extras…" row on upstream's settings
 * screen. Each feature registers its rows into one of the four sections.
 */
class ExtrasSettingsFragment : BaseFragment() {

    private var _binding: FragmentExtrasSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var extras: ExtrasPrefs

    /** Turns [pendingKey] on once the permission is granted. */
    private var pendingKey: String? = null
    private var pendingDenied: Int = 0
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val key = pendingKey ?: return@registerForActivityResult
        pendingKey = null
        if (granted) {
            extras.setBool(key, true)
            onEnabled(key)
        } else requireContext().showToast(getString(pendingDenied))
        populate()
    }

    /** Day/night photo picker (the system photo picker, no storage permission). */
    private var pendingPhotoPhase: Phase? = null
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        ExtrasState.awaitingSystemScreen = false
        val phase = pendingPhotoPhase ?: return@registerForActivityResult
        pendingPhotoPhase = null
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            if (!DayNightWallpapers.savePhoto(context, phase, uri)) context.showToast(getString(R.string.daynight_photo_failed))
            else if (DayNight.isEnabled(context) && DayNight.now(context).phase == phase) DayNight.syncInBackground(context, force = true)
            if (_binding != null) populate()
        }
    }

    /** Daily wallpaper folder picker (the system folder picker, no storage permission). */
    private var pendingFolderDark: Boolean? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        ExtrasState.awaitingSystemScreen = false
        val dark = pendingFolderDark ?: return@registerForActivityResult
        pendingFolderDark = null
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val empty = withContext(Dispatchers.IO) {
                try {
                    WallpaperFolders.set(context, dark, uri)
                    WallpaperFolders.photos(context, uri).isEmpty()
                } catch (e: Exception) {
                    null
                }
            }
            when (empty) {
                null -> context.showToast(getString(R.string.daily_wallpaper_folder_failed))
                true -> context.showToast(getString(R.string.daily_wallpaper_folder_empty))
                false -> {}
            }
            // An empty folder still counts: the other one is used meanwhile
            if (empty != null) DailyWallpapers.refreshNow(context)
            if (_binding != null) populate()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtrasSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        extras = ExtrasPrefs(requireContext())
        populate()
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in system settings while we were away
        populate()
    }

    private fun populate() {
        val sections = listOf(binding.sectionHome, binding.sectionSearch, binding.sectionLook, binding.sectionPrivacy)
        sections.forEach { it.removeViews(1, it.childCount - 1) }

        // Home screen
        weatherRows()
        permissionToggle(binding.sectionHome, R.string.events, ExtrasKeys.EVENTS_ENABLED,
            Manifest.permission.READ_CALENDAR, R.string.events_need_permission)
        addHint(binding.sectionHome, R.string.events_hint)
        toggle(binding.sectionHome, R.string.note_on_home, ExtrasKeys.NOTE_ENABLED)
        toggle(binding.sectionHome, R.string.widgets, ExtrasKeys.WIDGETS_ENABLED) { Folders.notifyChanged() }
        addHint(binding.sectionHome, R.string.widgets_hint)
        notificationAccessToggle(R.string.notifications_on_home, ExtrasKeys.NOTIFICATIONS_ENABLED)
        addHint(binding.sectionHome, R.string.notifications_hint)
        notificationDotRows()
        notificationAccessToggle(R.string.media_controls, ExtrasKeys.MEDIA_ENABLED)
        addHint(binding.sectionHome, R.string.media_hint)
        subListRows()

        // Search & app list
        searchRows()
        val folderCount = FolderStore(requireContext()).all().size
        addRow(binding.sectionSearch, R.string.folders, resources.getQuantityString(R.plurals.folder_count, folderCount, folderCount)) {}
        addHint(binding.sectionSearch, R.string.folders_hint)

        // Look
        addRow(binding.sectionLook, R.string.font, getString(FontChoice.current(extras).label)) { anchor ->
            choose(anchor, FontChoice.entries.map { getString(it.label) }) { index ->
                extras.setString(ExtrasKeys.FONT, FontChoice.entries[index].name)
                requireActivity().recreate()
            }
        }
        addHint(binding.sectionLook, R.string.font_bold_hint)
        if (BackgroundBlur.isSupported(requireActivity())) {
            toggle(binding.sectionLook, R.string.background_blur, ExtrasKeys.BLUR) { BackgroundBlur.refresh(requireActivity()) }
            addHint(binding.sectionLook, R.string.background_blur_hint)
        } else {
            addRow(binding.sectionLook, R.string.background_blur, getString(R.string.off)) {}
            addHint(binding.sectionLook, R.string.background_blur_unsupported)
        }
        toggle(binding.sectionLook, R.string.force_eink, ExtrasKeys.FORCE_EINK) { requireActivity().recreate() }
        addHint(binding.sectionLook, R.string.force_eink_hint)
        toggle(binding.sectionLook, R.string.gesture_motion, ExtrasKeys.GESTURE_MOTION)
        addHint(binding.sectionLook, R.string.gesture_motion_hint)
        dailyWallpaperRows()
        dayNightRows()

        // Privacy & security
        lockRow()
        addHint(binding.sectionPrivacy, R.string.lock_hidden_apps_hint)

        sections.forEach { if (it.childCount == 1) addHint(it, R.string.extras_nothing_yet) }

        // Version line, so testers can tell builds apart (selectable)
        binding.versionLine.text = getString(R.string.version_line, app.olauncher.versionLine)
    }

    /** "Lock hidden apps": turning it on needs a screen lock, turning it off needs that lock opened. */
    private fun lockRow() {
        addRow(binding.sectionPrivacy, R.string.lock_hidden_apps, onOff(HiddenAppsLock.isEnabled(requireContext()))) { value ->
            val context = requireContext()
            if (!HiddenAppsLock.isEnabled(context)) {
                if (!HiddenAppsLock.canAuthenticate(context)) return@addRow context.showToast(getString(R.string.lock_no_screen_lock))
                extras.setBool(ExtrasKeys.LOCK_HIDDEN_APPS, true)
                HiddenAppsLock.relock()
                value.text = onOff(true)
                return@addRow
            }
            val turnOff = {
                extras.setBool(ExtrasKeys.LOCK_HIDDEN_APPS, false)
                if (_binding != null) value.text = onOff(false)
            }
            if (HiddenAppsLock.isUnlocked(context)) turnOff()
            else HiddenAppsLock.unlock(this, R.string.lock_prompt_subtitle_off) { turnOff() }
        }
    }

    private fun weatherRows() {
        val section = binding.sectionHome
        val repo = WeatherRepository(requireContext())
        addRow(section, R.string.weather, onOff(repo.isEnabled())) { anchor ->
            if (repo.isEnabled()) {
                extras.setBool(ExtrasKeys.WEATHER_ENABLED, false)
                WeatherWorker.cancel(requireContext())
                populate()
            } else if (repo.manualPlace() != null || repo.hasLocationPermission()) {
                extras.setBool(ExtrasKeys.WEATHER_ENABLED, true)
                onEnabled(ExtrasKeys.WEATHER_ENABLED)
                populate()
            } else {
                // Without location access you can still pick a city by name
                choose(anchor, listOf(getString(R.string.weather_place_auto), getString(R.string.weather_place_search))) { i ->
                    if (i == 0) {
                        pendingKey = ExtrasKeys.WEATHER_ENABLED
                        pendingDenied = R.string.weather_need_location
                        askPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    } else WeatherPlaceDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, repo) {
                        extras.setBool(ExtrasKeys.WEATHER_ENABLED, true)
                        onEnabled(ExtrasKeys.WEATHER_ENABLED)
                    }
                }
            }
        }
        if (repo.isEnabled()) {
            addRow(section, R.string.weather_place, repo.manualPlace()?.name ?: getString(R.string.weather_place_auto)) { anchor ->
                choose(anchor, listOf(getString(R.string.weather_place_auto), getString(R.string.weather_place_search))) { i ->
                    if (i == 0) {
                        repo.setManualPlace(null)
                        if (!repo.hasLocationPermission()) {
                            pendingKey = ExtrasKeys.WEATHER_ENABLED
                            pendingDenied = R.string.weather_need_location
                            askPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        refreshWeather()
                    } else WeatherPlaceDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, repo) {
                        refreshWeather()
                    }
                }
            }
            addRow(section, R.string.weather_units, getString(if (repo.useFahrenheit()) R.string.weather_fahrenheit else R.string.weather_celsius)) { anchor ->
                choose(anchor, listOf(getString(R.string.weather_celsius), getString(R.string.weather_fahrenheit))) { i ->
                    extras.setBool(ExtrasKeys.WEATHER_FAHRENHEIT, i == 1)
                    refreshWeather()
                }
            }
            val appLabel = extras.string(ExtrasKeys.WEATHER_APP)?.let { NotificationsDialog.appLabel(requireContext(), it) }
            addRow(section, R.string.weather_app, appLabel ?: getString(R.string.weather_app_none)) {
                AppPickerScreen.pickOne(requireContext(), getString(R.string.weather_app), getString(R.string.weather_app_none)) { app ->
                    extras.setString(ExtrasKeys.WEATHER_APP, app?.packageName)
                    if (_binding != null) populate()
                }
            }
        }
        addHint(section, R.string.weather_hint)
        if (repo.isEnabled()) addHint(section, R.string.weather_attribution)
    }

    /** Where upstream's daily wallpaper comes from: our list, or the user's light and dark folders. */
    private fun dailyWallpaperRows() {
        val section = binding.sectionLook
        val context = requireContext()
        val folders = DailyWallpapers.usesFolders(context)
        val options = listOf(getString(R.string.daily_wallpaper_from_list), getString(R.string.daily_wallpaper_from_folders))
        addRow(section, R.string.daily_wallpaper_photos, options[if (folders) 1 else 0]) { anchor ->
            choose(anchor, options) { i ->
                if ((i == 1) == folders) return@choose
                DailyWallpapers.setSource(context, i == 1)
                populate()
            }
        }
        if (folders) {
            folderRow(R.string.daily_wallpaper_light_folder, dark = false)
            folderRow(R.string.daily_wallpaper_dark_folder, dark = true)
        }
        addHint(section, if (folders) R.string.daily_wallpaper_folders_hint else R.string.daily_wallpaper_list_hint)
    }

    private fun folderRow(@StringRes label: Int, dark: Boolean) {
        val context = requireContext()
        val tree = WallpaperFolders.folder(context, dark)
        val value = when {
            tree == null -> getString(R.string.daily_wallpaper_choose_folder)
            else -> WallpaperFolders.name(context, tree) ?: getString(R.string.daily_wallpaper_folder_gone)
        }
        addRow(binding.sectionLook, label, value) { anchor ->
            val launchPicker = {
                pendingFolderDark = dark
                // The picker stops the launcher, which would otherwise go home and drop the result
                ExtrasState.awaitingSystemScreen = true
                pickFolder.launch(tree)
            }
            if (tree == null) return@addRow launchPicker()
            choose(anchor, listOf(getString(R.string.daily_wallpaper_other_folder), getString(R.string.daily_wallpaper_remove_folder))) { i ->
                if (i == 0) launchPicker()
                else {
                    WallpaperFolders.clear(context, dark)
                    DailyWallpapers.refreshNow(context)
                    populate()
                }
            }
        }
    }

    /** "Day & night by sun": the toggle, where the times come from, and the two wallpapers. */
    private fun dayNightRows() {
        val section = binding.sectionLook
        val context = requireContext()
        val repo = WeatherRepository(context)
        val enabled = DayNight.isEnabled(context)
        addRow(section, R.string.daynight, onOff(enabled)) { anchor ->
            when {
                enabled -> {
                    DayNight.turnOff(context)
                    populate()
                }
                extras.bool(ExtrasKeys.DAYNIGHT_FIXED) || DayNight.coordinates(context) != null || repo.hasLocationPermission() -> {
                    extras.setBool(ExtrasKeys.DAYNIGHT_ENABLED, true)
                    onEnabled(ExtrasKeys.DAYNIGHT_ENABLED)
                    populate()
                }
                else -> chooseSunSource(anchor) {
                    extras.setBool(ExtrasKeys.DAYNIGHT_ENABLED, true)
                    onEnabled(ExtrasKeys.DAYNIGHT_ENABLED)
                }
            }
        }
        if (enabled) {
            addRow(section, R.string.daynight_times, sunTimesText()) { anchor -> chooseSunSource(anchor) { onEnabled(ExtrasKeys.DAYNIGHT_ENABLED) } }
            wallpaperRow(R.string.daynight_day_wallpaper, Phase.DAY)
            wallpaperRow(R.string.daynight_night_wallpaper, Phase.NIGHT)
        }
        addHint(section, R.string.daynight_hint)
    }

    private fun sunTimesText(): String {
        val context = requireContext()
        val format = android.text.format.DateFormat.getTimeFormat(context)
        DayNight.today(context)?.let {
            return getString(R.string.daynight_times_sun, format.format(java.util.Date(it.sunriseMs)), format.format(java.util.Date(it.sunsetMs)))
        }
        if (DayNight.coordinates(context) != null) return getString(R.string.daynight_times_polar)
        return getString(R.string.daynight_times_fixed, minutesText(DayNight.dayStart(context)), minutesText(DayNight.nightStart(context)))
    }

    private fun minutesText(minutes: Int): String {
        val time = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
            set(java.util.Calendar.MINUTE, minutes % 60)
        }
        return android.text.format.DateFormat.getTimeFormat(requireContext()).format(time.time)
    }

    /** My location / search a city (shared with weather) / fixed times. [onChosen] runs once a source is set. */
    private fun chooseSunSource(anchor: View, onChosen: () -> Unit) {
        val repo = WeatherRepository(requireContext())
        val options = listOf(getString(R.string.weather_place_auto), getString(R.string.weather_place_search), getString(R.string.daynight_fixed_times))
        choose(anchor, options) { i ->
            when (i) {
                0 -> {
                    extras.setBool(ExtrasKeys.DAYNIGHT_FIXED, false)
                    repo.setManualPlace(null)
                    if (repo.hasLocationPermission()) {
                        onChosen()
                        populate()
                    } else {
                        // Granting it turns the feature on (onEnabled syncs); denied keeps the fixed times
                        pendingKey = ExtrasKeys.DAYNIGHT_ENABLED
                        pendingDenied = R.string.daynight_need_location
                        askPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
                1 -> WeatherPlaceDialog.show(requireContext(), viewLifecycleOwner.lifecycleScope, repo) {
                    extras.setBool(ExtrasKeys.DAYNIGHT_FIXED, false)
                    onChosen()
                    if (_binding != null) populate()
                }
                else -> pickTime(R.string.daynight_day_starts, DayNight.dayStart(requireContext())) { day ->
                    pickTime(R.string.daynight_night_starts, DayNight.nightStart(requireContext())) { night ->
                        extras.setInt(ExtrasKeys.DAYNIGHT_DAY_START, day)
                        extras.setInt(ExtrasKeys.DAYNIGHT_NIGHT_START, night)
                        extras.setBool(ExtrasKeys.DAYNIGHT_FIXED, true)
                        onChosen()
                        if (_binding != null) populate()
                    }
                }
            }
        }
    }

    private fun pickTime(@StringRes title: Int, minutes: Int, onPicked: (Int) -> Unit) {
        android.app.TimePickerDialog(requireContext(), { _, hour, minute -> onPicked(hour * 60 + minute) },
            minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(requireContext())
        ).apply { setTitle(title) }.also { it.show(); app.olauncher.extras.HomeButton.track(it) }
    }

    private fun wallpaperRow(@StringRes label: Int, phase: Phase) {
        val context = requireContext()
        val value = when {
            DayNightWallpapers.hasPhoto(context, phase) -> R.string.daynight_wallpaper_photo
            app.olauncher.data.Prefs(context).dailyWallpaper -> R.string.daynight_wallpaper_daily
            else -> R.string.daynight_wallpaper_unchanged
        }
        addRow(binding.sectionLook, label, getString(value)) { anchor ->
            val launchPicker = {
                pendingPhotoPhase = phase
                // The picker stops the launcher, which would otherwise go home and drop the result
                ExtrasState.awaitingSystemScreen = true
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (!DayNightWallpapers.hasPhoto(context, phase)) return@addRow launchPicker()
            choose(anchor, listOf(getString(R.string.daynight_pick_photo), getString(R.string.daynight_remove_photo))) { i ->
                if (i == 0) launchPicker()
                else {
                    DayNightWallpapers.removePhoto(context, phase)
                    populate()
                }
            }
        }
    }

    /** The dot options, shown while "Notifications on home screen" is on. */
    private fun notificationDotRows() {
        if (!extras.bool(ExtrasKeys.NOTIFICATIONS_ENABLED) || !InilauncherNotificationListener.isAccessGranted(requireContext())) return
        val section = binding.sectionHome
        toggle(section, R.string.notifications_line, ExtrasKeys.NOTIFICATIONS_NO_LINE, inverted = true)
        toggle(section, R.string.notification_dots, ExtrasKeys.NOTIFICATIONS_NO_DOTS, inverted = true)
        choice(section, R.string.notification_dot_style, ExtrasKeys.NOTIFICATION_DOT_STYLE, listOf(
            NotificationDots.STYLE_DOT to R.string.notification_dot_style_dot,
            NotificationDots.STYLE_COUNT to R.string.notification_dot_style_count,
        ))
        toggle(section, R.string.notification_dots_folders, ExtrasKeys.NOTIFICATION_DOTS_FOLDERS)
        addHint(section, R.string.notification_dots_hint)
    }

    /** The home sub list: on/off, then its apps and looks (also on the long-press menu of its button). */
    private fun subListRows() {
        val section = binding.sectionHome
        toggle(section, R.string.sublist, ExtrasKeys.SUBLIST_ENABLED) { populate() }
        val store = SubListStore(requireContext())
        if (store.enabled) {
            addRow(section, R.string.sublist_apps, store.apps().size.toString()) {
                SubListMenu.chooseApps(requireContext()) { if (_binding != null) populate() }
            }
            choice(section, R.string.sublist_button, ExtrasKeys.SUBLIST_BUTTON, SubListMenu.BUTTONS)
            choice(section, R.string.sublist_style, ExtrasKeys.SUBLIST_STYLE, SubListMenu.STYLES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                toggle(section, R.string.sublist_auto, ExtrasKeys.SUBLIST_AUTO, canEnable = ::usageAccess)
                addHint(section, R.string.sublist_auto_hint)
            }
        }
        addHint(section, R.string.sublist_hint)
    }

    private fun searchRows() {
        val section = binding.sectionSearch
        permissionToggle(section, R.string.search_contacts, ExtrasKeys.SEARCH_CONTACTS,
            Manifest.permission.READ_CONTACTS, R.string.search_contacts_need)
        addHint(section, R.string.search_contacts_hint)
        toggle(section, R.string.search_calculator, ExtrasKeys.SEARCH_CALCULATOR)
        addHint(section, R.string.search_calculator_hint)
        toggle(section, R.string.search_web, ExtrasKeys.SEARCH_WEB)
        addHint(section, R.string.search_web_hint)
        toggle(section, R.string.search_hold_toggle, ExtrasKeys.SEARCH_HOLD_TOGGLE)
        addHint(section, R.string.search_hold_toggle_hint)
        toggle(section, R.string.search_hold_actions, ExtrasKeys.SEARCH_HOLD_ACTIONS)
        addHint(section, R.string.search_hold_actions_hint)
        toggle(section, R.string.search_shortcuts, ExtrasKeys.SEARCH_SHORTCUTS)
        addHint(section, R.string.search_shortcuts_hint)
        toggle(section, R.string.az_scroller, ExtrasKeys.AZ_SCROLLER)
        choice(section, R.string.az_side, ExtrasKeys.AZ_SIDE, listOf(
            "" to R.string.az_side_auto, DrawerExtras.SIDE_LEFT to R.string.az_side_left, DrawerExtras.SIDE_RIGHT to R.string.az_side_right,
        ))
        choice(section, R.string.az_height, ExtrasKeys.AZ_HEIGHT, listOf(
            "" to R.string.az_height_full, DrawerExtras.HEIGHT_THUMB to R.string.az_height_thumb,
        ))
        addHint(section, R.string.az_scroller_hint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            toggle(section, R.string.suggested_apps, ExtrasKeys.SUGGESTED_APPS, canEnable = ::usageAccess)
            addHint(section, R.string.suggested_apps_hint)
            addRow(section, R.string.suggested_no_suggest, SuggestedBlocklist.summary(requireContext())) { value ->
                SuggestedBlocklist.choose(requireContext()) {
                    if (_binding != null) value.text = SuggestedBlocklist.summary(requireContext())
                }
            }
            addHint(section, R.string.suggested_no_suggest_hint)
            toggle(section, R.string.sort_by_usage, ExtrasKeys.SORT_BY_USAGE, canEnable = ::usageAccess)
            addHint(section, R.string.sort_by_usage_hint)
        }

        addRow(section, R.string.slot_swipes, "") { showSlotSwipes() }
        addHint(section, R.string.slot_swipes_hint)
    }

    /** Usage access, granted on the same system screen upstream uses for screen time. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun usageAccess(): Boolean = requireContext().appUsagePermissionGranted().also {
        if (!it) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun showSlotSwipes() {
        val prefs = app.olauncher.data.Prefs(requireContext())
        val names = listOf(prefs.appName1, prefs.appName2, prefs.appName3, prefs.appName4,
            prefs.appName5, prefs.appName6, prefs.appName7, prefs.appName8).take(prefs.homeAppsNum)
        val entries = names.mapIndexed { i, name -> i + 1 to name.ifBlank { getString(R.string.slot_swipes_empty_slot, i + 1) } }
            .flatMap { (slot, name) -> listOf(Triple(slot, true, name), Triple(slot, false, name)) }
        val labels = entries.map { (slot, left, name) ->
            val target = SlotSwipes.target(requireContext(), slot, left)?.let { NotificationsDialog.appLabel(requireContext(), it) }
                ?: getString(R.string.slot_swipes_none)
            getString(if (left) R.string.slot_swipes_left else R.string.slot_swipes_right, name) + " → " + target
        }
        ListDialog.show(requireContext(), getString(R.string.slot_swipes), labels) { i ->
            val (slot, left, _) = entries[i]
            AppPickerScreen.pickOne(requireContext(), labels[i].substringBefore(" → "), getString(R.string.slot_swipes_none)) { app ->
                SlotSwipes.setTarget(requireContext(), slot, left, app?.packageName)
            }
        }
    }

    private fun refreshWeather() {
        val repo = WeatherRepository(requireContext())
        repo.clearCache()
        viewLifecycleOwner.lifecycleScope.launch {
            repo.refresh(force = true)
            if (_binding != null) populate()
        }
        populate()
    }

    private fun onEnabled(key: String) {
        if (key == ExtrasKeys.DAYNIGHT_ENABLED) {
            DayNight.syncInBackground(requireContext(), force = true)
            if (DayNightWallpapers.noneSet(requireContext())) offerDailyWallpaper()
        }
        if (key == ExtrasKeys.WEATHER_ENABLED) {
            WeatherWorker.schedule(requireContext())
            refreshWeather()
        }
    }

    /** Day & night without any wallpaper to switch: offer the daily wallpaper (light by day, dark at night). */
    private fun offerDailyWallpaper() {
        val context = requireContext()
        val dialog = context.showMessageDialog(
            R.string.daynight_offer_daily_title, R.string.daynight_offer_daily_message, R.string.daynight_offer_daily_action
        ) {
            val appContext = context.applicationContext
            app.olauncher.data.Prefs(appContext).dailyWallpaper = true
            DailyWallpapers.refreshNow(appContext)
            if (_binding != null) populate()
        }
        app.olauncher.extras.HomeButton.track(dialog)
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    /** A toggle that needs a runtime [permission] first. */
    private fun permissionToggle(section: LinearLayout, @StringRes label: Int, key: String, permission: String, @StringRes denied: Int) {
        addRow(section, label, onOff(extras.bool(key))) {
            if (extras.bool(key)) {
                extras.setBool(key, false)
                populate()
            } else if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
                extras.setBool(key, true)
                populate()
            } else {
                pendingKey = key
                pendingDenied = denied
                askPermission.launch(permission)
            }
        }
    }

    /** A toggle that needs Notification access, which is granted in system settings. */
    private fun notificationAccessToggle(@StringRes label: Int, key: String) {
        val granted = InilauncherNotificationListener.isAccessGranted(requireContext())
        addRow(binding.sectionHome, label, onOff(extras.bool(key) && granted)) {
            if (extras.bool(key) && granted) {
                extras.setBool(key, false)
                populate()
                return@addRow
            }
            extras.setBool(key, true)
            if (!granted) {
                requireContext().showToast(getString(R.string.notifications_grant))
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            populate()
        }
    }

    /**
     * An On/Off row for a boolean extra. [canEnable] can refuse turning it on. [inverted] is for
     * "off" keys (e.g. NOTIFICATIONS_NO_LINE): the row shows On while the key is false.
     */
    private fun toggle(
        section: LinearLayout,
        @StringRes label: Int,
        key: String,
        canEnable: () -> Boolean = { true },
        inverted: Boolean = false,
        onChanged: () -> Unit = {},
    ) {
        fun isOn() = extras.bool(key) != inverted
        fun valueText() = getString(if (isOn()) R.string.on else R.string.off)
        addRow(section, label, valueText()) { value ->
            val turnOn = !isOn()
            if (turnOn && !canEnable()) return@addRow
            extras.setBool(key, turnOn != inverted)
            value.text = valueText()
            onChanged()
        }
    }

    /** A row whose value is one of [options] (saved value → label), stored under [key]. */
    private fun choice(section: LinearLayout, @StringRes label: Int, key: String, options: List<Pair<String, Int>>) {
        fun valueText() = getString((options.firstOrNull { it.first == extras.string(key).orEmpty() } ?: options.first()).second)
        addRow(section, label, valueText()) { value ->
            choose(value, options.map { getString(it.second) }) { i ->
                extras.setString(key, options[i].first.ifEmpty { null })
                value.text = valueText()
            }
        }
    }

    /** A popup list anchored to a row's value. */
    private fun choose(anchor: View, options: List<String>, onChosen: (Int) -> Unit) {
        // Upstream's styled popup, hanging off the end edge like its settings menus
        anchor.showChoices(options, onChosen)
    }

    /**
     * A row with a [label] and a current [value]; tapping it runs [onClick]. The value takes the space
     * left after the label and wraps there, so a long value never runs over the label.
     */
    fun addRow(section: LinearLayout, @StringRes label: Int, value: CharSequence, onClick: (TextView) -> Unit): TextView {
        val gap = (8 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.extras_row_gap) }
        val labelView = TextView(requireContext(), null, 0, R.style.ExtrasRowLabel).apply { setText(label) }
        val valueView = TextView(requireContext(), null, 0, R.style.ExtrasRowValue).apply {
            text = value
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(labelView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = gap })
        row.addView(valueView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 2 * gap })
        row.setOnClickListener { onClick(valueView) }
        section.addView(row)
        return valueView
    }

    fun addHint(section: LinearLayout, @StringRes text: Int): TextView =
        TextView(requireContext(), null, 0, R.style.ExtrasRowHint).apply {
            setText(text)
            // Layout margins in a style aren't applied to views created in code
            val side = (8 * resources.displayMetrics.density).toInt()
            section.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(side, side, side, side) })
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
