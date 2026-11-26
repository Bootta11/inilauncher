package app.olauncher.extras

import app.olauncher.extras.iconpack.AppFilterParser.componentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppFilterParserTest {

    @Test
    fun componentKeys() {
        assertEquals("com.app/com.app.Main", componentKey("ComponentInfo{com.app/com.app.Main}"))
        assertEquals("com.app/com.app.Main", componentKey("ComponentInfo{com.app/.Main}"))
        assertNull(componentKey("ComponentInfo{}"))
        assertNull(componentKey("ComponentInfo{com.app}"))
        assertNull(componentKey("ComponentInfo{com.app/}"))
        assertNull(componentKey(null))
    }
}

class AppFilterByPackageTest {
    @Test
    fun onePerPackage() {
        val map = mapOf("a.b/a.b.Main" to "ab", "c.d/c.d.One" to "cd", "a.b/a.b.Other" to "ab2")
        val byPackage = app.olauncher.extras.iconpack.AppFilterParser.byPackage(map)
        assertEquals(setOf("a.b", "c.d"), byPackage.keys)
        assertEquals("cd", byPackage["c.d"])
        assertEquals(true, byPackage["a.b"] in setOf("ab", "ab2"))
    }
}

class AlphabetIndexTest {
    private val labels = listOf("Tools ›", "Adblock", "Bank", "Camera", "Zoom", "1Password")

    @org.junit.Test
    fun jumps() {
        val indexFor = app.olauncher.extras.search.AlphabetScrollerView.Companion::indexFor
        assertEquals(2, indexFor(labels, 'B'))
        assertEquals(4, indexFor(labels, 'Y')) // no Y → next letter that exists
        assertEquals(5, indexFor(labels, '#'))
        assertEquals(-1, indexFor(listOf("Alpha"), 'Z'))
    }

    @org.junit.Test
    fun tableSkipsPinnedEntriesAndHandlesOtherScripts() {
        val build = app.olauncher.extras.search.LetterIndex::build
        // null = folder / Widgets entry pinned on top: T and W must not jump to it
        val result = build(listOf(null, null, "Adblock", "Tasks", "Viber", "Ćevapi", "2FAS"))
        val idx = { c: Char -> app.olauncher.extras.search.LetterIndex.LETTERS.indexOf(c) }
        val at = { c: Char -> result.jump[idx(c)] }
        assertEquals(3, at('T'))
        assertEquals(5, at('W')) // nothing from W to Z → the first label in another script
        assertEquals(2, at('A'))
        assertEquals(3, at('B')) // next letter with apps
        assertEquals(6, at('#'))
        assertEquals(-1, build(listOf(null, "Zed")).jump[idx('#')])
        // Letters with apps of their own (for dimming the rest)
        assertEquals(true, result.present[idx('A')])
        assertEquals(false, result.present[idx('B')])
        assertEquals(true, result.present[idx('T')])
        assertEquals(false, result.present[idx('W')])
        assertEquals(true, result.present[idx('#')])
    }
}
