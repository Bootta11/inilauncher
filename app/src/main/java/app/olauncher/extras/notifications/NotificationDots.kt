package app.olauncher.extras.notifications

import android.content.Context
import android.widget.TextView
import app.olauncher.data.Prefs
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.folders.FolderStore
import app.olauncher.extras.folders.Folders

/**
 * The dot (or count) after the names of apps that have notifications: home screen, app list and
 * the home sub list. Part of "Notifications on home screen"; its options can turn the dots off,
 * show a count instead, and put dots on folders.
 */
object NotificationDots {

    const val DOT = " •"
    const val STYLE_DOT = ""
    const val STYLE_COUNT = "count"
    private val SUFFIX = Regex(" •(9\\+|\\d+)?$")

    /** The main "Notifications on home screen" switch. */
    fun isEnabled(context: Context) = ExtrasPrefs(context).bool(ExtrasKeys.NOTIFICATIONS_ENABLED)

    fun dotsEnabled(context: Context) = isEnabled(context) && !ExtrasPrefs(context).bool(ExtrasKeys.NOTIFICATIONS_NO_DOTS)

    /** The "N notifications" line under the clock. */
    fun lineEnabled(context: Context) = isEnabled(context) && !ExtrasPrefs(context).bool(ExtrasKeys.NOTIFICATIONS_NO_LINE)

    fun hasNotifications(packageName: String): Boolean =
        InilauncherNotificationListener.notifications.value.any { it.packageName == packageName }

    private var countedList: List<NotificationItem>? = null
    private var countedValue: Map<String, Int> = emptyMap()

    /** Notifications per app, counted the same way as the notifications list; once per snapshot, not once per row. */
    @Synchronized
    private fun counts(context: Context): Map<String, Int> {
        val list = InilauncherNotificationListener.notifications.value
        if (list !== countedList) {
            countedValue = NotificationGrouping.countsByPackage(NotificationGrouping.visible(list, context.packageName))
            countedList = list
        }
        return countedValue
    }

    /** How many notifications an app has; for a folder, its apps' total (when dots on folders are on). */
    fun count(context: Context, packageName: String, activityClassName: String?): Int {
        if (packageName.isEmpty()) return 0
        val counts = counts(context)
        if (!Folders.isFolder(packageName)) return counts[packageName] ?: 0
        if (!ExtrasPrefs(context).bool(ExtrasKeys.NOTIFICATION_DOTS_FOLDERS) || activityClassName.isNullOrEmpty()) return 0
        val folder = FolderStore(context).get(activityClassName) ?: return 0
        return folder.apps.map { it.packageName }.distinct().sumOf { counts[it] ?: 0 }
    }

    /** The text to put after an app's (or folder's) name: "", " •" or " •3". */
    fun suffix(context: Context, packageName: String, activityClassName: String?): String =
        if (!dotsEnabled(context)) "" else format(count(context, packageName, activityClassName), style(context))

    /** The text to put after something standing for [count] notifications (e.g. the closed sub list). */
    fun suffixFor(context: Context, count: Int): String =
        if (!dotsEnabled(context)) "" else format(count, style(context))

    fun style(context: Context) = ExtrasPrefs(context).string(ExtrasKeys.NOTIFICATION_DOT_STYLE).orEmpty()

    /** " •", " •3" or " •9+" for [count] notifications; "" for none. Pure, unit tested. */
    fun format(count: Int, style: String): String = when {
        count <= 0 -> ""
        style == STYLE_COUNT -> DOT + if (count > 9) "9+" else count.toString()
        else -> DOT
    }

    /** The text inside an icon badge ([BadgedIcon]): "" (a plain dot), "3" or "9+". Pure, unit tested. */
    fun badgeText(count: Int, style: String): String = when {
        count <= 0 || style != STYLE_COUNT -> ""
        count > 9 -> "9+"
        else -> count.toString()
    }

    /** [text] without a dot or count added earlier. Pure, unit tested. */
    fun strip(text: String): String = text.replace(SUFFIX, "")

    /** Adds or removes the dot on the home-screen app names, using upstream's saved slots. */
    fun decorateHome(context: Context, views: List<TextView>) {
        val prefs = Prefs(context)
        val slots = listOf(
            prefs.appPackage1 to prefs.appActivityClassName1, prefs.appPackage2 to prefs.appActivityClassName2,
            prefs.appPackage3 to prefs.appActivityClassName3, prefs.appPackage4 to prefs.appActivityClassName4,
            prefs.appPackage5 to prefs.appActivityClassName5, prefs.appPackage6 to prefs.appActivityClassName6,
            prefs.appPackage7 to prefs.appActivityClassName7, prefs.appPackage8 to prefs.appActivityClassName8,
        )
        views.forEachIndexed { i, view ->
            val base = strip(view.text.toString())
            val (pkg, cls) = slots.getOrNull(i) ?: ("" to null)
            val wanted = base + suffix(context, pkg, cls)
            if (view.text.toString() != wanted) view.text = wanted
        }
    }
}
