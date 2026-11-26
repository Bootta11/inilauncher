package app.olauncher.extras.look

import android.app.Activity
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs

/**
 * Font choices for the launcher's text. Each is a system font family, so nothing is
 * downloaded or bundled. The choice is applied as a theme overlay that sets upstream's
 * `mainFontFamily` attribute, on top of upstream's bold-font overlay.
 */
enum class FontChoice(@StringRes val label: Int, @StyleRes val overlay: Int) {
    DEFAULT(R.string.font_default, 0),
    SANS(R.string.font_sans, R.style.FontOverlay_Sans),
    CONDENSED(R.string.font_condensed, R.style.FontOverlay_Condensed),
    SERIF(R.string.font_serif, R.style.FontOverlay_Serif),
    MONO(R.string.font_mono, R.style.FontOverlay_Mono),
    CASUAL(R.string.font_casual, R.style.FontOverlay_Casual),
    ;

    companion object {
        fun current(prefs: ExtrasPrefs): FontChoice =
            entries.firstOrNull { it.name == prefs.string(ExtrasKeys.FONT) } ?: DEFAULT
    }
}

object Fonts {
    /** Called from MainActivity.onCreate, after upstream's own theme overlays. */
    fun applyTheme(activity: Activity) {
        val choice = FontChoice.current(ExtrasPrefs(activity))
        if (choice.overlay != 0) activity.theme.applyStyle(choice.overlay, true)
    }
}
