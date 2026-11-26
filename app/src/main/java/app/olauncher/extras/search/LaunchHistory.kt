package app.olauncher.extras.search

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** App launches from usage access (Android 10+). Used by the app list and the home sub list. */
object LaunchHistory {

    /** Launches over the last [days] days. Call off the main thread. */
    fun launches(context: Context, days: Int, now: Long = System.currentTimeMillis()): List<LaunchEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(now - days * 24L * 60 * 60 * 1000, now) ?: return emptyList()
        val event = UsageEvents.Event()
        val launches = mutableListOf<LaunchEvent>()
        var last: LaunchEvent? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            // Count a stay in one app once, not every screen inside it
            if (last?.packageName == event.packageName && event.timeStamp - last.time < 60_000) continue
            last = LaunchEvent(event.packageName, event.timeStamp)
            launches += last
        }
        return launches
    }
}
