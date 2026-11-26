package app.olauncher.extras

import android.content.Context
import app.olauncher.extras.folders.FolderStore
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.widgets.WidgetPanel

/**
 * Inilauncher's own entries in the app list (folders, the Widgets panel). They use made-up
 * `inilauncher.*` packages, so upstream's "is this app installed?" checks would reject them;
 * this answers "does it still exist?" instead.
 */
object InternalEntries {

    fun isInternal(packageName: String?) = packageName?.startsWith("inilauncher.") == true

    fun exists(context: Context, packageName: String, activityClassName: String?): Boolean = when {
        Folders.isFolder(packageName) -> !activityClassName.isNullOrBlank() && FolderStore(context).get(activityClassName) != null
        WidgetPanel.isPanel(packageName) -> WidgetPanel.isEnabled(context)
        else -> false
    }
}
