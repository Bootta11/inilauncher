package app.olauncher.extras

import app.olauncher.extras.weather.Condition
import app.olauncher.extras.weather.Weather
import app.olauncher.extras.weather.WeatherParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParserTest {

    @Test
    fun parsesCurrentWeather() {
        val json = """{"latitude":44.8,"current":{"time":"2026-11-10T14:00","temperature_2m":12.6,"weather_code":3}}"""
        assertEquals(Weather(13, 3, 1000L), WeatherParser.parseCurrent(json, 1000L))
    }

    @Test
    fun rejectsIncompleteOrBadResponses() {
        assertNull(WeatherParser.parseCurrent("""{"current":{"temperature_2m":10}}""", 0))
        assertNull(WeatherParser.parseCurrent("""{"error":true}""", 0))
        assertNull(WeatherParser.parseCurrent("nope", 0))
    }

    @Test
    fun parsesPlaces() {
        val json = """{"results":[{"name":"Banja Luka","latitude":44.77,"longitude":17.19,"country":"Bosnia and Herzegovina","admin1":"Republika Srpska"},{"name":"No coords"}]}"""
        val places = WeatherParser.parsePlaces(json)
        assertEquals(1, places.size)
        assertEquals("Banja Luka", places[0].name)
        assertEquals("Republika Srpska, Bosnia and Herzegovina", places[0].detail)
        assertTrue(WeatherParser.parsePlaces("""{"generationtime_ms":0.5}""").isEmpty())
    }

    @Test
    fun mapsWeatherCodes() {
        assertEquals(Condition.CLEAR, WeatherParser.condition(0))
        assertEquals(Condition.CLOUDY, WeatherParser.condition(3))
        assertEquals(Condition.RAIN, WeatherParser.condition(63))
        assertEquals(Condition.RAIN, WeatherParser.condition(81))
        assertEquals(Condition.SNOW, WeatherParser.condition(85))
        assertEquals(Condition.THUNDER, WeatherParser.condition(95))
        assertEquals(Condition.UNKNOWN, WeatherParser.condition(42))
    }

    @Test
    fun staleness() {
        assertTrue(WeatherParser.isStale(null, 0, 1000))
        assertFalse(WeatherParser.isStale(Weather(1, 0, 500), 1000, 1000))
        assertTrue(WeatherParser.isStale(Weather(1, 0, 500), 2000, 1000))
    }
}
