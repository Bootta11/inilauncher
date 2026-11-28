package app.olauncher.extras.showcase

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.extras.HomeButton
import app.olauncher.extras.folders.FolderUi
import app.olauncher.extras.sublist.SubList
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isDarkThemeOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The real launcher screens on the showcase's own preferences, in the `:showcase` process
 * (docs/configuration.md#screenshots). [MODE_CAPTURE] steps through home, sub list, folder and app
 * list, saves a screenshot of each and closes; [MODE_LIVE] leaves it to the user (Back on home or
 * the Home button leaves). The process ends with the activity, so nothing of it stays running.
 */
class ShowcaseActivity : MainActivity() {

    private var capturing = false

    override fun attachBaseContext(context: Context) {
        // Activity-scoped preferences (upstream's Prefs(context)) go to the showcase files too
        super.attachBaseContext(ShowcaseContext(context))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capturing = intent.getStringExtra(EXTRA_MODE) == MODE_CAPTURE
        // Our own background instead of the phone's wallpaper (which could be a personal photo)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        val base = ColorDrawable(getColorFromAttr(R.attr.primaryInverseColor))
        window.setBackgroundDrawable(base)
        if (capturing) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        else hideSystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findNavController(R.id.nav_host_fragment).currentDestination?.id == R.id.mainFragment) {
                    finish()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        lifecycleScope.launch {
            val choice = ShowcaseChoice.decode(intent.getStringExtra(EXTRA_CHOICE))
            val metrics = resources.displayMetrics
            val photo = choice?.let { ShowcaseBackground.load(this@ShowcaseActivity, it.wallpaper, isDarkThemeOn(), metrics.widthPixels, metrics.heightPixels) }
            if (photo != null) setBackground(base, CenterCropDrawable(photo))
            if (capturing) capture()
        }
    }

    /** Fades from the plain colour to the photo, like any other change on screen. */
    private fun setBackground(from: Drawable, to: Drawable) {
        if (!animationsOn()) { window.setBackgroundDrawable(to); return }
        val fade = TransitionDrawable(arrayOf(from, to)).apply { isCrossFadeEnabled = true }
        window.setBackgroundDrawable(fade)
        fade.startTransition(Motion.medium(this).toInt())
    }

    /** No status bar (notification icons) or navigation bar in a phone screenshot; a swipe shows them briefly. */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !capturing) hideSystemBars()
    }

    /** Home, sub list, folder, app list: waits for each to settle, then copies the window. */
    private suspend fun capture() {
        val shots = ShowcaseCapture(this)
        val settle = Motion.medium(this) * 2 + 300
        shots.clear()
        delay(900) // icons and the app list load in the background
        shots.take(NAME_HOME)

        val button = findViewById<android.view.View>(R.id.extrasSubListButton)
        if (SubList.isEnabled(this) && button != null && button.isShown) {
            button.performClick()
            delay(settle)
            if (findViewById<android.view.View>(R.id.extrasSubListScroll)?.isShown == true) {
                shots.take(NAME_SUBLIST)
                button.performClick()
                delay(settle)
            }
        }

        ShowcaseSetup.folderId(this)?.let { id ->
            FolderUi.open(this, id)
            delay(settle)
            if (HomeButton.showingDialogs().isNotEmpty()) shots.take(NAME_FOLDER)
            HomeButton.pressed()
            delay(settle)
        }

        try {
            findNavController(R.id.nav_host_fragment).navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(Constants.Key.FLAG to Constants.FLAG_LAUNCH_APP, Constants.Key.RENAME to false),
            )
            delay(settle + 300)
            shots.take(NAME_DRAWER)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "app list not captured", e)
        }

        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SHOTS, ArrayList(shots.names)))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Left for another app or the real home screen: close, so its process never lingers
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_enter, R.anim.fade_exit)
    }

    override fun onDestroy() {
        val ending = isFinishing
        super.onDestroy()
        // Nothing of the showcase keeps running: its process ends with it
        if (ending) Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    /** Sends every preferences file to the showcase's copy (see [Showcase.prefsName]). */
    private class ShowcaseContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(Showcase.prefsName(name), mode)
    }

    companion object {
        private const val TAG = "Showcase"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SHOTS = "shots"
        const val MODE_CAPTURE = "capture"
        const val MODE_LIVE = "live"
        const val NAME_HOME = "home"
        const val NAME_SUBLIST = "sublist"
        const val NAME_FOLDER = "folder"
        const val NAME_DRAWER = "app-list"
    }
}
