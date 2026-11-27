package app.olauncher.extras

import app.olauncher.extras.settingssearch.SettingEntry
import app.olauncher.extras.settingssearch.SettingsIndex
import app.olauncher.extras.settingssearch.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {

    private val strings = mapOf(
        1 to "Background blur", 2 to "Look", 3 to "Blurs the wallpaper behind dialogs.",
        4 to "Theme mode", 5 to "Appearance",
        6 to "Café mode", 7 to "Weather units", 8 to "Home screen", 9 to "Shows the temperature.",
        10 to "Blur strength",
    )
    private val text = { id: Int -> strings.getValue(id) }
    private val entries = listOf(
        SettingEntry(1, 2, SettingsScreen.EXTRAS, keywords = listOf(3)),
        SettingEntry(4, 5, SettingsScreen.MAIN),
        SettingEntry(6, 5, SettingsScreen.MAIN),
        SettingEntry(7, 8, SettingsScreen.EXTRAS, keywords = listOf(9)),
        SettingEntry(10, 2, SettingsScreen.EXTRAS),
    )

    private fun labels(query: String) = SettingsIndex.search(query, entries, text).map { strings.getValue(it.label) }

    @Test
    fun `label prefix ranks before a match inside the label`() {
        assertEquals(listOf("Blur strength", "Background blur"), labels("blur"))
    }

    @Test
    fun `accents and case are ignored`() {
        assertEquals(listOf("Café mode"), labels("CAFE"))
    }

    @Test
    fun `hints and sections match by word start`() {
        assertEquals(listOf("Weather units"), labels("temp"))
        assertEquals(listOf("Theme mode", "Café mode"), labels("appear"))
        assertEquals(listOf("Background blur"), labels("wallpaper dialogs"))
    }

    @Test
    fun `short queries don't look in hints`() {
        assertTrue(labels("sh").isEmpty())
    }

    @Test
    fun `blank and unmatched queries find nothing`() {
        assertTrue(labels("  ").isEmpty())
        assertTrue(labels("zzz").isEmpty())
    }

    @Test
    fun `the real index has unique labels per screen and valid fallbacks`() {
        val all = SettingsIndex.entries
        SettingsScreen.entries.forEach { screen ->
            val ls = all.filter { it.screen == screen }.map { it.label }
            assertEquals("duplicate label on $screen", ls.size, ls.toSet().size)
        }
        val extras = all.filter { it.screen == SettingsScreen.EXTRAS }
        extras.filter { it.fallback != 0 }.forEach { e ->
            val parent = extras.firstOrNull { it.label == e.fallback }
            assertTrue("fallback missing for ${e.label}", parent != null && parent.fallback == 0)
        }
        assertTrue(all.filter { it.screen == SettingsScreen.MAIN }.all { it.viewId != 0 })
    }
}
