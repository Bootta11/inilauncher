package app.olauncher.extras.search

import kotlin.math.exp

/** One app launch from the usage history. */
data class LaunchEvent(val packageName: String, val time: Long)

/**
 * Ranks apps for the "Suggested" row: recent use, how often, and whether you tend to
 * use the app at this hour. Pure, unit tested.
 */
object SuggestionRanking {

    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    fun rank(events: List<LaunchEvent>, now: Long, hourOfDay: (Long) -> Int, exclude: Set<String>, max: Int = 4): List<String> {
        val nowHour = hourOfDay(now)
        val scores = HashMap<String, Double>()
        events.filter { it.packageName !in exclude && it.time <= now }.forEach { event ->
            val ageDays = (now - event.time).toDouble() / DAY
            val recency = exp(-ageDays / 3.0) // halves roughly every two days
            val hourDistance = minOf(Math.floorMod(hourOfDay(event.time) - nowHour, 24), Math.floorMod(nowHour - hourOfDay(event.time), 24))
            val sameTime = if (hourDistance <= 1) 1.5 else 1.0
            scores[event.packageName] = (scores[event.packageName] ?: 0.0) + recency * sameTime
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(max).map { it.key }
    }
}
