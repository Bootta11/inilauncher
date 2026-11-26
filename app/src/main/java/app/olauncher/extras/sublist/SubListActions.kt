package app.olauncher.extras.sublist

import android.content.Context
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.extras.folders.Folders
import app.olauncher.helper.showToast

/** The app list's "Sub list" button (AppDrawerAdapter hook). */
object SubListActions {

    /** Regular apps only (not folders, Widgets or shortcuts), and only while the sub list is on. */
    fun canAdd(context: Context, appModel: AppModel) =
        appModel is AppModel.App && !appModel.appPackage.startsWith("inilauncher.") && SubList.isEnabled(context)

    fun toggle(context: Context, appModel: AppModel) {
        if (!canAdd(context, appModel)) return
        val app = Folders.folderAppOf(context, appModel as AppModel.App)
        val added = SubListStore(context).toggle(app)
        context.showToast(context.getString(if (added) R.string.sublist_added else R.string.sublist_removed, appModel.appLabel))
    }
}
