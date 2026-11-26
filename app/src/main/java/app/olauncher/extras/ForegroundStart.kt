package app.olauncher.extras

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle

/**
 * From Android 14, a PendingIntent or IntentSender made by another app (or by Android itself, like a
 * widget's setup screen) may only open an activity when the sender lends its own right to do so.
 * The launcher is on screen when it sends one, so it lends it.
 */
object ForegroundStart {

    /** Options to pass when sending; null before Android 14, where nothing is needed. */
    @Suppress("DEPRECATION")
    fun options(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }
}
