package app.olauncher.extras.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/**
 * Weather from Open-Meteo (open-meteo.com, free, no key). Only the place's coordinates
 * are sent. The last result is cached in [ExtrasPrefs] and shown while offline.
 */
class WeatherRepository(private val context: Context) {

    private val prefs = ExtrasPrefs(context)

    fun isEnabled() = prefs.bool(ExtrasKeys.WEATHER_ENABLED)
    fun useFahrenheit() = prefs.bool(ExtrasKeys.WEATHER_FAHRENHEIT)
    fun manualPlace(): Place? {
        val name = prefs.string(ExtrasKeys.WEATHER_PLACE_NAME) ?: return null
        val lat = prefs.string(ExtrasKeys.WEATHER_PLACE_LAT)?.toDoubleOrNull() ?: return null
        val lon = prefs.string(ExtrasKeys.WEATHER_PLACE_LON)?.toDoubleOrNull() ?: return null
        return Place(name, "", lat, lon)
    }

    fun setManualPlace(place: Place?) {
        prefs.setString(ExtrasKeys.WEATHER_PLACE_NAME, place?.name)
        prefs.setString(ExtrasKeys.WEATHER_PLACE_LAT, place?.latitude?.toString())
        prefs.setString(ExtrasKeys.WEATHER_PLACE_LON, place?.longitude?.toString())
        clearCache()
    }

    fun cached(): Weather? {
        val temp = prefs.string(ExtrasKeys.WEATHER_CACHE_TEMP)?.toIntOrNull() ?: return null
        return Weather(temp, prefs.int(ExtrasKeys.WEATHER_CACHE_CODE), prefs.string(ExtrasKeys.WEATHER_CACHE_TIME)?.toLongOrNull() ?: 0)
    }

    fun clearCache() = prefs.setString(ExtrasKeys.WEATHER_CACHE_TEMP, null)

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The manual place, or the phone's last known coarse location. */
    @SuppressLint("MissingPermission")
    fun coordinates(): Pair<Double, Double>? {
        manualPlace()?.let { return it.latitude to it.longitude }
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) || it == LocationManager.PASSIVE_PROVIDER }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        // Two decimals (~1 km) is plenty for weather and sends less detail
        return round2(location.latitude) to round2(location.longitude)
    }

    /**
     * Fetches current weather and caches it. Returns null when offline, without a place, or when a refresh
     * is already running. Unless [force] (a settings change, the hourly worker), it waits [RETRY_AFTER_MS]
     * after a failed try, so going home again and again while offline doesn't send a request each time.
     */
    suspend fun refresh(force: Boolean = false): Weather? = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return@withContext null
        if (!refreshing.compareAndSet(false, true)) return@withContext null
        try {
            val weather = fetch()
            failedAt = if (weather == null) System.currentTimeMillis() else 0L
            weather
        } finally {
            refreshing.set(false)
        }
    }

    private fun fetch(): Weather? {
        val (lat, lon) = coordinates() ?: return null
        val unit = if (useFahrenheit()) "&temperature_unit=fahrenheit" else ""
        val url = String.format(Locale.US, "%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code%s", FORECAST_URL, lat, lon, unit)
        val weather = get(url)?.let { WeatherParser.parseCurrent(it, System.currentTimeMillis()) } ?: return null
        prefs.setString(ExtrasKeys.WEATHER_CACHE_TEMP, weather.temperature.toString())
        prefs.setInt(ExtrasKeys.WEATHER_CACHE_CODE, weather.code)
        prefs.setString(ExtrasKeys.WEATHER_CACHE_TIME, weather.fetchedAt.toString())
        return weather
    }

    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language
        get("$GEOCODING_URL?name=$q&count=5&language=$lang")?.let { WeatherParser.parsePlaces(it) } ?: emptyList()
    }

    private fun get(url: String): String? = Http.getText(url, TIMEOUT_MS, MAX_BODY_BYTES)

    private fun round2(value: Double) = Math.round(value * 100) / 100.0

    companion object {
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val RETRY_AFTER_MS = 15 * 60 * 1000L
        /** One refresh at a time in the whole app (home screen, settings, worker). */
        private val refreshing = AtomicBoolean(false)
        @Volatile private var failedAt = 0L
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
    }
}
