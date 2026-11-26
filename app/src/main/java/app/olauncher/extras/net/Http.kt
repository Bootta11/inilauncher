package app.olauncher.extras.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small GETs for the extras (weather, search suggestions, wallpapers): https only, time-limited and
 * size-capped, so a slow or broken server can't hang a feature or fill the memory. Blocking: call
 * off the main thread.
 */
object Http {

    /** The body as text, or null on any failure (not https, not 200, too big, timeout, offline). */
    fun getText(url: String, timeoutMs: Int, maxBytes: Int): String? =
        getBytes(url, timeoutMs, maxBytes)?.toString(Charsets.UTF_8)

    /** The body, or null on any failure (see [getText]). */
    fun getBytes(url: String, timeoutMs: Int, maxBytes: Int): ByteArray? {
        if (!url.startsWith("https://")) return null
        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Inilauncher")
                try {
                    if (responseCode != 200 || contentLengthLong > maxBytes) null
                    else inputStream.use { readCapped(it, maxBytes) }
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** All of [input], or an [IOException] once it passes [maxBytes]. Pure, unit tested. */
    fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw IOException("Response over $maxBytes bytes")
        }
        return out.toByteArray()
    }
}
