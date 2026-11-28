package app.olauncher.extras

import app.olauncher.extras.whatsnew.WhatsNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewTest {

    private fun entry(v: String) = """{"version":"$v","intro":"i$v","features":[{"title":"t$v","text":"x","image":"a.webp"}]}"""
    private val all = WhatsNew.parse("[${entry("0.1.6")},${entry("0.2.0")},${entry("0.1.8")},${entry("0.1.7")}]")

    @Test
    fun versionCodesFollowCi() {
        assertEquals(106L, WhatsNew.versionCode("0.1.6"))
        assertEquals(10203L, WhatsNew.versionCode("v1.2.3"))
        assertEquals(0L, WhatsNew.versionCode("1.2"))
        assertEquals(0L, WhatsNew.versionCode("next"))
    }

    @Test
    fun parsesNewestFirstAndSkipsBrokenEntries() {
        assertEquals(listOf("0.2.0", "0.1.8", "0.1.7", "0.1.6"), all.map { it.version })
        val mixed = WhatsNew.parse("""[{"version":"soon"},{"version":"0.1.6","features":[{"title":""},{"title":"ok"}]}]""")
        assertEquals(1, mixed.size)
        assertEquals(listOf("ok"), mixed[0].features.map { it.title })
        assertEquals(null, mixed[0].features[0].image)
        val themed = WhatsNew.parse("""[{"version":"0.1.6","features":[{"title":"a","image":"a.webp","imageLight":"a-light.webp"},{"title":"b","image":"b.webp"}]}]""")[0]
        assertEquals("whatsnew/0.1.6/a-light.webp", themed.imagePath(themed.features[0], light = true))
        assertEquals("whatsnew/0.1.6/a.webp", themed.imagePath(themed.features[0], light = false))
        assertEquals("whatsnew/0.1.6/b.webp", themed.imagePath(themed.features[1], light = true)) // no light picture: the dark one
        assertTrue(WhatsNew.parse("not json").isEmpty())
    }

    @Test
    fun unseenNotes() {
        fun since(seen: Long, current: Long) = WhatsNew.notesSince(all, seen, current).map { it.version }
        assertEquals(listOf("0.1.7"), since(106, 107)) // one update
        assertEquals(listOf("0.2.0", "0.1.8", "0.1.7"), since(105, 200)) // skipped several: newest 3
        assertEquals(emptyList<String>(), since(107, 107)) // restart
        assertEquals(emptyList<String>(), since(200, 106)) // downgrade
        assertEquals(listOf("0.1.6"), since(105, 106)) // an update to a version with notes
        assertEquals(listOf("0.1.8", "0.1.7", "0.1.6"), WhatsNew.latest(all, 108).map { it.version })
    }

    private val assets = listOf(File("src/main/assets/whatsnew"), File("app/src/main/assets/whatsnew")).first { it.exists() }

    @Test
    fun bundledNotesAreValid() {
        val text = File(assets, "whatsnew.json").readText()
        val releases = WhatsNew.parse(text)
        assertEquals("every entry in whatsnew.json must parse", org.json.JSONArray(text).length(), releases.size)
        assertEquals("versions must be unique", releases.size, releases.map { it.versionCode }.toSet().size)
        assertEquals("whatsnew.json must list the newest version first", releases.map { it.version },
            org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("version") } })
        releases.forEach { r ->
            assertTrue("${r.version} needs at least one feature", r.features.isNotEmpty())
            r.imagePaths().forEach { assertTrue("missing $it", File(assets.parentFile, it).isFile) }
        }
    }

    /** Screenshots must not pile up release after release: only the newest versions keep them. */
    @Test
    fun screenshotsStayWithinBudget() {
        val releases = WhatsNew.parse(File(assets, "whatsnew.json").readText())
        val withImages = releases.filter { r -> r.imagePaths().isNotEmpty() }
        assertTrue("only the ${WhatsNew.MAX_VERSIONS} newest versions may keep screenshots; drop the oldest's",
            withImages.size <= WhatsNew.MAX_VERSIONS && withImages.all { it in releases.take(WhatsNew.MAX_VERSIONS) })
        val referenced = releases.flatMap { it.imagePaths() }.toSet()
        val files = assets.walk().filter { it.isFile && it.name != "whatsnew.json" }.toList()
        files.forEach { f ->
            val path = "whatsnew/" + f.relativeTo(assets).invariantSeparatorsPath
            assertTrue("$path isn't used in whatsnew.json: delete it", path in referenced)
        }
        val kb = files.sumOf { it.length() } / 1024
        assertTrue("whatsnew screenshots take $kb KB, budget 800 KB", kb <= 800)
    }
}
