package app.olauncher.extras

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.extras.widgets.WidgetPanel
import app.olauncher.extras.folders.FolderUi
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.notifications.AppNotifier
import app.olauncher.extras.weather.WeatherRepository
import app.olauncher.extras.weather.WeatherWorker
import app.olauncher.extras.privacy.HiddenAppsLock

/** Activity-level observers for the extras, wired from MainActivity with one line. */
object ExtrasActivityHooks {

    fun observe(activity: AppCompatActivity, viewModel: MainViewModel) {
        // Local notifications (e.g. "updated, tap to open"): channel + one-time permission prompt
        AppNotifier.ensureChannel(activity)
        val askNotifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (!ExtrasPrefs(activity).bool(ExtrasKeys.ASKED_POST_NOTIFICATIONS)) {
            ExtrasPrefs(activity).setBool(ExtrasKeys.ASKED_POST_NOTIFICATIONS, true)
            AppNotifier.requestPermissionIfNeeded(activity, askNotifications)
        }
        if (WeatherRepository(activity).isEnabled()) WeatherWorker.schedule(activity)
        // Direct build: "Version X is available" card over every screen (no-op in the Play build)
        app.olauncher.extras.update.UpdateBanner.attach(activity)
        showWhatsNewOnce(activity)
        // Debug builds: a configuration pushed with scripts/push-config.sh (adb run-as only)
        app.olauncher.extras.config.LauncherConfig.debugAutoImport(activity)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                app.olauncher.ApkUpdates.check(activity)
                // Catches up on a sunrise/sunset the worker missed (doze, the phone was off)
                app.olauncher.extras.daynight.DayNight.syncInBackground(activity)
            }
        })

        Folders.openRequests.observe(activity) { id ->
            if (id == null) return@observe
            Folders.openRequests.value = null
            FolderUi.open(activity, id)
        }
        WidgetPanel.openRequests.observe(activity) { open ->
            if (open != true) return@observe
            WidgetPanel.openRequests.value = false
            val nav = activity.findNavController(R.id.nav_host_fragment)
            if (nav.currentDestination?.id != R.id.widgetPanelFragment) {
                nav.popBackStack(R.id.mainFragment, false)
                nav.navigate(R.id.widgetPanelFragment)
            }
        }
        var firstChange = true
        Folders.changes.observe(activity) {
            // LiveData replays its current value on subscribe; only react to real changes
            if (firstChange) { firstChange = false; return@observe }
            viewModel.getAppList()
            viewModel.refreshHome(true)
        }

        val screenOff = HiddenAppsLock.screenOffReceiver()
        ContextCompat.registerReceiver(activity, screenOff, HiddenAppsLock.screenOffFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = activity.unregisterReceiver(screenOff)
        })
    }

    /** After an update: the new version's notes, once, when the home screen is up (WhatsNew). */
    private fun showWhatsNewOnce(activity: AppCompatActivity) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                activity.lifecycle.removeObserver(this)
                val notes = app.olauncher.extras.whatsnew.WhatsNew.takeUnseen(activity)
                if (notes.isEmpty()) return
                // Let the home screen settle first, so the dialog opens over it instead of with it
                activity.window.decorView.postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) app.olauncher.extras.whatsnew.WhatsNewDialog.show(activity, notes)
                }, 600)
            }
        })
    }
}
