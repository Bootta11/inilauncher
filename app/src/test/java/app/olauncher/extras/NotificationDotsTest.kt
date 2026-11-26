package app.olauncher.extras

import app.olauncher.extras.notifications.NotificationDots
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDotsTest {

    @Test
    fun formatDot() {
        assertEquals("", NotificationDots.format(0, NotificationDots.STYLE_DOT))
        assertEquals(" •", NotificationDots.format(1, NotificationDots.STYLE_DOT))
        assertEquals(" •", NotificationDots.format(25, NotificationDots.STYLE_DOT))
    }

    @Test
    fun formatCount() {
        assertEquals("", NotificationDots.format(0, NotificationDots.STYLE_COUNT))
        assertEquals(" •3", NotificationDots.format(3, NotificationDots.STYLE_COUNT))
        assertEquals(" •9", NotificationDots.format(9, NotificationDots.STYLE_COUNT))
        assertEquals(" •9+", NotificationDots.format(10, NotificationDots.STYLE_COUNT))
    }

    @Test
    fun badgeText() {
        assertEquals("", NotificationDots.badgeText(0, NotificationDots.STYLE_COUNT))
        assertEquals("", NotificationDots.badgeText(4, NotificationDots.STYLE_DOT))
        assertEquals("4", NotificationDots.badgeText(4, NotificationDots.STYLE_COUNT))
        assertEquals("9+", NotificationDots.badgeText(12, NotificationDots.STYLE_COUNT))
    }

    @Test
    fun stripRemovesAnyEarlierSuffix() {
        assertEquals("Messages", NotificationDots.strip("Messages"))
        assertEquals("Messages", NotificationDots.strip("Messages •"))
        assertEquals("Messages", NotificationDots.strip("Messages •3"))
        assertEquals("Messages", NotificationDots.strip("Messages •9+"))
        assertEquals("Mail • Work", NotificationDots.strip("Mail • Work"))
    }
}
