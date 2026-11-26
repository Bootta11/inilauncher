package app.olauncher.extras

import app.olauncher.helper.fadeVisible
import app.olauncher.extras.gestures.GestureMotion
import app.olauncher.helper.fadeTo
import app.olauncher.helper.animateChanges
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.olauncher.R
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.extras.calendar.EventItem
import app.olauncher.extras.calendar.UpcomingEvents
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.search.SlotSwipes
import app.olauncher.extras.sublist.SubList
import app.olauncher.extras.media.MediaControls
import app.olauncher.extras.notes.HomeNote
import app.olauncher.extras.notifications.InilauncherNotificationListener
import app.olauncher.extras.notifications.NotificationDots
import app.olauncher.extras.notifications.NotificationsDialog
import app.olauncher.extras.weather.Condition
import app.olauncher.extras.weather.WeatherParser
import app.olauncher.extras.weather.WeatherRepository
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Fills the extras block under the clock (layout/extras_home.xml). Called at the end of
 * upstream's HomeFragment.populateDateTime(), so it follows the same refreshes.
 */
object HomeExtras {

    fun bind(binding: FragmentHomeBinding) {
        val extras = binding.extrasHome
        val context = binding.root.context
        val prefs = ExtrasPrefs(context)
        // Lines fade in/out and slide, text that changes length resizes smoothly (see Motion)
        extras.root.animateChanges()
        extras.extrasEvents.animateChanges(changing = false)
        binding.dateTimeLayout.animateChanges(changing = false)

        bindWeather(binding, prefs)
        bindEvents(binding, prefs)
        HomeNote.bind(extras.extrasNote)
        bindMedia(binding, prefs)
        bindNotifications(binding)
        updateBlock(binding)
    }

    /** Shows the block when any line in it is shown. */
    private fun updateBlock(binding: FragmentHomeBinding) {
        val root = binding.extrasHome.root
        val anyVisible = (0 until root.childCount).any { root.getChildAt(it).isVisible }
        root.isVisible = anyVisible
        // Upstream hides the whole clock block when date and time are off; keep it for the extras
        if (anyVisible) binding.dateTimeLayout.isVisible = true
    }

    /** Called after upstream fills the home app names (HomeFragment.onResume hook). Also binds the sub list. */
    fun decorateHomeApps(binding: FragmentHomeBinding) {
        val context = binding.root.context
        NotificationDots.decorateHome(context, homeAppViews(binding))
        val prefs = app.olauncher.data.Prefs(context)
        val slots = listOf(
            prefs.appPackage1 to prefs.appActivityClassName1, prefs.appPackage2 to prefs.appActivityClassName2,
            prefs.appPackage3 to prefs.appActivityClassName3, prefs.appPackage4 to prefs.appActivityClassName4,
            prefs.appPackage5 to prefs.appActivityClassName5, prefs.appPackage6 to prefs.appActivityClassName6,
            prefs.appPackage7 to prefs.appActivityClassName7, prefs.appPackage8 to prefs.appActivityClassName8,
        )
        homeAppViews(binding).forEachIndexed { i, view ->
            val (pkg, cls) = slots[i]
            AppIcons.decorate(view, pkg, cls, android.os.Process.myUserHandle(), async = false)
        }
        SubList.bind(binding)
    }

    /** A tap on empty home space: closes an open sub list. True when it did (the tap is used up). */
    fun tapOutside(binding: FragmentHomeBinding): Boolean = SubList.closeOnTapOutside(binding)

    /** Per-app swipe on a home app name; false lets upstream's screen-wide swipe run. */
    fun slotSwipe(binding: FragmentHomeBinding, view: android.view.View, left: Boolean): Boolean {
        val slot = homeAppViews(binding).indexOf(view) + 1
        if (slot <= 0) return false
        if (SlotSwipes.target(view.context, slot, left) != null) GestureMotion.slotLaunching(view, left)
        return SlotSwipes.handle(view.context, slot, left)
    }

    private fun homeAppViews(b: FragmentHomeBinding) =
        listOf(b.homeApp1, b.homeApp2, b.homeApp3, b.homeApp4, b.homeApp5, b.homeApp6, b.homeApp7, b.homeApp8)

    private fun bindWeather(binding: FragmentHomeBinding, prefs: ExtrasPrefs) {
        val view = binding.extrasHome.extrasWeather
        val repo = WeatherRepository(view.context)
        view.isVisible = repo.isEnabled()
        if (!view.isVisible) return
        val cached = repo.cached()
        view.text = cached?.let { weatherText(view.context, it.temperature, it.code) } ?: "…"
        view.setOnClickListener { openWeatherApp(view.context, prefs) }
        if (WeatherParser.isStale(cached, System.currentTimeMillis(), 60 * 60 * 1000L)) {
            view.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                val fresh = repo.refresh() ?: return@launch
                view.text = weatherText(view.context, fresh.temperature, fresh.code)
            }
        }
    }

    fun weatherText(context: Context, temperature: Int, code: Int): String {
        val condition = when (WeatherParser.condition(code)) {
            Condition.CLEAR -> R.string.weather_clear
            Condition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
            Condition.CLOUDY -> R.string.weather_cloudy
            Condition.FOG -> R.string.weather_fog
            Condition.DRIZZLE -> R.string.weather_drizzle
            Condition.RAIN -> R.string.weather_rain
            Condition.SNOW -> R.string.weather_snow
            Condition.THUNDER -> R.string.weather_thunder
            Condition.UNKNOWN -> R.string.weather_unknown
        }
        return context.getString(R.string.weather_format, temperature, context.getString(condition))
    }

    private fun openWeatherApp(context: Context, prefs: ExtrasPrefs) {
        val pkg = prefs.string(ExtrasKeys.WEATHER_APP) ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_app))
        }
    }

    /** The last events read, so the home screen draws them at once; the calendar is read in the background. */
    private var events: List<EventItem> = emptyList()
    private var eventsReadAt = 0L
    private var readingEvents = false
    private const val EVENTS_EVERY_MS = 60_000L

    private fun bindEvents(binding: FragmentHomeBinding, prefs: ExtrasPrefs) {
        val container = binding.extrasHome.extrasEvents
        val context = container.context
        if (!prefs.bool(ExtrasKeys.EVENTS_ENABLED) || !UpcomingEvents.hasPermission(context)) {
            events = emptyList()
            eventsReadAt = 0L // turned on again later: read at once
            showEvents(container, emptyList())
            return
        }
        val now = System.currentTimeMillis()
        showEvents(container, UpcomingEvents.pick(events, now))
        if (readingEvents || now - eventsReadAt < EVENTS_EVERY_MS) return
        val owner = container.findViewTreeLifecycleOwner() ?: return
        val app = context.applicationContext
        readingEvents = true
        owner.lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { UpcomingEvents.load(app) }
                eventsReadAt = System.currentTimeMillis()
                if (fresh == events) return@launch
                events = fresh
                if (container.isAttachedToWindow) {
                    showEvents(container, fresh)
                    updateBlock(binding)
                }
            } finally {
                readingEvents = false
            }
        }
    }

    private fun showEvents(container: LinearLayout, events: List<EventItem>) {
        val context = container.context
        // Rebuilding the same rows would make them blink out and back in
        if (container.getTag(R.id.extras_events_shown) == events && container.childCount == events.size) return
        container.setTag(R.id.extras_events_shown, events)
        container.removeAllViews()
        container.isVisible = events.isNotEmpty()
        if (events.isEmpty()) return
        val timeFormat = DateFormat.getTimeFormat(context)
        events.forEach { event ->
            val time = if (event.allDay) context.getString(R.string.events_all_day) else timeFormat.format(Date(event.begin))
            container.addView(TextView(context, null, 0, R.style.ExtrasHomeText).apply {
                text = context.getString(R.string.events_line, time, event.title)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    try {
                        context.startActivity(UpcomingEvents.openIntent(event))
                    } catch (e: Exception) {
                        context.showToast(context.getString(R.string.unable_to_open_app))
                    }
                }
            })
        }
    }

    private fun bindMedia(binding: FragmentHomeBinding, prefs: ExtrasPrefs) {
        val extras = binding.extrasHome
        val now = if (prefs.bool(ExtrasKeys.MEDIA_ENABLED)) MediaControls.current(binding.root.context) else null
        extras.extrasMedia.isVisible = now != null
        if (now == null) return
        extras.extrasMediaTitle.text = listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" – ")
        extras.extrasMediaPlay.setText(if (now.playing) R.string.media_symbol_pause else R.string.media_symbol_play)
        extras.extrasMediaPrev.setOnClickListener { MediaControls.previous(now); rebindSoon(binding) }
        extras.extrasMediaPlay.setOnClickListener { MediaControls.togglePlay(now); rebindSoon(binding) }
        extras.extrasMediaNext.setOnClickListener { MediaControls.next(now); rebindSoon(binding) }
    }

    private fun rebindSoon(binding: FragmentHomeBinding) =
        binding.root.postDelayed({ if (binding.root.isAttachedToWindow) bind(binding) }, 400)

    private fun bindNotifications(binding: FragmentHomeBinding) {
        val view = binding.extrasHome.extrasNotifications
        val context = view.context
        val count = InilauncherNotificationListener.notifications.value.size
        val hidden = InilauncherNotificationListener.hiddenCount.value
        val show = NotificationDots.lineEnabled(context) && (count > 0 || hidden > 0) &&
                InilauncherNotificationListener.isAccessGranted(context)
        if (!show) return view.fadeVisible(false)
        // Only hidden ones left: a faded line, so they can still be brought back
        view.text = if (count > 0) context.resources.getQuantityString(R.plurals.notifications_count, count, count)
        else context.resources.getQuantityString(R.plurals.notifications_hidden_count, hidden, hidden)
        val alpha = if (count > 0) 1f else 0.6f
        view.fadeTo(alpha) // already shown: fades to it
        view.fadeVisible(true, alpha) // just shown: fades in to it
        view.setOnClickListener { NotificationsDialog.show(context) }
    }

    /** Re-binds when notifications change while the home screen is visible. Called from onViewCreated. */
    fun observe(binding: FragmentHomeBinding, owner: LifecycleOwner) {
        SubList.observe(binding, owner)
        HomeButton.onPress(owner) { SubList.closeOnTapOutside(binding) }
        val root = binding.extrasHome.root
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                InilauncherNotificationListener.notifications.combine(InilauncherNotificationListener.hiddenCount) { _, _ -> }.collect {
                    val prefs = ExtrasPrefs(root.context)
                    bindNotifications(binding)
                    bindMedia(binding, prefs)
                    decorateHomeApps(binding)
                    updateBlock(binding)
                }
            }
        }
    }
}
