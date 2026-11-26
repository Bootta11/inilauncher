package app.olauncher.extras

import app.olauncher.extras.daynight.DayNightSchedule
import app.olauncher.extras.daynight.Phase
import app.olauncher.extras.daynight.SunDay
import app.olauncher.extras.daynight.SunTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DayNightTest {

    private val belgrade = 44.82 to 20.46
    private val zone: TimeZone = TimeZone.getTimeZone("Europe/Belgrade")

    private fun ms(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }.parse(text)!!.time

    private fun day(text: String) = DayNightSchedule.epochDay(ms("$text 12:00"), zone)

    private fun assertNear(expected: String, actualMs: Long, toleranceMin: Int = 3) {
        val diff = Math.abs(ms(expected) - actualMs) / 60_000
        assertTrue("expected $expected, off by $diff min", diff <= toleranceMin)
    }

    @Test
    fun belgradeSummerSolstice() {
        val sun = SunTimes.forDay(belgrade.first, belgrade.second, day("2026-06-21")) as SunDay.Normal
        assertNear("2026-06-21 04:51", sun.sunriseMs)
        assertNear("2026-06-21 20:29", sun.sunsetMs)
    }

    @Test
    fun belgradeWinterSolstice() {
        val sun = SunTimes.forDay(belgrade.first, belgrade.second, day("2026-12-21")) as SunDay.Normal
        assertNear("2026-12-21 07:10", sun.sunriseMs)
        assertNear("2026-12-21 16:02", sun.sunsetMs)
    }

    @Test
    fun polarDayAndNight() {
        assertEquals(SunDay.PolarDay, SunTimes.forDay(78.22, 15.65, day("2026-06-21")))
        assertEquals(SunDay.PolarNight, SunTimes.forDay(78.22, 15.65, day("2026-12-21")))
    }

    @Test
    fun phaseThroughTheDay() {
        val beforeSunrise = DayNightSchedule.phaseAt(ms("2026-06-21 03:00"), zone, belgrade, 420, 1140)
        assertEquals(Phase.NIGHT, beforeSunrise.phase)
        assertNear("2026-06-21 04:51", beforeSunrise.nextSwitchMs)

        val noon = DayNightSchedule.phaseAt(ms("2026-06-21 12:00"), zone, belgrade, 420, 1140)
        assertEquals(Phase.DAY, noon.phase)
        assertNear("2026-06-21 20:29", noon.nextSwitchMs)

        val lateEvening = DayNightSchedule.phaseAt(ms("2026-06-21 23:30"), zone, belgrade, 420, 1140)
        assertEquals(Phase.NIGHT, lateEvening.phase)
        assertNear("2026-06-22 04:51", lateEvening.nextSwitchMs)
    }

    @Test
    fun fixedTimesWithoutPlace() {
        val morning = DayNightSchedule.phaseAt(ms("2026-11-13 06:59"), zone, null, 420, 1140)
        assertEquals(Phase.NIGHT, morning.phase)
        assertEquals(ms("2026-11-13 07:00"), morning.nextSwitchMs)

        val evening = DayNightSchedule.phaseAt(ms("2026-11-13 19:00"), zone, null, 420, 1140)
        assertEquals(Phase.NIGHT, evening.phase)
        assertEquals(ms("2026-11-14 07:00"), evening.nextSwitchMs)
    }

    @Test
    fun polarSummerStaysDayAndChecksAgainLater() {
        val now = ms("2026-06-21 12:00")
        val result = DayNightSchedule.phaseAt(now, zone, 78.22 to 15.65, 420, 1140)
        assertEquals(Phase.DAY, result.phase)
        assertTrue(result.nextSwitchMs > now)
    }

    @Test
    fun fixedTimesAcrossDstChange() {
        // Europe switches to winter time on 2026-10-25
        assertEquals(ms("2026-10-25 07:00"), DayNightSchedule.localTime(day("2026-10-25"), 420, zone))
        assertEquals(ms("2026-03-29 19:00"), DayNightSchedule.localTime(day("2026-03-29"), 1140, zone))
    }
}
