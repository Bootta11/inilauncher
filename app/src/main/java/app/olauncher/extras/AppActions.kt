package app.olauncher.extras

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.extras.folders.Folders
import app.olauncher.helper.OlDialog
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

/**
 * Android's own app actions (App info, Uninstall) for the long-press menus outside the app
 * drawer: home apps, the sub list and folders. Same rules as the drawer's Info / Uninstall.
 */
object AppActions {

    /** A real installed app, not a folder or one of Inilauncher's own entries. */
    fun isRealApp(packageName: String?) =
        !packageName.isNullOrEmpty() && !InternalEntries.isInternal(packageName) && !Folders.isFolder(packageName)

    fun appInfo(context: Context, packageName: String, user: UserHandle) = openAppInfo(context, user, packageName)

    /** Other-profile apps and system apps can't be uninstalled from here: App info opens instead. */
    fun uninstall(context: Context, packageName: String, user: UserHandle) {
        when {
            user != Process.myUserHandle() -> openAppInfo(context, user, packageName)
            context.isSystemApp(packageName, user) -> {
                context.showToast(context.getString(R.string.system_app_cannot_delete))
                openAppInfo(context, user, packageName)
            }
            else -> context.uninstall(packageName)
        }
    }

    /** The two rows, for a menu that builds rows with [option] (label, action). */
    fun addOptions(packageName: String, option: (label: Int, action: () -> Unit) -> Unit, context: Context, user: UserHandle) {
        if (!isRealApp(packageName)) return
        option(R.string.info) { appInfo(context, packageName, user) }
        option(R.string.delete) { uninstall(context, packageName, user) }
    }

    /**
     * Long-press on a home app: Change app / Info / Uninstall. Empty slots, folders and internal
     * entries go straight to [change] (upstream's picker), as before.
     */
    fun homeAppLongPress(context: Context, slot: Int, change: () -> Unit) {
        val prefs = Prefs(context)
        val packageName = prefs.getAppPackage(slot)
        if (!isRealApp(packageName)) return change()
        val userString = prefs.getAppUser(slot)
        val user = if (userString.isEmpty()) Process.myUserHandle() else getUserHandleFromString(context, userString)

        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.text = prefs.getAppName(slot)
        binding.ivClose.setOnClickListener { dialog.dismiss() }
        binding.tvAction.setText(R.string.okay)
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, 0)
        }
        fun option(label: Int, action: () -> Unit) = options.addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = context.getString(label)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { dialog.dismiss(); action() }
        })
        option(R.string.home_app_change, change)
        addOptions(packageName, ::option, context, user)
        binding.contentContainer.addView(options)
        binding.contentContainer.isVisible = true
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
