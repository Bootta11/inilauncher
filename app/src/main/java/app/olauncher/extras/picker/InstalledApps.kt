package app.olauncher.extras.picker

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import app.olauncher.R
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.privacy.HiddenApps
import app.olauncher.helper.isPrivateSpaceProfile

/**
 * Every launchable app in every profile (work apps marked), A–Z, without Inilauncher. Private Space
 * apps stay out (upstream keeps them in their own section), and so do hidden apps while
 * "Lock hidden apps" is locked. Slow with many apps: call it off the main thread.
 */
object InstalledApps {

    fun all(context: Context): List<FolderApp> {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val me = Process.myUserHandle()
        val hidden = HiddenApps.hiddenSet(context)
        val showHidden = HiddenApps.showInPickers(context)
        return userManager.userProfiles.filterNot { isPrivateSpaceProfile(context, it) }.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            val activities = try {
                launcher.getActivityList(null, user)
            } catch (e: Exception) {
                emptyList()
            }
            val visible = HiddenApps.filter(activities, hidden, showHidden) { HiddenApps.key(it.componentName.packageName, user) }
            visible.filter { it.componentName.packageName != context.packageName }.map { info ->
                val label = info.label.toString()
                FolderApp(
                    label = if (user == me) label else context.getString(R.string.sublist_other_profile, label),
                    packageName = info.componentName.packageName,
                    activityClassName = info.componentName.className,
                    userSerial = serial,
                )
            }
        }.distinctBy { key(it) }.sortedBy { it.label.lowercase() }
    }

    /** One app in one profile. */
    fun key(app: FolderApp) = app.packageName + "|" + app.userSerial
}
