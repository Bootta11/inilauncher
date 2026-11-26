package app.olauncher.extras.notifications

/** Pure helpers over the notification list, unit tested. */
object NotificationGrouping {

    /** Drops our own notifications and empty group summaries, newest first. */
    fun visible(items: List<NotificationItem>, ownPackage: String): List<NotificationItem> =
        items.filter { it.packageName != ownPackage && (it.title.isNotBlank() || it.text.isNotBlank()) }
            .sortedByDescending { it.postTime }

    /** Unread count per package. */
    fun countsByPackage(items: List<NotificationItem>): Map<String, Int> =
        items.groupingBy { it.packageName }.eachCount()

    /** Notifications grouped by app, apps ordered by their newest notification. */
    fun byApp(items: List<NotificationItem>): List<Pair<String, List<NotificationItem>>> =
        items.groupBy { it.packageName }.toList().sortedByDescending { (_, list) -> list.maxOf { it.postTime } }
}
