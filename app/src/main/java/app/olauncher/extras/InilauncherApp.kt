package app.olauncher.extras

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.olauncher.extras.showcase.Showcase
import java.io.File

/**
 * Inilauncher's Application. Its only job: in the `:showcase` process, every preferences file is
 * swapped for the showcase's own copy, so the showcase can never read or change the real setup.
 * In the launcher's own process this costs one boolean check per call.
 */
class InilauncherApp : Application() {

    override fun attachBaseContext(base: Context) {
        Showcase.inProcess = processName().endsWith(Showcase.PROCESS_SUFFIX)
        super.attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(if (Showcase.inProcess) Showcase.prefsName(name) else name, mode)

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName().orEmpty()
        else try { File("/proc/self/cmdline").readText().trimEnd('\u0000') } catch (e: Exception) { "" }
}
