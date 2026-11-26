package app.olauncher.extras

import app.olauncher.extras.notifications.NotificationGrouping
import app.olauncher.extras.notifications.NotificationItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationGroupingTest {

    private fun item(key: String, pkg: String, time: Long, title: String = "t") =
        NotificationItem(key, pkg, title, "", time, clearable = true)

    @Test
    fun visibleDropsOwnAndEmptyAndSortsNewestFirst() {
        val items = listOf(item("a", "mail", 1), item("b", "self", 5), item("c", "chat", 3), item("d", "mail", 2, title = ""))
        assertEquals(listOf("c", "a"), NotificationGrouping.visible(items, "self").map { it.key })
    }

    @Test
    fun groupsByAppNewestAppFirst() {
        val items = listOf(item("a", "mail", 1), item("b", "chat", 9), item("c", "mail", 4))
        assertEquals(listOf("chat", "mail"), NotificationGrouping.byApp(items).map { it.first })
        assertEquals(mapOf("mail" to 2, "chat" to 1), NotificationGrouping.countsByPackage(items))
    }
}
