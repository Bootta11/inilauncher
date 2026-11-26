package app.olauncher.extras.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/** A calendar event instance shown on the home screen. */
data class EventItem(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/** Reads the next few events from the phone's calendars (READ_CALENDAR). Nothing leaves the phone. */
object UpcomingEvents {

    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    const val MAX_EVENTS = 3

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context, now: Long = System.currentTimeMillis()): List<EventItem> {
        if (!hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + WINDOW_MS)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val items = mutableListOf<EventItem>()
        try {
            context.contentResolver.query(
                uri, projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    items += EventItem(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return pick(items, now)
    }

    /** Events that haven't ended yet, timed ones first by start, then all-day ones. Pure, unit tested. */
    fun pick(items: List<EventItem>, now: Long, max: Int = MAX_EVENTS): List<EventItem> =
        items.filter { it.end > now && it.title.isNotBlank() }
            .sortedWith(compareBy<EventItem> { it.allDay }.thenBy { it.begin })
            .take(max)

    fun openIntent(item: EventItem): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
