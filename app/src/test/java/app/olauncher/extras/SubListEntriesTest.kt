package app.olauncher.extras

import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.sublist.SubListEntries
import org.junit.Assert.assertEquals
import org.junit.Test

class SubListEntriesTest {

    @Test
    fun rankMostUsedFirstTiesAlphabetical() {
        val counts = mapOf("b" to 3, "a" to 3, "c" to 9, "zero" to 0)
        assertEquals(listOf("c", "a", "b"), SubListEntries.rank(counts))
    }

    @Test
    fun fillsUpToTheLimitAfterPickedApps() {
        val picked = listOf("p1", "p2")
        val ranked = listOf("p1", "x1", "x2", "x3", "x4")
        assertEquals(listOf("x1", "x2"), SubListEntries.autoPackages(picked, ranked, emptySet(), fillTo = 4))
    }

    @Test
    fun skipsExcludedAndPickedApps() {
        val ranked = listOf("home", "own", "p", "x", "hidden", "y")
        val auto = SubListEntries.autoPackages(listOf("p"), ranked, setOf("home", "own", "hidden"), fillTo = 10)
        assertEquals(listOf("x", "y"), auto)
    }

    @Test
    fun nothingWhenPickedAppsAlreadyFillIt() {
        val picked = (1..10).map { "p$it" }
        assertEquals(emptyList<String>(), SubListEntries.autoPackages(picked, listOf("x"), emptySet()))
    }

    private fun app(name: String) = FolderApp(name, name, null, 0)

    @Test
    fun placesBeforeAnotherApp() {
        val apps = listOf("a", "b", "c").map(::app)
        fun names(list: List<FolderApp>) = list.joinToString("") { it.packageName }
        assertEquals("cab", names(SubListEntries.place(apps, app("c"), app("a"))))
        assertEquals("bca", names(SubListEntries.place(apps, app("a"), null)))
        assertEquals("abc", names(SubListEntries.place(apps, app("b"), app("c"))))
        // A most-used app dropped among them is added
        assertEquals("axbc", names(SubListEntries.place(apps, app("x"), app("b"))))
        assertEquals("abcx", names(SubListEntries.place(apps, app("x"), null)))
        // "b" isn't installed, so only a and c are shown: swapping them keeps it
        assertEquals("bca", names(SubListEntries.place(apps, app("a"), null)))
    }

    @Test
    fun placeTellsProfilesApart() {
        val work = FolderApp("a", "a", null, 10)
        val placed = SubListEntries.place(listOf(app("a"), app("b"), work), work, app("a"))
        assertEquals(listOf(work, app("a"), app("b")), placed)
    }
}
