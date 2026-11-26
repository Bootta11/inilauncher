package app.olauncher.extras

import app.olauncher.extras.search.LaunchEvent
import app.olauncher.extras.search.UsageSort
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSortTest {

    private fun sort(labels: List<String>, counts: Map<String, Int>) =
        UsageSort.sortWithinLetters(labels, { it }) { counts[it] ?: 0 }

    @Test
    fun mostUsedFirstWithinLetter() {
        val labels = listOf("Settings", "Signal", "Spotify")
        assertEquals(listOf("Spotify", "Signal", "Settings"), sort(labels, mapOf("Signal" to 3, "Spotify" to 9)))
    }

    @Test
    fun lettersStayInOrder() {
        val labels = listOf("Calendar", "Camera", "Maps", "Messages", "Zoom")
        val sorted = sort(labels, mapOf("Zoom" to 100, "Messages" to 5, "Camera" to 2))
        assertEquals(listOf("Camera", "Calendar", "Messages", "Maps", "Zoom"), sorted)
    }

    @Test
    fun tiesKeepAlphabeticalOrder() {
        val labels = listOf("Bank", "Books", "Browser")
        assertEquals(labels, sort(labels, mapOf("Bank" to 2, "Books" to 2, "Browser" to 2)))
        assertEquals(labels, sort(labels, emptyMap()))
    }

    @Test
    fun caseAndNonLettersGrouped() {
        assertEquals('A', UsageSort.group("app"))
        assertEquals('#', UsageSort.group("1Password"))
        assertEquals('#', UsageSort.group(""))
        val labels = listOf("1Password", "7-Zip", "app", "Authenticator")
        assertEquals(
            listOf("7-Zip", "1Password", "Authenticator", "app"),
            sort(labels, mapOf("7-Zip" to 1, "Authenticator" to 4)),
        )
    }

    @Test
    fun countsPerPackage() {
        val events = listOf(LaunchEvent("a", 1), LaunchEvent("b", 2), LaunchEvent("a", 3))
        assertEquals(mapOf("a" to 2, "b" to 1), UsageSort.counts(events))
    }
}
