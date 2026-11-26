package app.olauncher.extras

import app.olauncher.extras.wallpaper.WallpaperList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DailyWallpapersTest {

    private fun photo(n: Int) = """{"url":"https://images.example/$n.jpg","page":"https://unsplash.com/photos/$n","by":"@p$n"}"""

    @Test
    fun parsesPairs() {
        val pairs = WallpaperList.parse("""{"version":1,"pairs":[{"light":${photo(1)},"dark":${photo(2)}}]}""")!!
        assertEquals(1, pairs.size)
        assertEquals("https://images.example/1.jpg", pairs[0].light.url)
        assertEquals("@p2", pairs[0].dark.by)
    }

    @Test
    fun skipsBrokenPairsAndRejectsUnusableLists() {
        val mixed = """{"pairs":[{"light":${photo(1)}},{"light":{"url":"http://insecure"},"dark":${photo(2)}},{"light":${photo(3)},"dark":${photo(4)}}]}"""
        assertEquals(listOf("https://images.example/3.jpg"), WallpaperList.parse(mixed)!!.map { it.light.url })
        assertNull(WallpaperList.parse("""{"pairs":[]}"""))
        assertNull(WallpaperList.parse("""{"0_0":{"light":"x"}}"""))
        assertNull(WallpaperList.parse("not json"))
    }

    @Test
    fun picksTheSamePairAllDayAndWrapsAround() {
        val pairs = WallpaperList.parse("""{"pairs":[{"light":${photo(1)},"dark":${photo(2)}},{"light":${photo(3)},"dark":${photo(4)}},{"light":${photo(5)},"dark":${photo(6)}}]}""")!!
        assertEquals(pairs[0], WallpaperList.pick(pairs, 0))
        assertEquals(pairs[2], WallpaperList.pick(pairs, 2))
        assertEquals(pairs[0], WallpaperList.pick(pairs, 3))
        assertEquals(pairs[2], WallpaperList.pick(pairs, -1))
        assertEquals(pairs[1].dark, WallpaperList.photoFor(pairs[1], dark = true))
        assertEquals(pairs[1].light, WallpaperList.photoFor(pairs[1], dark = false))
    }

    @Test
    fun picksFolderPhotosInNameOrder() {
        val photos = listOf("c.jpg", "A.jpg", "b.jpg")
        assertEquals("A.jpg", WallpaperList.pickFromFolder(photos, 0) { it })
        assertEquals("b.jpg", WallpaperList.pickFromFolder(photos, 1) { it })
        assertEquals("c.jpg", WallpaperList.pickFromFolder(photos, 5) { it })
        assertNull(WallpaperList.pickFromFolder(emptyList<String>(), 3) { it })
    }

    @Test
    fun publishedListParses() {
        // The repo copy that's published to the gist (docs/wallpapers.md)
        val file = listOf(File("../wallpapers/daily_wallpapers.json"), File("wallpapers/daily_wallpapers.json")).first { it.exists() }
        val pairs = WallpaperList.parse(file.readText())!!
        assertEquals(pairs.size, pairs.distinct().size)
    }
}
