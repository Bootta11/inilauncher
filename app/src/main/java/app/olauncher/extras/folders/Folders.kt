package app.olauncher.extras.folders

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.showToast

/**
 * Glue between folders and upstream's app list. A folder shows up in the list as an
 * [AppModel.App] with the [FOLDER_PACKAGE] package and the folder id as activity class,
 * so it can be placed on the home screen or a swipe gesture like any app. Launching
 * it (MainViewModel.launchApp hook) opens the folder instead.
 */
object Folders {

    const val FOLDER_PACKAGE = "inilauncher.folder"
    private const val LABEL_SUFFIX = " ›"

    /** Folder id to open; observed by MainActivity. */
    val openRequests = MutableLiveData<String?>()

    /** Bumped when folders change, so the app list and home screen refresh. */
    val changes = MutableLiveData(0)

    fun isFolder(packageName: String?) = packageName == FOLDER_PACKAGE

    fun requestOpen(folderId: String?) {
        if (!folderId.isNullOrBlank()) openRequests.postValue(folderId)
    }

    fun notifyChanged() = changes.postValue((changes.value ?: 0) + 1)

    /** Upstream's app list with the folders on top. */
    fun withFolders(context: Context, apps: MutableList<AppModel>): MutableList<AppModel> {
        val folders = FolderStore(context).all()
        if (folders.isEmpty()) return apps
        val user = Process.myUserHandle()
        val entries = folders.map { folder ->
            AppModel.App(
                appLabel = folder.name + LABEL_SUFFIX,
                key = null,
                appPackage = FOLDER_PACKAGE,
                activityClassName = folder.id,
                isNew = false,
                user = user,
            )
        }
        return (entries + apps.filterNot { isFolder(it.appPackage) }).toMutableList()
    }

    fun folderAppOf(context: Context, app: AppModel.App): FolderApp {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return FolderApp(app.appLabel, app.appPackage, app.activityClassName, userManager.getSerialNumberForUser(app.user))
    }

    /** The user (profile) an app saved by serial number belongs to; the main user if it's gone. */
    fun userOf(context: Context, app: FolderApp): UserHandle {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.getUserForSerialNumber(app.userSerial) ?: Process.myUserHandle()
    }

    /** Opens an app saved in a folder or the home sub list. */
    fun launch(context: Context, app: FolderApp) {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = userOf(context, app)
        try {
            val activities = launcher.getActivityList(app.packageName, user)
            val component = activities.firstOrNull { it.componentName.className == app.activityClassName }?.componentName
                ?: activities.lastOrNull()?.componentName
                ?: return context.showToast(context.getString(R.string.app_not_found))
            launcher.startMainActivity(ComponentName(component.packageName, component.className), user, null, null)
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_app))
        }
    }
}
