package app.olauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZealotClientTest {

    private val zealot = "https://apps.example.com"

    @Test
    fun apkOnOurZealotIsAccepted() {
        assertTrue(ZealotClient.sameHost("https://apps.example.com/download/releases/12", zealot))
        assertTrue(ZealotClient.sameHost("https://APPS.example.com/x.apk", zealot))
    }

    @Test
    fun otherHostsAndPlainHttpAreRefused() {
        assertFalse(ZealotClient.sameHost("https://evil.example.net/x.apk", zealot))
        assertFalse(ZealotClient.sameHost("https://apps.example.com.evil.net/x.apk", zealot))
        assertFalse(ZealotClient.sameHost("http://apps.example.com/x.apk", zealot))
        assertFalse(ZealotClient.sameHost("https://user@evil.net/apps.example.com", zealot))
        assertFalse(ZealotClient.sameHost("not a url", zealot))
        assertFalse(ZealotClient.sameHost("", zealot))
    }
}
