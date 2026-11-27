package app.olauncher.extras.daynight

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.weather.WeatherRepository
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * "Day & night by sun": light theme + day wallpaper from sunrise, dark theme + night wallpaper
 * from sunset. Sunrise and sunset come from the weather place or the phone's rough location and
 * are calculated on the phone; without a place, fixed times are used.
 */
object DayNight {

    const val DEFAULT_DAY_START = 7 * 60
    const val DEFAULT_NIGHT_START = 19 * 60

    fun isEnabled(context: Context) = ExtrasPrefs(context).bool(ExtrasKeys.DAYNIGHT_ENABLED)

    fun dayStart(context: Context) = ExtrasPrefs(context).int(ExtrasKeys.DAYNIGHT_DAY_START, DEFAULT_DAY_START)
    fun nightStart(context: Context) = ExtrasPrefs(context).int(ExtrasKeys.DAYNIGHT_NIGHT_START, DEFAULT_NIGHT_START)

    /**
     * Coordinates for sunrise/sunset, or null for the fixed times. A fresh location is saved, so the
     * background worker still knows the place when the phone has no location fix.
     */
    fun coordinates(context: Context): Pair<Double, Double>? {
        val prefs = ExtrasPrefs(context)
        if (prefs.bool(ExtrasKeys.DAYNIGHT_FIXED)) return null
        WeatherRepository(context).coordinates()?.let {
            prefs.setString(ExtrasKeys.DAYNIGHT_LAT, it.first.toString())
            prefs.setString(ExtrasKeys.DAYNIGHT_LON, it.second.toString())
            return it
        }
        val lat = prefs.string(ExtrasKeys.DAYNIGHT_LAT)?.toDoubleOrNull() ?: return null
        val lon = prefs.string(ExtrasKeys.DAYNIGHT_LON)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun now(context: Context, nowMs: Long = System.currentTimeMillis()): PhaseNow =
        DayNightSchedule.phaseAt(nowMs, TimeZone.getDefault(), coordinates(context), dayStart(context), nightStart(context))

    /** Today's sunrise and sunset, or null without a place (or in polar day/night). */
    fun today(context: Context): SunDay.Normal? {
        val (lat, lon) = coordinates(context) ?: return null
        val day = DayNightSchedule.epochDay(System.currentTimeMillis(), TimeZone.getDefault())
        return SunTimes.forDay(lat, lon, day) as? SunDay.Normal
    }

    /**
     * Brings theme and wallpaper in line with the time of day, then schedules the next run.
     * Only acts when the phase changed, unless [force] (turning it on, a new photo).
     */
    suspend fun sync(context: Context, force: Boolean = false) = lock.withLock {
        val app = context.applicationContext
        if (!isEnabled(app)) return@withLock
        val extras = ExtrasPrefs(app)
        val current = now(app)
        // E-ink screens stay light (upstream forces it), so there's nothing to switch
        var pending = false
        if (!app.isEinkDisplay() && (force || extras.string(ExtrasKeys.DAYNIGHT_LAST_PHASE) != current.phase.name)) {
            pending = !switchTo(app, current.phase)
        } else {
            // The download after the last switch may have failed (offline): try again, once it's missing only
            DayNightWallpapers.prefetchDaily(app, current.phase.other(), onlyIfMissing = true)
        }
        // Last: called from the worker, REPLACE cancels the run that's still going.
        // A switch still waiting for its wallpaper tries again once there's a network.
        if (pending) DayNightWorker.schedule(app, System.currentTimeMillis() + RETRY_MS, needsNetwork = true)
        else DayNightWorker.schedule(app, current.nextSwitchMs)
    }

    /**
     * Wallpaper first; the theme follows only once it's set, so the text never ends up the wrong
     * colour for the wallpaper behind it. False (nothing changed) when the wallpaper couldn't be set.
     * When the wallpaper stays as it is, so does the theme: it already matches that wallpaper, and
     * Android's guess at a wallpaper's brightness isn't reliable (stale on some phones).
     */
    private suspend fun switchTo(app: Context, phase: Phase): Boolean {
        val applied = DayNightWallpapers.apply(app, phase)
        if (applied == DayNightWallpapers.Applied.FAILED) return false
        val prefs = Prefs(app)
        if (applied == DayNightWallpapers.Applied.CHANGED)
            prefs.appTheme = if (phase == Phase.DAY) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        ExtrasPrefs(app).setString(ExtrasKeys.DAYNIGHT_LAST_PHASE, phase.name)
        // An open launcher gets a uiMode change and recreates in MainActivity.onConfigurationChanged;
        // otherwise MainActivity picks the theme up when it starts
        withContext(Dispatchers.Main) { AppCompatDelegate.setDefaultNightMode(prefs.appTheme) }
        // Fetch the next one now, so the coming sunrise/sunset doesn't need a network
        DayNightWallpapers.prefetchDaily(app, phase.other())
        return true
    }

    private const val RETRY_MS = 15 * 60_000L

    /** Runs [sync] outside any screen's lifecycle, so a recreate halfway doesn't cancel it. */
    fun syncInBackground(context: Context, force: Boolean = false) {
        scope.launch { sync(context, force) }
    }

    // The worker and a return home can sync at the same moment
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun turnOff(context: Context) {
        val prefs = ExtrasPrefs(context)
        prefs.setBool(ExtrasKeys.DAYNIGHT_ENABLED, false)
        prefs.setString(ExtrasKeys.DAYNIGHT_LAST_PHASE, null)
        DayNightWorker.cancel(context)
    }

    /** A theme picked by hand wins: switching by the sun stops, so it doesn't undo the choice. */
    fun onManualTheme(context: Context) {
        if (!isEnabled(context)) return
        turnOff(context)
        context.showToast(context.getString(R.string.daynight_turned_off))
    }

    /** For upstream's daily wallpaper worker: don't replace the user's own photo for this phase. */
    fun ownPhotoShowing(context: Context): Boolean =
        isEnabled(context) && DayNightWallpapers.hasPhoto(context, now(context).phase)
}
