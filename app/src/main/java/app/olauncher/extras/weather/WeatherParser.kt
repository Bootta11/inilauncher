package app.olauncher.extras.weather

import org.json.JSONObject
import kotlin.math.roundToInt

/** Current conditions as shown on the home screen. */
data class Weather(val temperature: Int, val code: Int, val fetchedAt: Long)

data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

/** Parses Open-Meteo responses. Pure functions, unit tested. */
object WeatherParser {

    /** `current=temperature_2m,weather_code` forecast response; null when it doesn't have both. */
    fun parseCurrent(json: String, now: Long): Weather? = try {
        val current = JSONObject(json).getJSONObject("current")
        if (!current.has("temperature_2m") || !current.has("weather_code")) null
        else Weather(current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"), now)
    } catch (e: Exception) {
        null
    }

    /** Geocoding search response; an empty list when nothing matched. */
    fun parsePlaces(json: String): List<Place> = try {
        val results = JSONObject(json).optJSONArray("results")
        if (results == null) emptyList()
        else (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            if (!r.has("latitude") || !r.has("longitude")) return@mapNotNull null
            Place(
                name = r.optString("name"),
                detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", "),
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Groups WMO weather codes into the few conditions shown on the home screen. */
    fun condition(code: Int): Condition = when (code) {
        0 -> Condition.CLEAR
        1, 2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        in 51..57 -> Condition.DRIZZLE
        in 61..67, in 80..82 -> Condition.RAIN
        in 71..77, 85, 86 -> Condition.SNOW
        in 95..99 -> Condition.THUNDER
        else -> Condition.UNKNOWN
    }

    /** True when [weather] is older than [maxAgeMs] at [now] (or missing). */
    fun isStale(weather: Weather?, now: Long, maxAgeMs: Long): Boolean =
        weather == null || now - weather.fetchedAt > maxAgeMs
}

enum class Condition { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER, UNKNOWN }
