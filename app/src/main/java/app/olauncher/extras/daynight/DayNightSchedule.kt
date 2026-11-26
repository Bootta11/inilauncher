package app.olauncher.extras.daynight

import java.util.TimeZone

enum class Phase {
    DAY, NIGHT;

    fun other() = if (this == DAY) NIGHT else DAY
}

/** The look to show now, and when it next changes. */
data class PhaseNow(val phase: Phase, val nextSwitchMs: Long)

/**
 * Works out day or night from sunrise/sunset at a place, or from fixed times when no place is
 * known. Pure (no Android), so it's unit tested.
 */
object DayNightSchedule {

    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_MINUTE = 60_000L

    /** Local calendar day of [utcMs] in [zone]. */
    fun epochDay(utcMs: Long, zone: TimeZone): Long = Math.floorDiv(utcMs + zone.getOffset(utcMs), MS_PER_DAY)

    /** UTC millis of [minutes] after local midnight of [epochDay]. */
    fun localTime(epochDay: Long, minutes: Int, zone: TimeZone): Long {
        val local = epochDay * MS_PER_DAY + minutes * MS_PER_MINUTE
        return local - zone.getOffset(local - zone.rawOffset)
    }

    /**
     * [coordinates] null means "use the fixed times": [dayStartMinutes] and [nightStartMinutes]
     * after midnight.
     */
    fun phaseAt(
        nowMs: Long,
        zone: TimeZone,
        coordinates: Pair<Double, Double>?,
        dayStartMinutes: Int,
        nightStartMinutes: Int,
    ): PhaseNow {
        val today = epochDay(nowMs, zone)
        // Every switch from yesterday to the day after tomorrow, in time order
        val events = (today - 1..today + 2).flatMap { day -> eventsFor(day, zone, coordinates, dayStartMinutes, nightStartMinutes) }
            .sortedBy { it.first }
        val phase = events.lastOrNull { it.first <= nowMs }?.second
            ?: events.first().second.let { if (it == Phase.DAY) Phase.NIGHT else Phase.DAY }
        // Polar summer/winter: nothing changes in the window, so look again in two days
        val next = events.firstOrNull { it.first > nowMs && it.second != phase }?.first
            ?: localTime(today + 2, 0, zone)
        return PhaseNow(phase, next)
    }

    private fun eventsFor(
        day: Long, zone: TimeZone, coordinates: Pair<Double, Double>?, dayStart: Int, nightStart: Int,
    ): List<Pair<Long, Phase>> {
        if (coordinates == null) return listOf(
            localTime(day, dayStart, zone) to Phase.DAY,
            localTime(day, nightStart, zone) to Phase.NIGHT,
        )
        return when (val sun = SunTimes.forDay(coordinates.first, coordinates.second, day)) {
            is SunDay.Normal -> listOf(sun.sunriseMs to Phase.DAY, sun.sunsetMs to Phase.NIGHT)
            SunDay.PolarDay -> listOf(localTime(day, 0, zone) to Phase.DAY)
            SunDay.PolarNight -> listOf(localTime(day, 0, zone) to Phase.NIGHT)
        }
    }
}
