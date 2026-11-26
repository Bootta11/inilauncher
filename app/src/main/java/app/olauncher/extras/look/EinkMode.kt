package app.olauncher.extras.look

import android.content.Context
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs

/**
 * "Force e-ink mode": makes upstream's isEinkDisplay() return true, so everything
 * upstream already does for e-ink screens (light theme, no animations, no blur)
 * also happens on screens it doesn't detect.
 */
object EinkMode {
    fun isForced(context: Context): Boolean =
        ExtrasPrefs(context).bool(ExtrasKeys.FORCE_EINK)
}
