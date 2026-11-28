package app.olauncher.extras

import app.olauncher.extras.config.ConfigBundle
import app.olauncher.extras.config.HomeEntry
import app.olauncher.extras.config.JsonGuard
import app.olauncher.extras.config.SchemaError
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.showcase.Showcase
import app.olauncher.extras.showcase.ShowcaseChoice
import app.olauncher.extras.showcase.ShowcaseChoice.Role
import app.olauncher.extras.showcase.ShowcaseSetup
import app.olauncher.extras.showcase.ShowcaseWallpaper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ShowcaseTest {

    private fun app(pkg: String, label: String = pkg.substringAfterLast('.')) = FolderApp(label, pkg, "$pkg.Main", 0)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun refused(block: () -> Unit): SchemaError {
        try { block() } catch (e: SchemaError) { return e }
        fail("expected a SchemaError"); throw IllegalStateException()
    }

    // ---- The .zip bundle ----

    @Test
    fun bundleRoundTripKeepsConfigAndScreenshots() {
        val shot = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = ConfigBundle.write("{\"format\":\"inilauncher-config\"}", mapOf("home" to shot, "app-list" to shot))
        assertTrue(ConfigBundle.isZip(bytes))
        assertEquals("{\"format\":\"inilauncher-config\"}", ConfigBundle.readConfig(bytes))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "screenshots/home.webp") assertArrayEquals(shot, z.readBytes())
            }
        }
        assertEquals(listOf("config.json", "screenshots/home.webp", "screenshots/app-list.webp"), names)
    }

    @Test
    fun plainJsonIsNotAZip() {
        assertFalse(ConfigBundle.isZip("{\"format\":1}".toByteArray()))
        assertFalse(ConfigBundle.isZip(byteArrayOf('P'.code.toByte())))
    }

    @Test
    fun onlyConfigJsonIsRead() {
        // Path tricks and other files are skipped unread; nothing is ever unpacked to disk
        val bytes = zip("../evil.json" to "x".toByteArray(), "screenshots/a.webp" to byteArrayOf(9), "config.json" to "{}".toByteArray())
        assertEquals("{}", ConfigBundle.readConfig(bytes))
    }

    @Test
    fun configThatUnpacksTooBigIsRefused() {
        val huge = ByteArray(JsonGuard.MAX_BYTES + 10) { ' '.code.toByte() } // compresses to almost nothing
        val e = refused { ConfigBundle.readConfig(zip("config.json" to huge)) }
        assertEquals("config.json", e.path)
    }

    @Test
    fun zipWithoutConfigOrBrokenIsRefused() {
        assertEquals("file", refused { ConfigBundle.readConfig(zip("other.json" to "{}".toByteArray())) }.path)
        assertEquals("file", refused { ConfigBundle.readConfig(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)) }.path)
    }

    @Test
    fun tooManyEntriesAreRefused() {
        val entries = (0 until 70).map { "f$it" to byteArrayOf(1) }.toTypedArray()
        refused { ConfigBundle.readConfig(zip(*entries)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun screenshotNamesAreChecked() {
        ConfigBundle.write("{}", mapOf("../home" to byteArrayOf(1)))
    }

    // ---- The stand-in apps ----

    @Test
    fun choiceRoundTrip() {
        val choice = ShowcaseChoice(listOf(app("a.phone")), listOf(app("a.maps")), "Work", listOf(app("a.calc")),
            listOf(app("a.calc"), app("a.phone")), ShowcaseWallpaper.ListPair("https://x/l.jpg", "https://x/d.jpg"))
        assertEquals(choice, ShowcaseChoice.decode(choice.encode()))
        for (w in listOf(ShowcaseWallpaper.Theme, ShowcaseWallpaper.Daily, ShowcaseWallpaper.Image))
            assertEquals(w, ShowcaseChoice.decode(choice.copy(wallpaper = w).encode())!!.wallpaper)
    }

    @Test
    fun badChoiceGivesNullOrSafeValues() {
        assertNull(ShowcaseChoice.decode(null))
        assertNull(ShowcaseChoice.decode("not json"))
        val odd = ShowcaseChoice.decode("""{"home":[${(1..10).joinToString(",") { "{\"package\":\"p$it\"}" }}],"folderName":"  ",
            "wallpaper":{"kind":"list","light":"http://plain","dark":"https://ok"}}""")!!
        assertEquals(ShowcaseChoice.MAX_HOME, odd.home.size)
        assertEquals(ShowcaseChoice.DEFAULT_FOLDER, odd.folderName)
        assertEquals(ShowcaseWallpaper.Daily, odd.wallpaper) // only https photos
    }

    @Test
    fun defaultsSpreadOrdinaryAppsOverTheScreens() {
        val found = mapOf(Role.PHONE to app("x.dialer", "Phone"), Role.CAMERA to app("x.camera", "Camera"),
            Role.MAPS to app("x.maps", "Maps"), Role.CALCULATOR to app("x.calc", "Calculator"), Role.SETTINGS to app("x.settings", "Settings"))
        val d = ShowcaseChoice.defaults(found)
        assertEquals(listOf("Phone", "Camera"), d.home.map { it.label })
        assertEquals(listOf("Maps"), d.sublist.map { it.label })
        assertEquals(listOf("Calculator", "Settings"), d.folder.map { it.label })
        assertEquals(listOf("Calculator", "Camera", "Maps", "Phone", "Settings"), d.drawer.map { it.label })
        assertEquals(ShowcaseWallpaper.Daily, d.wallpaper)
    }

    @Test
    fun layoutPutsTheFolderAfterTheHomeApps() {
        val choice = ShowcaseChoice(listOf(app("a.one"), app("a.two")), listOf(app("a.three")), "Tools", listOf(app("a.four")), emptyList(), ShowcaseWallpaper.Theme)
        val layout = ShowcaseSetup.layout(choice)
        assertEquals(3, layout.homeAppsCount)
        assertEquals(HomeEntry.Folder("Tools"), layout.homeApps!!.last())
        assertEquals(listOf("a.three"), layout.sublistApps!!.map { it.packageName })
        assertEquals("Tools", layout.folders!!.single().name)
        // No folder apps: no folder on home
        assertEquals(2, ShowcaseSetup.layout(choice.copy(folder = emptyList())).homeAppsCount)
    }

    @Test
    fun appListKeepsTheExactEntry() {
        assertEquals("a.dialer/a.dialer.Contacts", ShowcaseSetup.drawerKey("a.dialer", "a.dialer.Contacts"))
        assertEquals("a.dialer", ShowcaseSetup.drawerKey("a.dialer", null))
    }

    @Test
    fun showcasePrefsNamesNeverClashWithTheRealOnes() {
        assertEquals("showcase.app.olauncher", Showcase.prefsName("app.olauncher"))
        assertEquals("showcase.app.olauncher", Showcase.prefsName("showcase.app.olauncher"))
        assertFalse(Showcase.inProcess) // unit tests (and the launcher) are never the showcase
    }
}
