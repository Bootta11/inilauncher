package app.olauncher.extras.showcase

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.extras.ExtrasState
import app.olauncher.extras.config.ConfigBundle
import app.olauncher.extras.config.ConfigUi
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The steps around the showcase for one screen (Extras): sets up its files, starts it to take
 * screenshots or to look around, and turns the screenshots and the configuration into one .zip
 * to save or share. Create it as a property of the fragment (it registers activity results).
 */
class ShowcaseExport(private val fragment: Fragment) {

    /** The configuration waiting for its screenshots, and what to do with the bundle then. */
    private class Pending(val config: String, val fileName: String, val share: Boolean)

    private var pending: Pending? = null
    private var pendingBundle: ByteArray? = null

    private val capture = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ExtrasState.awaitingSystemScreen = false
        val job = pending ?: return@registerForActivityResult
        pending = null
        val context = fragment.context ?: return@registerForActivityResult
        val names = if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(ShowcaseActivity.EXTRA_SHOTS).orEmpty() else emptyList()
        if (names.isEmpty()) context.showToast(R.string.showcase_no_shots)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                val dir = ShowcaseCapture.dir(context)
                val shots = names.associateWith { java.io.File(dir, "$it.webp").readBytes() }
                dir.listFiles()?.forEach { it.delete() }
                ConfigBundle.write(job.config, shots)
            }
            if (job.share) ConfigUi.shareBytes(fragment, bundle, job.fileName, MIME_ZIP)
            else {
                pendingBundle = bundle
                ExtrasState.awaitingSystemScreen = true
                saveZip.launch(job.fileName)
            }
        }
    }

    /** The live showcase: nothing comes back, but the launcher may pop back home again once it closes. */
    private val live = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ExtrasState.awaitingSystemScreen = false
    }

    private val saveZip = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri: Uri? ->
        ExtrasState.awaitingSystemScreen = false
        val bytes = pendingBundle ?: return@registerForActivityResult
        pendingBundle = null
        if (uri != null) ConfigUi.saveBytes(fragment.requireContext(), uri, bytes)
    }

    /** The photo picker for the showcase's background (no permission needed); [onImagePicked] updates the editor. */
    private var onImagePicked: (() -> Unit)? = null
    private val pickImage = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        ExtrasState.awaitingSystemScreen = false
        val done = onImagePicked
        onImagePicked = null
        if (uri == null || done == null) return@registerForActivityResult
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val metrics = context.resources.displayMetrics
            val ok = withContext(Dispatchers.IO) {
                app.olauncher.extras.wallpaper.DailyWallpapers.decodeUriScaled(context, uri, metrics.widthPixels, metrics.heightPixels)
                    ?.let { ShowcaseBackground.savePicked(context, it, metrics.widthPixels, metrics.heightPixels) } == true
            }
            if (ok) done() else context.showToast(R.string.showcase_image_failed)
        }
    }

    /** Extras → Showcase screens: the editor for the stand-in apps and the background. */
    fun editor() {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val choice = withContext(Dispatchers.IO) { ShowcaseApps.load(context.applicationContext) }
            if (fragment.context == null) return@launch
            ShowcaseDialog.show(context, choice, onOpen = { reset -> openLive(reset) }, onPickImage = { done ->
                onImagePicked = done
                ExtrasState.awaitingSystemScreen = true
                pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }
    }

    /** Takes the screenshots on freshly set-up showcase screens, then saves or shares [config] with them. */
    fun export(config: String, fileName: String, share: Boolean) {
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val choice = withContext(Dispatchers.IO) {
                ShowcaseApps.load(context).also { ShowcaseSetup.prepare(context, it) }
            }
            pending = Pending(config, fileName, share)
            ExtrasState.awaitingSystemScreen = true
            capture.launch(intent(ShowcaseActivity.MODE_CAPTURE, choice))
        }
    }

    /** Opens the showcase to use by hand (e.g. What's new screenshots); [reset] copies the current look again. */
    fun openLive(reset: Boolean = false) {
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val choice = withContext(Dispatchers.IO) {
                ShowcaseApps.load(context).also {
                    // The apps as chosen now; the look is copied again only the first time or on Reset
                    ShowcaseSetup.prepare(context, it, copyLook = reset || !ShowcaseSetup.isPrepared(context))
                }
            }
            ExtrasState.awaitingSystemScreen = true
            live.launch(intent(ShowcaseActivity.MODE_LIVE, choice))
            @Suppress("DEPRECATION")
            fragment.activity?.overridePendingTransition(R.anim.fade_enter, R.anim.fade_exit)
        }
    }

    private fun intent(mode: String, choice: ShowcaseChoice) = Intent(fragment.requireContext(), ShowcaseActivity::class.java)
        .putExtra(ShowcaseActivity.EXTRA_MODE, mode)
        .putExtra(ShowcaseActivity.EXTRA_CHOICE, choice.encode())

    companion object {
        const val MIME_ZIP = "application/zip"
    }
}
