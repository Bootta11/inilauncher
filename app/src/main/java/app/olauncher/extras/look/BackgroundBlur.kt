package app.olauncher.extras.look

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.navigation.NavController
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.helper.isEinkDisplay

/**
 * Blurs the wallpaper behind the app list and the settings screens (Android 12+).
 * The home screen keeps the sharp wallpaper. Off on e-ink and when the system
 * turns cross-window blur off (battery saver, developer option, weak hardware).
 */
object BackgroundBlur {

    fun isSupported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !activity.isEinkDisplay()

    /** Called from MainActivity.onCreate once the NavController exists. */
    fun attach(activity: Activity, navController: NavController) {
        if (!isSupported(activity)) return
        navController.addOnDestinationChangedListener { _, destination, _ ->
            apply(activity, destination.id != R.id.mainFragment)
        }
    }

    /** Re-applies after the setting changes while a settings screen is open. */
    fun refresh(activity: Activity) {
        if (isSupported(activity)) apply(activity, blurred = true)
    }

    private fun apply(activity: Activity, blurred: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val on = blurred && ExtrasPrefs(activity).bool(ExtrasKeys.BLUR) &&
                activity.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val radius = if (on) activity.resources.getDimensionPixelSize(R.dimen.extras_background_blur_radius) else 0
        activity.window.setBackgroundBlurRadius(radius)
    }
}
