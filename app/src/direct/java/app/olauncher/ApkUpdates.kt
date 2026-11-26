package app.olauncher

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Direct build: new APKs from Zealot (docs/app-updates.md). check() asks Zealot at most every 6 hours (MainActivity
 * calls it on every resume), UpdateBanner offers the release, and install() downloads it and hands it to Android.
 * The Play build has a stub with the same API in src/play, and none of this code.
 */
object ApkUpdates {
    val supported: Boolean get() = AppRelease.ZEALOT_CHANNEL_KEY.isNotEmpty()

    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private var lastCheck = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val newer = MutableStateFlow<ApkRelease?>(null)

    /** The newest release above the installed one, once a check found it. */
    val available: StateFlow<ApkRelease?> = newer.asStateFlow()

    fun check(context: Context) {
        if (!supported || System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return
        lastCheck = System.currentTimeMillis()
        val app = context.applicationContext
        worker.execute {
            try {
                val release = ZealotClient.latest(app, AppRelease.ZEALOT_URL, AppRelease.ZEALOT_CHANNEL_KEY)
                newer.value = release?.let { ApkRelease(it.versionName, it.versionCode, it.changelog, it.url) }
            } catch (e: Exception) {
                Log.w("ApkUpdates", "Update check failed", e)
            }
        }
    }

    fun canInstall(context: Context): Boolean = ApkInstaller.canInstall(context)

    fun openInstallSettings(context: Context) = ApkInstaller.openInstallSettings(context)

    private val install = MutableStateFlow<InstallState>(InstallState.Idle)

    /**
     * The running install, kept here rather than in the banner, so a recreated screen shows the same download and a
     * second tap can't start another one into the same file.
     */
    val installState: StateFlow<InstallState> = install.asStateFlow()

    /** Downloads and installs the release (ignored while one is running); back to Idle once Android took over. */
    fun install(context: Context, release: ApkRelease) {
        if (install.value is InstallState.Downloading) return
        if (!ZealotClient.sameHost(release.url, AppRelease.ZEALOT_URL)) {
            install.value = InstallState.Failed
            return
        }
        install.value = InstallState.Downloading(0)
        val app = context.applicationContext
        worker.execute {
            install.value = try {
                ApkInstaller.install(app, release.url) { percent -> install.value = InstallState.Downloading(percent) }
                InstallState.Idle
            } catch (e: Exception) {
                Log.w("ApkUpdates", "Update install failed", e)
                InstallState.Failed
            }
        }
    }

    /** Forgets a failed attempt (the banner's Later). */
    fun clearFailure() {
        if (install.value == InstallState.Failed) install.value = InstallState.Idle
    }
}
