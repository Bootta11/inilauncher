package app.olauncher.extras.search

/**
 * "Sort by usage": keeps the app list's A–Z letter groups where they are, but puts the
 * apps you open most first inside each letter. Pure, unit tested.
 */
object UsageSort {

    /** Letter group of a label: its first letter, or '#' for digits, symbols and empty labels. */
    fun group(label: String): Char {
        val first = label.trimStart().firstOrNull() ?: return '#'
        return if (first.isLetter()) first.uppercaseChar() else '#'
    }

    /**
     * [items] must already be in A–Z order. Each run of items in the same letter group is
     * reordered by [count], highest first; equal counts keep their A–Z order.
     */
    fun <T> sortWithinLetters(items: List<T>, label: (T) -> String, count: (T) -> Int): List<T> {
        if (items.size < 2) return items
        val result = ArrayList<T>(items.size)
        var start = 0
        while (start < items.size) {
            val letter = group(label(items[start]))
            var end = start + 1
            while (end < items.size && group(label(items[end])) == letter) end++
            // sortedByDescending is stable, so ties stay alphabetical
            result += items.subList(start, end).sortedByDescending(count)
            start = end
        }
        return result
    }

    /** Launches per package from [events]. */
    fun counts(events: List<LaunchEvent>): Map<String, Int> = events.groupingBy { it.packageName }.eachCount()
}
