package app.olauncher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Play build: Google Play updates the app, so there's never a release to offer (the real one is in src/direct). */
object ApkUpdates {
    val supported = false
    val available: StateFlow<ApkRelease?> = MutableStateFlow(null)

    fun check(context: Context) {}

    fun checkNow(context: Context, onDone: (UpdateCheck) -> Unit) {}

    fun canInstall(context: Context) = false

    fun openInstallSettings(context: Context) {}

    val installState: StateFlow<InstallState> = MutableStateFlow(InstallState.Idle)

    fun install(context: Context, release: ApkRelease) {}

    fun clearFailure() {}
}
