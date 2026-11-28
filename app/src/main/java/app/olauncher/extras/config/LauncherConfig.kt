package app.olauncher.extras.config

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.notifications.InilauncherNotificationListener
import app.olauncher.extras.picker.InstalledApps
import app.olauncher.helper.appUsagePermissionGranted
import java.io.File
import java.util.UUID

/**
 * Export and import of the launcher's configuration on the phone (docs/configuration.md). The rules
 * live in the pure [ConfigFile] / [ConfigImport]; this connects them to the preferences, the
 * installed apps and the permissions.
 */
object LauncherConfig {

    private const val TAG = "LauncherConfig"
    private const val UPSTREAM_PREFS = "app.olauncher"
    private const val UNDO_FILE = "config-undo.json"
    /** Debug builds only: written by scripts/push-config.sh through `adb run-as`. */
    const val DEBUG_IMPORT_FILE = "import-config.json"

    internal class Prefs(private val prefs: SharedPreferences) : KeyValues {
        override fun bool(key: String, default: Boolean) = try { prefs.getBoolean(key, default) } catch (e: ClassCastException) { default }
        override fun int(key: String, default: Int) = try { prefs.getInt(key, default) } catch (e: ClassCastException) { default }
        override fun float(key: String, default: Float) = try { prefs.getFloat(key, default) } catch (e: ClassCastException) { default }
        override fun string(key: String): String? = try { prefs.getString(key, null) } catch (e: ClassCastException) { null }
    }

    internal class Editor(val editor: SharedPreferences.Editor) : KeyEdits {
        override fun putBool(key: String, value: Boolean) { editor.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
        override fun putFloat(key: String, value: Float) { editor.putFloat(key, value) }
        override fun putString(key: String, value: String?) { if (value == null) editor.remove(key) else editor.putString(key, value) }
    }

    private fun upstream(context: Context) = context.applicationContext.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE)
    private fun extras(context: Context) = ExtrasPrefs(context).prefs

    fun store(context: Context) = Store(Prefs(upstream(context)), Prefs(extras(context)))

    /** The phone side of an import. Loads the installed apps once (slow: build it off the main thread). */
    class PhoneDevice(private val context: Context) : Device {
        private val mainSerial = (context.getSystemService(Context.USER_SERVICE) as UserManager).getSerialNumberForUser(Process.myUserHandle())
        /** Launchable apps in the main profile only; hidden apps stay out while they're locked. */
        private val apps: List<FolderApp> = InstalledApps.all(context).filter { it.userSerial == mainSerial }
        private val packs: Set<String> by lazy { AppIcons.installedPacks(context).map { it.first }.toSet() }

        override fun app(packageName: String, activity: String?): FolderApp? =
            apps.firstOrNull { it.packageName == packageName && (activity == null || it.activityClassName == activity) }
        override fun iconPackInstalled(packageName: String) = packageName in packs
        override fun granted(requirement: Requirement): Boolean = when (requirement) {
            Requirement.NONE -> true
            Requirement.CONTACTS -> has(Manifest.permission.READ_CONTACTS)
            Requirement.CALENDAR -> has(Manifest.permission.READ_CALENDAR)
            Requirement.NOTIFICATION_ACCESS -> InilauncherNotificationListener.isAccessGranted(context)
            Requirement.USAGE_ACCESS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.appUsagePermissionGranted()
        }
        override val mainUser: String = Process.myUserHandle().toString()
        override val widgetsLabel: String = context.getString(R.string.widgets) + " ›"
        override fun newId() = UUID.randomUUID().toString()

        private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun export(context: Context, parts: Set<Part>, meta: ConfigMeta): String =
        ConfigFile.export(store(context), parts, meta, BuildConfig.VERSION_NAME)

    /**
     * Reads a picked file, refusing anything over the size limit before it's all in memory. A .zip
     * with screenshots ([ConfigBundle]) gives its `config.json`; nothing else in it is read.
     */
    fun read(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw SchemaError("file", "can't be opened")
        val bytes = input.use { it.readNBytesCompat(ConfigBundle.MAX_BYTES + 1) }
        if (ConfigBundle.isZip(bytes)) return ConfigBundle.readConfig(bytes)
        if (bytes.size > JsonGuard.MAX_BYTES) throw SchemaError("file", "is larger than ${JsonGuard.MAX_BYTES / 1024} KB")
        return String(bytes, Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Applies [plan]: first saves the current configuration for [undo], then writes everything and
     * commits both preference files. The caller recreates the activity so the new look shows.
     */
    // commit, not apply: callers run off the main thread, and the activity is recreated right after
    @android.annotation.SuppressLint("ApplySharedPref")
    fun apply(context: Context, plan: ImportPlan, device: Device, saveUndo: Boolean = true) {
        if (saveUndo) undoFile(context).writeText(export(context, Part.entries.toSet(), ConfigMeta()))
        val up = upstream(context).edit()
        val ex = extras(context).edit()
        ConfigImport.apply(plan, Edits(Editor(up), Editor(ex)), device)
        up.commit()
        ex.commit()
        // Drop caches that hold the old values
        AppIcons.setMode(context, ExtrasPrefs(context).string(ExtrasKeys.ICONS).orEmpty())
        Folders.notifyChanged()
    }

    private fun undoFile(context: Context) = File(context.filesDir, UNDO_FILE)

    fun canUndo(context: Context) = undoFile(context).isFile

    /** Puts back what the last import replaced (the same checks as any import). */
    fun undo(context: Context): Boolean {
        val file = undoFile(context)
        if (!file.isFile) return false
        return try {
            val config = ConfigFile.parse(file.readText())
            val device = PhoneDevice(context)
            apply(context, ConfigImport.plan(config, config.parts, store(context), device), device, saveUndo = false)
            file.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "undo failed", e)
            false
        }
    }

    /**
     * Debug builds: imports `files/import-config.json` once, if scripts/push-config.sh put it there.
     * Only `adb run-as` can write that folder, and only on a debuggable build; release builds never
     * look. Runs off the main thread; recreates [activity] when something changed.
     */
    fun debugAutoImport(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        val file = File(activity.filesDir, DEBUG_IMPORT_FILE)
        if (!file.isFile) return
        val context = activity.applicationContext
        Thread {
            val text = try { file.readText() } finally { file.delete() }
            try {
                val config = ConfigFile.parse(text)
                val device = PhoneDevice(context)
                val plan = ConfigImport.plan(config, config.parts, store(context), device)
                plan.skips.forEach { Log.w(TAG, "skipped ${it.path}: ${it.reason}") }
                apply(context, plan, device)
                Log.i(TAG, "imported ${plan.changes.size} settings${if (plan.layoutSummary.isEmpty()) "" else " and the layout"}")
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) activity.recreate() }
            } catch (e: SchemaError) {
                Log.e(TAG, "not imported: ${e.message}")
            }
        }.start()
    }
}
