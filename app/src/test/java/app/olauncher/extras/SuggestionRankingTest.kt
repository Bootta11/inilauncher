package app.olauncher.extras

import app.olauncher.extras.search.LaunchEvent
import app.olauncher.extras.search.SuggestionRanking
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionRankingTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val hourOf: (Long) -> Int = { ((it / hour) % 24).toInt() }

    @Test
    fun frequentAndRecentWinExcludedDropped() {
        val now = 30 * day + 9 * hour
        val events = listOf(
            LaunchEvent("mail", now - hour), LaunchEvent("mail", now - day), LaunchEvent("mail", now - 2 * day),
            LaunchEvent("chat", now - 2 * hour),
            LaunchEvent("old", now - 20 * day), LaunchEvent("old", now - 21 * day),
            LaunchEvent("self", now - hour),
        )
        assertEquals(listOf("mail", "chat", "old"), SuggestionRanking.rank(events, now, hourOf, exclude = setOf("self")))
    }

    @Test
    fun sameTimeOfDayBreaksTies() {
        val now = 30 * day + 8 * hour
        val events = listOf(LaunchEvent("evening", now - day + 12 * hour), LaunchEvent("morning", now - day))
        assertEquals("morning", SuggestionRanking.rank(events, now, hourOf, emptySet()).first())
    }

    @Test
    fun limit() {
        val now = 10 * day
        val events = (1..10).map { LaunchEvent("app$it", now - it * hour) }
        assertEquals(4, SuggestionRanking.rank(events, now, hourOf, emptySet()).size)
    }
}
