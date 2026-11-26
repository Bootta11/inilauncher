package app.olauncher.extras

import app.olauncher.extras.iconpack.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconsMonogramTest {

    @Test
    fun wordInitials() {
        assertEquals("GM", AppIcons.monogram("Google Maps"))
        assertEquals("2A", AppIcons.monogram("2FAS Auth"))
        assertEquals("SA", AppIcons.monogram("“Sky” app"))
    }

    @Test
    fun camelCaseCountsAsWords() {
        assertEquals("WA", AppIcons.monogram("WhatsApp"))
        assertEquals("YT", AppIcons.monogram("YouTube"))
        assertEquals("KR", AppIcons.monogram("KOReader"))
        assertEquals("ID", AppIcons.monogram("iDenfy"))
    }

    @Test
    fun oneWordTakesNextSyllable() {
        assertEquals("VB", AppIcons.monogram("Viber"))
        assertEquals("BS", AppIcons.monogram("Browser"))
        assertEquals("TL", AppIcons.monogram("Telegram"))
        assertEquals("CM", AppIcons.monogram("Chrome"))
        assertEquals("ST", AppIcons.monogram("Spotify"))
        assertEquals("UB", AppIcons.monogram("Uber"))
    }

    @Test
    fun ignoresSuffixMarkers() {
        assertEquals("VB", AppIcons.monogram("Viber •"))
        assertEquals("TL", AppIcons.monogram("Tools ›"))
    }

    @Test
    fun fallbacks() {
        assertEquals("X", AppIcons.monogram("X"))
        assertEquals("ZZ", AppIcons.monogram("Zzz"))
        assertEquals("ТЕ", AppIcons.monogram("Телеграм"))
        assertEquals("#", AppIcons.monogram(""))
        assertEquals("#", AppIcons.monogram("✦ ›"))
    }
}
