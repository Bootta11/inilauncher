package app.olauncher.extras.widgets

import android.content.Context
import android.os.Process
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs

/**
 * The widget panel appears in the app list as a "Widgets ›" entry (package [PACKAGE]) while
 * the feature is on, so it can be opened from the list or put on a swipe gesture or home slot.
 */
object WidgetPanel {

    const val PACKAGE = "inilauncher.widgets"
    const val HOST_ID = 0x1A1

    val openRequests = MutableLiveData(false)

    fun isEnabled(context: Context) = ExtrasPrefs(context).bool(ExtrasKeys.WIDGETS_ENABLED)

    fun isPanel(packageName: String?) = packageName == PACKAGE

    fun requestOpen() = openRequests.postValue(true)

    fun entry(context: Context): AppModel.App = AppModel.App(
        appLabel = context.getString(R.string.widgets) + " ›",
        key = null,
        appPackage = PACKAGE,
        activityClassName = PACKAGE,
        isNew = false,
        user = Process.myUserHandle(),
    )

    /** Upstream's app list with the panel entry on top while widgets are on. */
    fun withPanel(context: Context, apps: MutableList<AppModel>): MutableList<AppModel> {
        val rest = apps.filterNot { isPanel(it.appPackage) }
        return (if (isEnabled(context)) listOf(entry(context)) + rest else rest).toMutableList()
    }
}
