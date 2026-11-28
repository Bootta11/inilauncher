package app.olauncher.extras.showcase

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import app.olauncher.extras.HomeButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Screenshots of the showcase's own windows: the activity, plus any dialog on top (a folder),
 * drawn where it sits with its dim. The system bars are other windows, so they never show.
 * Saved as WebP, at most [MAX_WIDTH] wide, in [dir].
 */
class ShowcaseCapture(private val activity: Activity) {

    val names = mutableListOf<String>()

    fun clear() {
        dir(activity).listFiles()?.forEach { it.delete() }
        names.clear()
    }

    suspend fun take(name: String) {
        val root = activity.window.decorView
        if (root.width <= 0 || root.height <= 0) return
        val shot = copy(activity.window, root) ?: return
        val canvas = Canvas(shot)
        val origin = IntArray(2).also(root::getLocationOnScreen)
        for (dialog in HomeButton.showingDialogs()) {
            val window = dialog.window ?: continue
            val view = window.decorView
            if (view.width <= 0 || view.height <= 0) continue
            val attrs = window.attributes
            if (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                canvas.drawColor(Color.argb((attrs.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
            val part = copy(window, view) ?: continue
            val at = IntArray(2).also(view::getLocationOnScreen)
            canvas.drawBitmap(part, (at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat(), null)
            part.recycle()
        }
        withContext(Dispatchers.IO) { save(shot, File(dir(activity), "$name.webp")) }
        shot.recycle()
        names += name
    }

    /** The window's pixels as drawn on screen (hardware layers, blur and all); a software draw before Android 8. */
    private suspend fun copy(window: Window, view: View): Bitmap? {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.decorView.background?.let { bg -> bg.setBounds(0, 0, view.width, view.height); bg.draw(Canvas(bitmap)) }
            view.draw(Canvas(bitmap))
            return bitmap
        }
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(window, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                cont.resume(false)
            }
        }
        return if (ok) bitmap else { bitmap.recycle(); null }
    }

    private fun save(shot: Bitmap, file: File) {
        val scaled = if (shot.width > MAX_WIDTH)
            Bitmap.createScaledBitmap(shot, MAX_WIDTH, shot.height * MAX_WIDTH / shot.width, true) else shot
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.outputStream().use { scaled.compress(format, QUALITY, it) }
        if (scaled !== shot) scaled.recycle()
    }

    companion object {
        const val MAX_WIDTH = 1080
        private const val QUALITY = 90

        /** Where the screenshots wait for the export to pick them up (the cache folder is shared with the launcher). */
        fun dir(context: Context) = File(context.cacheDir, "showcase-shots").apply { mkdirs() }
    }
}
