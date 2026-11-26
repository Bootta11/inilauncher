package app.olauncher.extras.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.helper.showToast

/**
 * Optional lock on the hidden-apps list: fingerprint/face, or the phone's PIN, pattern
 * or password. Once unlocked it stays open until the screen turns off (or 5 minutes).
 */
object HiddenAppsLock {

    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private const val UNLOCK_MS = 5 * 60 * 1000L
    private var unlockedUntil = 0L

    fun isEnabled(context: Context) = ExtrasPrefs(context).bool(ExtrasKeys.LOCK_HIDDEN_APPS)

    /** True when the phone has a screen lock or biometrics we can ask for. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun isUnlocked(context: Context): Boolean =
        !isEnabled(context) || SystemClock.elapsedRealtime() < unlockedUntil

    fun relock() {
        unlockedUntil = 0L
    }

    /** Asks to unlock, then runs [onUnlocked]. Without a screen lock it lets you through with a hint. */
    fun unlock(fragment: Fragment, @StringRes subtitle: Int = R.string.lock_prompt_subtitle, onUnlocked: () -> Unit) {
        val context = fragment.requireContext()
        if (!canAuthenticate(context)) {
            context.showToast(context.getString(R.string.lock_no_screen_lock))
            onUnlocked()
            return
        }
        val prompt = BiometricPrompt(fragment, ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
                    onUnlocked()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.lock_prompt_title))
                .setSubtitle(context.getString(subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }

    /** Relocks when the screen turns off. Registered for the activity's lifetime. */
    fun screenOffReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = relock()
    }

    val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
}
