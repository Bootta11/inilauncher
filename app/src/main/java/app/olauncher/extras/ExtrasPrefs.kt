package app.olauncher.extras

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Settings for Inilauncher's extra features. Kept in their own file, separate from
 * upstream's [app.olauncher.data.Prefs], so upstream changes merge without conflicts.
 * Every extra is off by default.
 */
class ExtrasPrefs(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun bool(key: String, default: Boolean = false) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    fun string(key: String, default: String? = null): String? = prefs.getString(key, default)
    fun setString(key: String, value: String?) = prefs.edit { putString(key, value) }

    fun int(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun setInt(key: String, value: Int) = prefs.edit { putInt(key, value) }

    companion object {
        const val FILE_NAME = "app.inilauncher.extras"
    }
}
