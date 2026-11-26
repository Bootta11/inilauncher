package app.olauncher.extras.search

import android.content.Context
import android.os.UserHandle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.extras.AppActions
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.picker.AppPickerScreen
import app.olauncher.extras.picker.InstalledApps
import app.olauncher.helper.OlDialog

/**
 * Apps the app list's "Suggested" row leaves out, and the ones pinned to its front. Package names,
 * so every profile's copy counts. Separate from the home sub list's own "Don't suggest" list.
 */
object SuggestedBlocklist {

    fun blocked(context: Context): Set<String> =
        ExtrasPrefs(context).string(ExtrasKeys.SUGGESTED_NO_SUGGEST).orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()

    fun setBlocked(context: Context, packageName: String, on: Boolean) {
        val now = blocked(context).let { if (on) it + packageName else it - packageName }
        ExtrasPrefs(context).setString(ExtrasKeys.SUGGESTED_NO_SUGGEST, now.joinToString(",").ifEmpty { null })
    }

    /** Pinned apps, in the order they were pinned: they fill the row's first places. */
    fun pinned(context: Context): List<String> =
        ExtrasPrefs(context).string(ExtrasKeys.SUGGESTED_PINNED).orEmpty()
            .split(',').filter { it.isNotBlank() }.distinct()

    fun setPinned(context: Context, packageName: String, on: Boolean) {
        val now = pinned(context).let { if (on) it - packageName + packageName else it - packageName }
        ExtrasPrefs(context).setString(ExtrasKeys.SUGGESTED_PINNED, now.joinToString(",").ifEmpty { null })
    }

    /** The settings row's value: "None" or "3 apps". */
    fun summary(context: Context): String {
        val count = blocked(context).size
        return if (count == 0) context.getString(R.string.suggested_no_suggest_none)
        else context.resources.getQuantityString(R.plurals.suggested_no_suggest_count, count, count)
    }

    /** The full-screen app checklist: ticked apps never show in the row. */
    fun choose(context: Context, onDone: () -> Unit) {
        val blocked = blocked(context)
        AppPickerScreen.pickMany(
            context, context.getString(R.string.suggested_no_suggest),
            selected = InstalledApps.all(context).filter { it.packageName in blocked }.distinctBy { it.packageName },
            onToggle = { app, on ->
                if (on) setPinned(context, app.packageName, false)
                setBlocked(context, app.packageName, on)
            },
            onDone = onDone,
        )
    }

    /**
     * Long-press on an app in the row: Pin or Unpin, Don't suggest, Info, Uninstall. Pin shows only
     * while the row has a place that isn't pinned ([canPin]). [onChanged] runs after any of the first two.
     */
    fun showMenu(anchor: View, label: String, packageName: String, user: UserHandle, canPin: Boolean, onChanged: () -> Unit) {
        val context = anchor.context
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.text = label
        binding.ivClose.setOnClickListener { dialog.dismiss() }
        binding.tvAction.setText(R.string.okay)
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        val density = context.resources.displayMetrics.density
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        fun option(text: Int, action: () -> Unit) = options.addView(
            TextView(context, null, 0, R.style.TextSmall).apply {
                setText(text)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { dialog.dismiss(); action() }
            })
        val isPinned = packageName in pinned(context)
        if (isPinned || canPin) option(if (isPinned) R.string.suggested_unpin else R.string.suggested_pin) {
            setPinned(context, packageName, !isPinned)
            onChanged()
        }
        option(R.string.suggested_no_suggest) {
            setPinned(context, packageName, false)
            setBlocked(context, packageName, true)
            onChanged()
        }
        AppActions.addOptions(packageName, { text, action -> option(text, action) }, context, user)
        binding.contentContainer.addView(options)
        binding.contentContainer.isVisible = true
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
    }
}
