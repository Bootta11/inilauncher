package app.olauncher.extras.search

import app.olauncher.data.Constants

/**
 * "Hold" search: find an app without it opening by itself. A leading space (upstream Olauncher)
 * or the optional search-bar toggle turns auto-open off; a single match then shows its actions.
 */
object HoldSearch {
    /** The query was typed with a leading space (shown with the "no auto-open" chip). */
    fun spaced(query: CharSequence?, flag: Int): Boolean =
        flag == Constants.FLAG_LAUNCH_APP && query?.startsWith(" ") == true

    /** Auto-open is paused for this query, and a single match should show its actions. */
    fun isHold(query: CharSequence?, flag: Int, toggled: Boolean): Boolean =
        flag == Constants.FLAG_LAUNCH_APP && !query.isNullOrBlank() && !query.startsWith("!") &&
            (query.startsWith(" ") || toggled)
}
