package app.olauncher.extras.wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.daynight.DayNight
import app.olauncher.extras.daynight.DayNightWallpapers
import app.olauncher.extras.net.Http
import app.olauncher.helper.WallpaperWorker
import app.olauncher.helper.getBitmapFromURL
import app.olauncher.helper.openUrl
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Where upstream's daily wallpaper comes from: Inilauncher's own list of light/dark Unsplash pairs
 * (a gist, see docs/wallpapers.md), or the user's own light and dark photo folders.
 *
 * A wallpaper is still one string, as upstream keeps it in `Prefs.dailyWallpaperUrl`: an https
 * image URL from the list, or a `content://` photo from a folder.
 */
object DailyWallpapers {

    /** The list (`wallpapers/daily_wallpapers.json` in the repo). No revision in the link: always the latest. */
    const val LIST_URL =
        "https://gist.githubusercontent.com/Bootta11/00e920ad8a4dfd86d0dd165a9b4b924d/raw/daily_wallpapers.json"

    const val SOURCE_LIST = "list"
    const val SOURCE_FOLDERS = "folders"

    /** Used when there's no list yet (first run offline): the list's first pair. */
    private val FALLBACK = WallpaperPair(
        ListPhoto(
            "https://images.unsplash.com/photo-1646301951440-20a381a16d98?w=1440&q=85&fit=max",
            "https://unsplash.com/photos/-T3urjuCyMg", "@maxvdo",
        ),
        ListPhoto(
            "https://images.unsplash.com/photo-1628498188904-036f5e25e93e?w=1440&q=85&fit=max",
            "https://unsplash.com/photos/Cq5NaI0yKBE", "@olenkasergienko",
        ),
    )

    private const val LIST_FILE = "daily_wallpapers.json"
    private const val LIST_MAX_BYTES = 1024 * 1024
    private const val LIST_REFRESH_MS = 20 * 60 * 60_000L

    fun usesFolders(context: Context) =
        ExtrasPrefs(context).string(ExtrasKeys.DAILY_WALLPAPER_SOURCE) == SOURCE_FOLDERS

    /** Today in the phone's time zone, as days since 1970: the same pair all day. */
    fun today(nowMs: Long = System.currentTimeMillis()): Long =
        TimeUnit.MILLISECONDS.toDays(nowMs + TimeZone.getDefault().getOffset(nowMs))

    private fun isDark(wallType: String) = wallType == Constants.WALL_TYPE_DARK

    /** Today's wallpaper for [wallType] (light or dark): a folder photo, else a list photo. */
    suspend fun todays(context: Context, wallType: String): String = withContext(Dispatchers.IO) {
        val dark = isDark(wallType)
        if (usesFolders(context)) WallpaperFolders.todaysPhoto(context, dark, today())?.let { return@withContext it.toString() }
        val pair = list(context)?.let { WallpaperList.pick(it, today()) } ?: FALLBACK
        WallpaperList.photoFor(pair, dark).url
    }

    /** Used when the list can't be read at all. */
    fun fallback(wallType: String): String = WallpaperList.photoFor(FALLBACK, isDark(wallType)).url

    /** The list: refreshed from the gist about once a day, else the last good copy. Null if there's none yet. */
    private fun list(context: Context): List<WallpaperPair>? {
        val file = File(context.filesDir, LIST_FILE)
        val extras = ExtrasPrefs(context)
        val now = System.currentTimeMillis()
        val fetched = extras.string(ExtrasKeys.DAILY_WALLPAPER_LIST_FETCHED)?.toLongOrNull() ?: 0L
        if (!file.exists() || now - fetched !in 0..LIST_REFRESH_MS) {
            val body = Http.getText(LIST_URL, 15_000, LIST_MAX_BYTES)
            if (body != null && WallpaperList.parse(body) != null) {
                val temp = File(file.parentFile, "$LIST_FILE.tmp")
                try {
                    temp.writeText(body)
                    if (temp.renameTo(file)) extras.setString(ExtrasKeys.DAILY_WALLPAPER_LIST_FETCHED, now.toString())
                } catch (e: Exception) {
                    temp.delete()
                }
            }
        }
        return try {
            if (file.exists()) WallpaperList.parse(file.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    /** The picture behind [ref] at about screen size: from a folder, or downloaded. */
    suspend fun loadBitmap(context: Context, ref: String, width: Int, height: Int): Bitmap? =
        if (ref.startsWith("content://")) decodeUriScaled(context, Uri.parse(ref), width, height)
        else getBitmapFromURL(ref, width, height)

    /** Decodes [uri] no smaller than [width]×[height] (or its own size), so big photos don't fill the memory. */
    suspend fun decodeUriScaled(context: Context, uri: Uri, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Settings' "daily wallpaper" link: the photo's Unsplash page (credit), or the folder photo itself. */
    fun openCurrent(context: Context) {
        val ref = Prefs(context).dailyWallpaperUrl
        if (ref.isEmpty()) return
        try {
            if (ref.startsWith("content://")) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ref), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                val page = cachedPairs(context)
                    ?.flatMap { listOf(it.light, it.dark) }
                    ?.firstOrNull { it.url == ref && it.page.startsWith("https://") }?.page
                context.openUrl(page ?: ref)
            }
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.daily_wallpaper_cant_open))
        }
    }

    private fun cachedPairs(context: Context): List<WallpaperPair>? = try {
        File(context.filesDir, LIST_FILE).takeIf { it.exists() }?.readText()?.let(WallpaperList::parse)
    } catch (e: Exception) {
        null
    } ?: listOf(FALLBACK)

    /** Upstream's wallpaper worker (every 4 h). Folders don't need a network; the list does. */
    fun scheduleWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (usesFolders(context)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WallpaperWorker>(4, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(Constants.WALLPAPER_WORKER_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    fun setSource(context: Context, folders: Boolean) {
        ExtrasPrefs(context).setString(ExtrasKeys.DAILY_WALLPAPER_SOURCE, if (folders) SOURCE_FOLDERS else SOURCE_LIST)
        refreshNow(context)
    }

    /** After the source or a folder changed: show today's wallpaper from the new place now. */
    fun refreshNow(context: Context) {
        val app = context.applicationContext
        DayNightWallpapers.clearDaily(app)
        val prefs = Prefs(app)
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaperUrl = ""
        scheduleWorker(app)
        if (DayNight.isEnabled(app)) DayNight.syncInBackground(app, force = true)
    }
}
