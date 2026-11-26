package app.olauncher.extras

import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.picker.AppPickerRows
import app.olauncher.extras.picker.AppPickerRows.Row
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerRowsTest {

    private fun app(label: String) = FolderApp(label, "p.$label", null, 0)
    private val apps = listOf(app("Acrobat"), app("Maps"), app("Notes"))
    private val contains: (String, String) -> Boolean = { label, q -> label.contains(q, ignoreCase = true) }

    @Test
    fun noPinnedAppsMeansNoHeaders() {
        val rows = AppPickerRows.build(apps, emptyList(), "", "Selected", "All", contains)
        assertEquals(apps.map { Row.App(it) }, rows)
    }

    @Test
    fun pinnedAppsOnTopThenEverything() {
        val rows = AppPickerRows.build(apps, listOf(apps[2]), "", "Selected", "All", contains)
        assertEquals(
            listOf(Row.Header("Selected"), Row.App(apps[2]), Row.Header("All")) + apps.map { Row.App(it) },
            rows,
        )
    }

    @Test
    fun searchShowsOnlyMatchesWithoutSections() {
        val rows = AppPickerRows.build(apps, listOf(apps[2]), "ma", "Selected", "All", contains)
        assertEquals(listOf(Row.App(apps[1])), rows)
    }

    @Test
    fun lettersSkipThePinnedSection() {
        val rows = AppPickerRows.build(apps, listOf(apps[2]), "", "Selected", "All", contains)
        assertEquals(listOf(null, null, null, "Acrobat", "Maps", "Notes"), AppPickerRows.letterLabels(rows))
        val plain = AppPickerRows.build(apps, emptyList(), "", "Selected", "All", contains)
        assertEquals(listOf("Acrobat", "Maps", "Notes"), AppPickerRows.letterLabels(plain))
    }

    @Test
    fun mostUsedAfterSelectedWithoutRepeats() {
        val rows = AppPickerRows.build(apps, listOf(apps[2]), "", "Selected", "All", contains,
            suggested = listOf(apps[2], apps[1]), suggestedTitle = "Most used")
        assertEquals(
            listOf(Row.Header("Selected"), Row.App(apps[2]), Row.Header("Most used"), Row.App(apps[1]), Row.Header("All")) +
                apps.map { Row.App(it) },
            rows,
        )
        // Letters still jump into "All apps" only
        assertEquals(listOf(null, null, null, null, null, "Acrobat", "Maps", "Notes"), AppPickerRows.letterLabels(rows))
    }
}
