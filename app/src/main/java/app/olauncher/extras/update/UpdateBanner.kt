package app.olauncher.extras.update

import app.olauncher.helper.fadeVisible
import app.olauncher.helper.animateChanges
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.olauncher.ApkRelease
import app.olauncher.ApkUpdates
import app.olauncher.InstallState
import app.olauncher.R
import app.olauncher.databinding.ExtrasUpdateBannerBinding
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The update card (Views port of the Zealot skill's banner): "Version X is available" with the
 * changelog, Later / Update, the "Install unknown apps" step, a progress bar and errors. Later
 * hides it until a newer version. Added over every screen from ExtrasActivityHooks. The download
 * itself lives in [ApkUpdates.installState], so a recreated screen picks it up where it is.
 */
object UpdateBanner {

    fun attach(activity: AppCompatActivity) {
        if (!ApkUpdates.supported) return
        val root = activity.findViewById<ViewGroup>(R.id.mainActivityLayout) ?: return
        val b = ExtrasUpdateBannerBinding.inflate(LayoutInflater.from(activity), root, false)
        root.addView(b.root)
        (b.root as? ViewGroup)?.animateChanges() // progress/buttons swap smoothly
        val prefs = ExtrasPrefs(activity)
        var release: ApkRelease? = null
        var state: InstallState = InstallState.Idle
        var askPermission = false

        fun render() {
            val r = release
            val dismissed = prefs.string(ExtrasKeys.UPDATE_DISMISSED)?.toLongOrNull() ?: 0L
            val downloading = state as? InstallState.Downloading
            b.root.fadeVisible(r != null && (r.versionCode > dismissed || downloading != null))
            if (r == null) return
            b.updateTitle.text = activity.getString(R.string.update_available, r.versionName)
            b.updateProgress.isVisible = downloading != null
            downloading?.let { b.updateProgress.progress = it.percent }
            b.updateLater.isVisible = downloading == null
            b.updateAction.isVisible = downloading == null
            when {
                downloading == null && askPermission -> {
                    b.updateMessage.setText(R.string.update_permission)
                    b.updateAction.setText(R.string.update_open_settings)
                }
                state == InstallState.Failed -> {
                    b.updateMessage.setText(R.string.update_failed)
                    b.updateAction.setText(R.string.update_retry)
                }
                else -> {
                    b.updateMessage.text = r.changelog.ifBlank { activity.getString(R.string.update_no_notes) }
                    b.updateAction.setText(R.string.update_now)
                }
            }
        }

        b.updateLater.setOnClickListener {
            val r = release ?: return@setOnClickListener
            prefs.setString(ExtrasKeys.UPDATE_DISMISSED, r.versionCode.toString())
            askPermission = false
            ApkUpdates.clearFailure()
            render()
        }
        b.updateAction.setOnClickListener {
            val r = release ?: return@setOnClickListener
            if (askPermission) {
                ApkUpdates.openInstallSettings(activity)
                askPermission = false
                return@setOnClickListener render()
            }
            if (!ApkUpdates.canInstall(activity)) {
                askPermission = true
                return@setOnClickListener render()
            }
            ApkUpdates.install(activity, r)
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ApkUpdates.available, ApkUpdates.installState) { r, s -> r to s }.collect { (r, s) ->
                    release = r
                    state = s
                    render()
                }
            }
        }
    }
}
