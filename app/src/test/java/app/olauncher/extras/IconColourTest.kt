package app.olauncher.extras

import app.olauncher.extras.iconpack.IconColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconColourTest {

    private val white = 0xFFFFFFFF.toInt()
    private val purple = 0xFF7360F2.toInt()
    private val clear = 0x00FF0000

    @Test
    fun logoBeatsWhiteBackground() {
        val pixels = IntArray(100) { if (it < 20) purple else white }
        assertEquals(purple, IconColour.dominant(pixels))
    }

    @Test
    fun transparentPixelsIgnored() {
        val pixels = IntArray(100) { if (it < 10) purple else clear }
        assertEquals(purple, IconColour.dominant(pixels))
    }

    @Test
    fun allGreyAverages() {
        val pixels = IntArray(10) { if (it < 5) white else 0xFF000000.toInt() }
        assertEquals(0xFF7F7F7F.toInt(), IconColour.dominant(pixels))
    }

    @Test
    fun multiColourPicksARealColourNotTheAverage() {
        val red = 0xFFDB4437.toInt(); val green = 0xFF0F9D58.toInt(); val yellow = 0xFFF4B400.toInt()
        val pixels = IntArray(90) { when (it % 9) { in 0..3 -> red; in 4..6 -> green; else -> yellow } }
        assertEquals(red, IconColour.dominant(pixels))
    }

    @Test
    fun readableLiftsDarkColoursOnLightText() {
        val navy = 0xFF1A1F3A.toInt()
        val lifted = IconColour.readable(navy, white)
        // At least 4.5:1 against black
        assert((IconColour.luminance(lifted) + 0.05) / 0.05 >= 4.5)
    }

    @Test
    fun readableKeepsBrightColours() {
        val bright = 0xFF8C7CFF.toInt()
        assertEquals(bright, IconColour.readable(bright, white))
    }

    @Test
    fun readableDarkensPaleColoursOnDarkText() {
        val pale = 0xFFFFF59D.toInt()
        val dark = 0xFF202020.toInt()
        assert(IconColour.luminance(IconColour.readable(pale, dark)) < IconColour.luminance(pale))
    }

    @Test
    fun emptyIsNull() {
        assertNull(IconColour.dominant(IntArray(4) { clear }))
        assertNull(IconColour.dominant(IntArray(0)))
    }
}
