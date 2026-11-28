package app.olauncher.extras.showcase

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.picker.InstalledApps
import app.olauncher.extras.showcase.ShowcaseChoice.Role

/** Reads and saves the showcase's stand-in apps; the first time, finds ordinary apps by role. */
object ShowcaseApps {

    /** The saved choice, or the defaults. Slow (reads the installed apps): call it off the main thread. */
    fun load(context: Context): ShowcaseChoice =
        ShowcaseChoice.decode(ExtrasPrefs(context).string(ExtrasKeys.SHOWCASE)) ?: defaults(context)

    fun save(context: Context, choice: ShowcaseChoice) = ExtrasPrefs(context).setString(ExtrasKeys.SHOWCASE, choice.encode())

    /** Back to the default apps; the background stays as chosen. */
    fun reset(context: Context): ShowcaseChoice {
        val current = ShowcaseChoice.decode(ExtrasPrefs(context).string(ExtrasKeys.SHOWCASE))
        return defaults(context, current?.wallpaper ?: ShowcaseWallpaper.Daily).also { save(context, it) }
    }

    private fun defaults(context: Context, wallpaper: ShowcaseWallpaper = ShowcaseWallpaper.Daily): ShowcaseChoice {
        val serial = (context.getSystemService(Context.USER_SERVICE) as UserManager).getSerialNumberForUser(Process.myUserHandle())
        val installed = InstalledApps.all(context).filter { it.userSerial == serial }
        val launchable = installed.map { it.packageName }.toSet()
        val found = LinkedHashMap<Role, FolderApp>()
        for (role in Role.entries) {
            val pkg = resolve(context, role, launchable) ?: continue
            installed.firstOrNull { it.packageName == pkg }?.let { found[role] = it }
        }
        return ShowcaseChoice.defaults(found, wallpaper)
    }

    /**
     * The app that handles [role]'s standard intent: the default one, or, when several could
     * (Android would ask), the first preinstalled one. Apps the user installed are only used
     * when they're the default, so the showcase shows ordinary apps.
     */
    private fun resolve(context: Context, role: Role, launchable: Set<String>): String? {
        val intent = intent(role) ?: return null
        val pm = context.packageManager
        val own = context.packageName
        fun ok(pkg: String?) = pkg != null && pkg != "android" && pkg != own && pkg in launchable
        val default = try { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName } catch (e: Exception) { null }
        if (ok(default)) return default
        val all = try { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (e: Exception) { emptyList() }
        return all.map { it.activityInfo }
            .filter { ok(it.packageName) && it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .map { it.packageName }.firstOrNull()
    }

    private fun intent(role: Role): Intent? {
        val intent = when (role) {
            Role.PHONE -> Intent(Intent.ACTION_DIAL)
            Role.MESSAGES -> selector(Intent.CATEGORY_APP_MESSAGING)
            Role.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            Role.BROWSER -> selector(Intent.CATEGORY_APP_BROWSER)
            Role.MAPS -> selector(Intent.CATEGORY_APP_MAPS)
            Role.PHOTOS -> selector(Intent.CATEGORY_APP_GALLERY)
            Role.CALENDAR -> selector(Intent.CATEGORY_APP_CALENDAR)
            Role.CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            Role.MUSIC -> selector(Intent.CATEGORY_APP_MUSIC)
            Role.CALCULATOR -> selector(Intent.CATEGORY_APP_CALCULATOR)
            Role.FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selector(Intent.CATEGORY_APP_FILES) else null
            Role.CONTACTS -> selector(Intent.CATEGORY_APP_CONTACTS)
            Role.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            Role.EMAIL -> selector(Intent.CATEGORY_APP_EMAIL)
        }
        return intent
    }

    /** MAIN with the role's category, as the system matches it ("android" is the chooser when several match). */
    private fun selector(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)
}
