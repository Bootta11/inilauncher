package app.olauncher.extras.daynight

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.wallpaper.DailyWallpapers
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.getTodaysWallpaper
import app.olauncher.helper.getWallpaperBitmap
import app.olauncher.helper.setWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The day and night wallpapers. Your own photo for a phase is saved as a screen-sized copy in
 * the app's files, so it keeps working after the original is deleted. A phase without a photo
 * uses upstream's daily wallpaper (light or dark set) when that's on, otherwise it's left alone.
 */
object DayNightWallpapers {

    private fun file(context: Context, phase: Phase) =
        File(File(context.filesDir, "daynight"), if (phase == Phase.DAY) "day.jpg" else "night.jpg")

    fun hasPhoto(context: Context, phase: Phase) = file(context, phase).exists()

    fun removePhoto(context: Context, phase: Phase) {
        file(context, phase).delete()
    }

    /** Copies the picked image at screen size. False if it couldn't be read. */
    suspend fun savePhoto(context: Context, phase: Phase, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val (width, height) = getScreenDimensions(context)
            val original = DailyWallpapers.decodeUriScaled(context, uri, width, height) ?: return@withContext false
            val scaled = getWallpaperBitmap(original, width, height)
            val target = file(context, phase)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            original.recycle()
            scaled.recycle()
            temp.renameTo(target)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    /** Upstream's daily wallpaper for [phase], downloaded ahead of the switch. */
    private fun dailyFile(context: Context, phase: Phase) =
        File(File(context.filesDir, "daynight"), if (phase == Phase.DAY) "daily_day.jpg" else "daily_night.jpg")

    private fun dailyUrlKey(phase: Phase) =
        if (phase == Phase.DAY) ExtrasKeys.DAYNIGHT_DAILY_DAY_URL else ExtrasKeys.DAYNIGHT_DAILY_NIGHT_URL

    private fun wallType(phase: Phase) = if (phase == Phase.DAY) Constants.WALL_TYPE_LIGHT else Constants.WALL_TYPE_DARK

    /**
     * Sets the wallpaper for [phase] on the home and lock screen: your photo, else the daily
     * wallpaper downloaded ahead, else a live download. True also when there's nothing to set
     * (no photo, daily wallpaper off). False if it couldn't, e.g. offline with nothing downloaded.
     */
    suspend fun apply(context: Context, phase: Phase): Boolean = withContext(Dispatchers.IO) {
        val photo = file(context, phase)
        if (photo.exists()) return@withContext setPhoto(context, photo)
        val prefs = Prefs(context)
        if (!prefs.dailyWallpaper) return@withContext true
        val ready = dailyFile(context, phase)
        if (ready.exists() && setPhoto(context, ready)) {
            // Same URL as upstream's worker would pick, so it doesn't download it again
            ExtrasPrefs(context).string(dailyUrlKey(phase))?.let { prefs.dailyWallpaperUrl = it }
            return@withContext true
        }
        val url = getTodaysWallpaper(context, wallType(phase), prefs.firstOpenTime)
        setWallpaper(context, url).also { if (it) prefs.dailyWallpaperUrl = url }
    }

    /** Forgets both prefetched daily wallpapers, e.g. when their source changed. */
    fun clearDaily(context: Context) {
        val extras = ExtrasPrefs(context)
        for (phase in Phase.entries) {
            dailyFile(context, phase).delete()
            extras.setString(dailyUrlKey(phase), null)
        }
    }

    /** Fetches [phase]'s daily wallpaper (screen-sized, from the list or a folder) so its switch works offline. Failures are ignored. */
    suspend fun prefetchDaily(context: Context, phase: Phase, onlyIfMissing: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (!prefs.dailyWallpaper || file(context, phase).exists()) return@withContext
        if (onlyIfMissing && dailyFile(context, phase).exists()) return@withContext
        try {
            val url = getTodaysWallpaper(context, wallType(phase), prefs.firstOpenTime)
            val extras = ExtrasPrefs(context)
            val target = dailyFile(context, phase)
            if (target.exists() && extras.string(dailyUrlKey(phase)) == url) return@withContext
            val (width, height) = getScreenDimensions(context)
            val original = DailyWallpapers.loadBitmap(context, url, width, height) ?: return@withContext
            val scaled = getWallpaperBitmap(original, width, height)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            original.recycle()
            scaled.recycle()
            if (temp.renameTo(target)) extras.setString(dailyUrlKey(phase), url)
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            // Next switch downloads it live instead
        }
    }

    private fun setPhoto(context: Context, photo: File): Boolean {
        val bitmap = try {
            BitmapFactory.decodeFile(photo.path)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            val manager = WallpaperManager.getInstance(context)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            bitmap.recycle()
        }
    }
}
