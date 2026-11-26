package app.olauncher.extras

import android.os.SystemClock

/** In-memory state shared between the extras and upstream's activity. */
object ExtrasState {
    /**
     * True while a system screen we started for a result is open (widget permission or
     * setup). Upstream returns to the home screen whenever the launcher stops, which
     * would close the widget panel and lose the result, so that's skipped meanwhile.
     */
    @Volatile var awaitingSystemScreen = false

    /**
     * When a widget on the Widgets panel was last tapped. A tap can open the widget's app
     * (e.g. To Do's "Add a task"); the launcher then stays on the panel instead of going home,
     * so closing that screen lands back on the widgets. Home still always goes home.
     */
    @Volatile private var widgetTapAt = 0L
    private const val WIDGET_TAP_MS = 1500L

    fun markWidgetTap() { widgetTapAt = SystemClock.uptimeMillis() }
    fun clearWidgetTap() { widgetTapAt = 0L }
    fun leavingForWidget(): Boolean =
        widgetTapAt != 0L && SystemClock.uptimeMillis() - widgetTapAt < WIDGET_TAP_MS
}
