package app.olauncher.extras

import app.olauncher.extras.privacy.HiddenApps
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenAppsTest {

    private val apps = listOf("mail|0", "bank|0", "bank|10", "maps|0")

    @Test
    fun lockedLeavesHiddenAppsOut() {
        assertEquals(listOf("mail|0", "bank|10", "maps|0"), HiddenApps.filter(apps, setOf("bank|0"), show = false) { it })
    }

    @Test
    fun openOrNothingHiddenKeepsAll() {
        assertEquals(apps, HiddenApps.filter(apps, setOf("bank|0"), show = true) { it })
        assertEquals(apps, HiddenApps.filter(apps, emptySet(), show = false) { it })
    }
}
