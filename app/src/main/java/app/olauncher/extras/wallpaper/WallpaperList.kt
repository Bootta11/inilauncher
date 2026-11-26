package app.olauncher.extras.wallpaper

import org.json.JSONObject

/** One photo of the daily list: the image, its Unsplash page and the photographer (credit). */
data class ListPhoto(val url: String, val page: String, val by: String)

data class WallpaperPair(val light: ListPhoto, val dark: ListPhoto)

/**
 * The daily wallpaper list (`wallpapers/daily_wallpapers.json`, published as a gist, see
 * docs/wallpapers.md). Pure, unit tested.
 */
object WallpaperList {

    /** The pairs, or null when [json] isn't a usable list (bad JSON, no pairs, a pair without an https image). */
    fun parse(json: String): List<WallpaperPair>? = try {
        val array = JSONObject(json).getJSONArray("pairs")
        val pairs = (0 until array.length()).mapNotNull { i ->
            val pair = array.optJSONObject(i) ?: return@mapNotNull null
            val light = photo(pair.optJSONObject("light"))
            val dark = photo(pair.optJSONObject("dark"))
            if (light != null && dark != null) WallpaperPair(light, dark) else null
        }
        pairs.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun photo(json: JSONObject?): ListPhoto? {
        val url = json?.optString("url").orEmpty()
        if (!url.startsWith("https://")) return null
        return ListPhoto(url, json!!.optString("page"), json.optString("by"))
    }

    /** Today's pair: the same for everyone on the same day, and the list may be any length. */
    fun pick(pairs: List<WallpaperPair>, epochDay: Long): WallpaperPair =
        pairs[Math.floorMod(epochDay, pairs.size.toLong()).toInt()]

    /** Today's item of a folder's photos, sorted by name so the order stays the same. */
    fun <T> pickFromFolder(photos: List<T>, epochDay: Long, name: (T) -> String): T? {
        if (photos.isEmpty()) return null
        val sorted = photos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))
        return sorted[Math.floorMod(epochDay, sorted.size.toLong()).toInt()]
    }

    /** The photo for [dark] (dark theme or night) from today's [pair]. */
    fun photoFor(pair: WallpaperPair, dark: Boolean) = if (dark) pair.dark else pair.light
}
