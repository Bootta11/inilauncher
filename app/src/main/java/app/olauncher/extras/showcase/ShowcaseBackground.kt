package app.olauncher.extras.showcase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import app.olauncher.extras.wallpaper.DailyWallpapers
import app.olauncher.extras.wallpaper.WallpaperList
import app.olauncher.helper.getBitmapFromURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** The picture behind the showcase screens (see [ShowcaseWallpaper]). The system wallpaper is never read or set. */
object ShowcaseBackground {

    private const val PICKED = "picked.jpg"
    private const val KEEP_DOWNLOADS = 6

    fun pickedFile(context: Context) = File(ShowcaseSetup.dir(context), PICKED)

    /** The photo's address for [dark] or light, or null for the theme colour and a picked image. */
    fun url(context: Context, wallpaper: ShowcaseWallpaper, dark: Boolean): String? = when (wallpaper) {
        ShowcaseWallpaper.Theme, ShowcaseWallpaper.Image -> null
        ShowcaseWallpaper.Daily -> WallpaperList.photoFor(WallpaperList.pick(DailyWallpapers.cachedPairs(context), DailyWallpapers.today()), dark).url
        is ShowcaseWallpaper.ListPair -> if (dark) wallpaper.darkUrl else wallpaper.lightUrl
    }

    /**
     * The background at about screen size, or null for the theme colour (also when a download fails
     * offline). Downloads are kept in the showcase's folder, so it works offline next time.
     */
    suspend fun load(context: Context, wallpaper: ShowcaseWallpaper, dark: Boolean, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (wallpaper == ShowcaseWallpaper.Image) return@withContext decode(pickedFile(context), width, height)
            val url = url(context, wallpaper, dark) ?: return@withContext null
            val file = File(ShowcaseSetup.dir(context), "wp-" + hash(url) + ".jpg")
            decode(file, width, height)?.let { return@withContext it }
            val bitmap = getBitmapFromURL(url, width, height) ?: return@withContext null
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            prune(context)
            bitmap
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Copies a picked image into the showcase's folder, scaled down to just cover a [width] ×
     * [height] screen (what it's drawn at), so a big photo isn't kept at full size.
     */
    fun savePicked(context: Context, bitmap: Bitmap, width: Int, height: Int): Boolean = try {
        val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat()).coerceAtMost(1f)
        val fitted = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        pickedFile(context).outputStream().use { fitted.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .also { if (fitted !== bitmap) fitted.recycle() }
    } catch (e: Exception) {
        false
    }

    private fun decode(file: File, width: Int, height: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun prune(context: Context) {
        ShowcaseSetup.dir(context).listFiles { f -> f.name.startsWith("wp-") }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP_DOWNLOADS)?.forEach { it.delete() }
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

}

/** A bitmap that fills its bounds, cropped from the centre, like a wallpaper. */
class CenterCropDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = maxOf(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
        matrix.setScale(scale, scale)
        matrix.postTranslate(b.left + (b.width() - bitmap.width * scale) / 2f, b.top + (b.height() - bitmap.height * scale) / 2f)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
