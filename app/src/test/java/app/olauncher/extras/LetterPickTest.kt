package app.olauncher.extras

import app.olauncher.extras.search.LetterPick
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterPickTest {

    private val labels = listOf("Tools ›", null, "Maps", "Obsidian", "Olauncher", "one sec", "OnePlus Store", "Paprika", "1Password", "Ébène", "")

    @Test
    fun letterRowsFromTheTopDown() {
        assertEquals(listOf(3, 4, 5, 6), LetterPick.rows(labels, 'O'))
    }

    @Test
    fun symbolsAndEmptyLetters() {
        assertEquals(listOf(8), LetterPick.rows(labels, '#'))
        assertEquals(emptyList<Int>(), LetterPick.rows(labels, 'Z'))
    }

    @Test
    fun otherAlphabetsAndEmptyLabelsBelongNowhere() {
        assertEquals(null, LetterPick.letterOf("Ébène"))
        assertEquals(null, LetterPick.letterOf(""))
        assertEquals(null, LetterPick.letterOf(null))
        assertEquals('T', LetterPick.letterOf("tools"))
    }
}
