package app.olauncher.extras.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.extras.privacy.HiddenAppsLock
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The steps around the dialogs: saving, sharing, importing (with the lock check) and undo. */
object ConfigUi {

    /** Writes [text] to a file the user picked with the system's save dialog. */
    fun save(context: Context, uri: Uri, text: String) {
        val ok = try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            false
        }
        context.showToast(if (ok) R.string.config_saved else R.string.config_save_failed)
    }

    /** Shares [text] as a .json file through the share sheet; the previous shared file is removed first. */
    fun share(fragment: Fragment, text: String, fileName: String) {
        val context = fragment.requireContext()
        val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, fileName).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.configshare", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fragment.startActivity(Intent.createChooser(send, null))
    }

    /**
     * Imports a picked file: asks for the hidden-apps lock first when it's on, checks the file,
     * shows the preview, and applies it only after Import. Then the screen is rebuilt.
     */
    fun import(fragment: Fragment, uri: Uri) {
        val context = fragment.requireContext()
        if (HiddenAppsLock.isEnabled(context) && !HiddenAppsLock.isUnlocked(context)) {
            HiddenAppsLock.unlock(fragment, R.string.config_lock_subtitle) { load(fragment, uri) }
        } else load(fragment, uri)
    }

    private fun load(fragment: Fragment, uri: Uri) {
        val app = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    Result.success(ConfigFile.parse(LauncherConfig.read(app, uri)) to LauncherConfig.PhoneDevice(app))
                } catch (e: SchemaError) {
                    Result.failure(e)
                } catch (e: Exception) {
                    android.util.Log.w("LauncherConfig", "import failed", e)
                    Result.failure(SchemaError("file", "can't be read"))
                }
            }
            val ui = fragment.context ?: return@launch
            loaded.onFailure { e ->
                android.util.Log.w("LauncherConfig", "not imported: ${e.message}")
                val reason = (e as? SchemaError)?.let { "${it.path} ${it.reason}" } ?: "?"
                ui.showToast(ui.getString(R.string.config_not_imported, reason), android.widget.Toast.LENGTH_LONG)
            }.onSuccess { (config, device) ->
                ConfigDialogs.preview(ui, config, plan = { parts -> ConfigImport.plan(config, parts, LauncherConfig.store(app), device) }) { plan ->
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { LauncherConfig.apply(app, plan, device) }
                        app.showToast(R.string.config_imported)
                        fragment.activity?.recreate()
                    }
                }
            }
        }
    }

    fun undo(fragment: Fragment) {
        val app = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { LauncherConfig.undo(app) }
            app.showToast(if (ok) R.string.config_undone else R.string.config_undo_failed)
            if (ok) fragment.activity?.recreate()
        }
    }
}
