package app.olauncher.extras.whatsnew

import android.content.Context
import android.os.Build
import android.util.Log
import app.olauncher.BuildConfig
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import org.json.JSONArray

/**
 * "What's new" notes bundled in the APK (assets/whatsnew/whatsnew.json, newest first, screenshots in
 * assets/whatsnew/<version>/). Shown once after an update that has notes; Settings → What's new reopens
 * them. Only the newest [MAX_VERSIONS] versions keep screenshots, so the APK doesn't grow per release
 * (docs/app-updates.md → What's new).
 */
object WhatsNew {

    /** Versions joined into one dialog, and versions that may keep screenshots in the APK. */
    const val MAX_VERSIONS = 3
    const val ASSET_DIR = "whatsnew"
    private const val FILE = "$ASSET_DIR/whatsnew.json"

    /** [image] is the dark-theme screenshot; [imageLight] the light-theme one (falls back to [image]). */
    data class Feature(val title: String, val text: String, val image: String?, val imageLight: String? = null)
    data class Release(val version: String, val versionCode: Long, val intro: String, val features: List<Feature>) {
        /** Asset path of a feature's screenshot for the current theme. */
        fun imagePath(f: Feature, light: Boolean = false) = ((if (light) f.imageLight else null) ?: f.image)?.let { "$ASSET_DIR/$version/$it" }

        /** Every screenshot file this release uses, both themes. */
        fun imagePaths() = features.flatMap { f -> listOfNotNull(f.image, f.imageLight) }.map { "$ASSET_DIR/$version/$it" }
    }

    /** CI's rule (vX.Y.Z → X*10000 + Y*100 + Z); 0 for anything else. */
    fun versionCode(version: String): Long {
        val parts = version.trim().removePrefix("v").split(".")
        if (parts.size != 3) return 0
        val n = parts.map { it.toLongOrNull() ?: return 0 }
        return n[0] * 10000 + n[1] * 100 + n[2]
    }

    /** Releases from the JSON, newest first; broken entries are skipped. */
    fun parse(json: String): List<Release> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val version = o.optString("version")
            val code = versionCode(version)
            if (code == 0L) return@mapNotNull null
            val list = o.optJSONArray("features") ?: JSONArray()
            val features = (0 until list.length()).mapNotNull { j ->
                val f = list.optJSONObject(j) ?: return@mapNotNull null
                val title = f.optString("title")
                if (title.isBlank()) null
                else Feature(title, f.optString("text"), f.optString("image").takeIf { it.isNotBlank() },
                    f.optString("imageLight").takeIf { it.isNotBlank() })
            }
            Release(version, code, o.optString("intro"), features)
        }.sortedByDescending { it.versionCode }
    }.getOrElse { emptyList() }

    /** Notes the user hasn't seen: newer than [seen], up to [current], newest first, at most [MAX_VERSIONS]. */
    fun notesSince(all: List<Release>, seen: Long, current: Long): List<Release> =
        all.filter { it.versionCode in (seen + 1)..current }.take(MAX_VERSIONS)

    /** The newest notes up to this build, for Settings → What's new. */
    fun latest(all: List<Release>, current: Long): List<Release> =
        all.filter { it.versionCode <= current }.take(MAX_VERSIONS)

    fun load(context: Context): List<Release> = runCatching {
        context.assets.open(FILE).bufferedReader().use { parse(it.readText()) }
    }.getOrElse { Log.w("WhatsNew", "no notes", it); emptyList() }

    /**
     * What to show on this start, once: notes since the version seen last. A fresh install sees
     * nothing and just remembers this version. Marks them seen right away, so a crash or a quick
     * Home press never shows them twice.
     */
    fun takeUnseen(context: Context): List<Release> {
        val prefs = ExtrasPrefs(context)
        val current = BuildConfig.VERSION_CODE.toLong()
        val stored = prefs.string(ExtrasKeys.WHATS_NEW_SEEN)?.toLongOrNull()
        if (stored != null && stored >= current) return emptyList()
        prefs.setString(ExtrasKeys.WHATS_NEW_SEEN, current.toString())
        // No key yet: a fresh install (nothing to show), or an update from a build before this feature
        val seen = stored ?: if (isFreshInstall(context)) return emptyList() else current - 1
        return notesSince(load(context), seen, current)
    }

    private fun isFreshInstall(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(true)
}
