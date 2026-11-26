package app.olauncher.extras

import app.olauncher.extras.net.Http
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class HttpTest {

    @Test
    fun readsUpToTheCap() {
        val body = ByteArray(10_000) { it.toByte() }
        assertArrayEquals(body, Http.readCapped(ByteArrayInputStream(body), 10_000))
    }

    @Test(expected = IOException::class)
    fun refusesMoreThanTheCap() {
        Http.readCapped(ByteArrayInputStream(ByteArray(10_001)), 10_000)
    }

    @Test
    fun onlyHttps() {
        assertNull(Http.getText("http://example.com", 1000, 1000))
        assertNull(Http.getText("file:///etc/hosts", 1000, 1000))
    }
}
