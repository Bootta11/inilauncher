package app.olauncher.extras.privacy

import android.content.Context
import android.os.UserHandle
import app.olauncher.data.Prefs

/**
 * Upstream's hidden apps, for the extras' own app lists. Hidden apps never fill the home sub list,
 * and with "Lock hidden apps" on they stay out of every picker until the lock is opened.
 */
object HiddenApps {

    /** Upstream's saved keys: "package|user". */
    fun hiddenSet(context: Context): Set<String> = Prefs(context).hiddenApps

    fun key(packageName: String, user: UserHandle) = "$packageName|$user"

    /** Whether pickers may list hidden apps now: the lock is off, or was opened lately. */
    fun showInPickers(context: Context) = HiddenAppsLock.isUnlocked(context)

    /** [apps] without the hidden ones, unless [show]. Pure, unit tested. */
    fun <T> filter(apps: List<T>, hidden: Set<String>, show: Boolean, key: (T) -> String): List<T> =
        if (show || hidden.isEmpty()) apps else apps.filterNot { key(it) in hidden }
}
