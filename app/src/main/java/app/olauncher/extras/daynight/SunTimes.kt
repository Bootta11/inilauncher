package app.olauncher.extras.daynight

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset for one day, or the sun staying up / down all day (far north and south). */
sealed class SunDay {
    data class Normal(val sunriseMs: Long, val sunsetMs: Long) : SunDay()
    data object PolarDay : SunDay()
    data object PolarNight : SunDay()
}

/**
 * Offline sunrise/sunset ("sunrise equation", NOAA-style approximation, about a minute off).
 * Plain math with epoch millis: no java.time, which needs API 26, and no network.
 */
object SunTimes {

    private const val MS_PER_DAY = 86_400_000.0
    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5

    /**
     * [epochDay] is the local calendar day (days since 1970-01-01), [longitude] positive east.
     * The times returned are UTC epoch millis.
     */
    fun forDay(latitude: Double, longitude: Double, epochDay: Long): SunDay {
        // Days since J2000 at noon of this date, moved to the place's mean solar noon
        val n = (epochDay + 2440588 - 2451545).toDouble()
        val jStar = n - longitude / 360.0
        val m = norm(357.5291 + 0.98560028 * jStar)
        val mRad = Math.toRadians(m)
        val c = 1.9148 * sin(mRad) + 0.02 * sin(2 * mRad) + 0.0003 * sin(3 * mRad)
        val lambda = Math.toRadians(norm(m + c + 180 + 102.9372))
        val transit = J2000 + jStar + 0.0053 * sin(mRad) - 0.0069 * sin(2 * lambda)
        val declination = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val lat = Math.toRadians(latitude)
        // -0.833°: refraction plus the sun's radius, so "sunrise" is the top edge showing
        val cosOmega = (sin(Math.toRadians(-0.833)) - sin(lat) * sin(declination)) / (cos(lat) * cos(declination))
        if (cosOmega > 1) return SunDay.PolarNight
        if (cosOmega < -1) return SunDay.PolarDay
        val omega = Math.toDegrees(acos(cosOmega)) / 360.0
        return SunDay.Normal(julianToMillis(transit - omega), julianToMillis(transit + omega))
    }

    private fun julianToMillis(jd: Double) = ((jd - UNIX_EPOCH_JD) * MS_PER_DAY).toLong()

    private fun norm(degrees: Double) = ((degrees % 360) + 360) % 360
}
