package app.olauncher.extras

import app.olauncher.extras.calendar.EventItem
import app.olauncher.extras.calendar.UpcomingEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingEventsTest {

    private val now = 10_000L

    @Test
    fun keepsUnfinishedTimedEventsFirstThenAllDay() {
        val items = listOf(
            EventItem(1, "All day", 0, 90_000, allDay = true),
            EventItem(2, "Later", 30_000, 40_000, allDay = false),
            EventItem(3, "Ended", 1_000, 5_000, allDay = false),
            EventItem(4, "Now", 9_000, 20_000, allDay = false),
            EventItem(5, "", 12_000, 13_000, allDay = false),
        )
        assertEquals(listOf(4L, 2L, 1L), UpcomingEvents.pick(items, now).map { it.eventId })
    }

    @Test
    fun limitsCount() {
        val items = (1..10).map { EventItem(it.toLong(), "E$it", now + it, now + it + 100, false) }
        assertEquals(3, UpcomingEvents.pick(items, now).size)
    }
}
