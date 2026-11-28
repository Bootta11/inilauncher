package app.olauncher.extras.config

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A configuration with screenshots: one .zip holding `config.json` and `screenshots/<name>.webp`.
 * Import reads only `config.json`, at most [JsonGuard.MAX_BYTES], and never unpacks anything else.
 * Pure, so it's unit tested.
 */
object ConfigBundle {

    const val CONFIG = "config.json"
    const val SHOTS = "screenshots/"
    /** A whole bundle can't be bigger than this (four screenshots are well under 2 MB). */
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRIES = 64
    private val SHOT_NAME = Regex("^[a-z0-9-]{1,40}$")

    /** True when [bytes] start like a zip file. */
    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** The bundle's bytes: the configuration first, then each screenshot (name without extension → WebP bytes). */
    fun write(config: String, shots: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(CONFIG))
            zip.write(config.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, data) in shots) {
                require(SHOT_NAME.matches(name)) { "bad screenshot name" }
                // WebP is already compressed: store it as it is
                val entry = ZipEntry("$SHOTS$name.webp").apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The configuration text inside a bundle. Throws [SchemaError] when there's none, when it's
     * larger than [JsonGuard.MAX_BYTES] once unpacked, or when the zip can't be read. Other entries
     * (screenshots, anything else) are skipped unread.
     */
    fun readConfig(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw SchemaError("file", "is larger than ${MAX_BYTES / 1024 / 1024} MB")
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw SchemaError("file", "has too many files inside")
                    if (entry.isDirectory || entry.name != CONFIG) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        // Counted while unpacking, so a tiny file that unpacks huge is stopped early
                        if (out.size() > JsonGuard.MAX_BYTES) throw SchemaError(CONFIG, "is larger than ${JsonGuard.MAX_BYTES / 1024} KB")
                    }
                    return String(out.toByteArray(), Charsets.UTF_8)
                }
            }
        } catch (e: SchemaError) {
            throw e
        } catch (e: Exception) {
            throw SchemaError("file", "isn't a readable zip file")
        }
        throw SchemaError("file", "has no $CONFIG inside")
    }
}
