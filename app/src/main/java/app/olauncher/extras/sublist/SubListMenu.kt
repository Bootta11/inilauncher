package app.olauncher.extras.sublist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.extras.ListDialog
import app.olauncher.extras.picker.AppPickerScreen
import app.olauncher.helper.OlDialog
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.showToast

/** The sub list's dialogs: the button's menu, "Choose apps" and the menu of one app in it. */
object SubListMenu {

    val BUTTONS = listOf(
        SubListStore.BUTTON_DOTS to R.string.sublist_button_dots,
        SubListStore.BUTTON_GRID to R.string.sublist_button_grid,
        SubListStore.BUTTON_COUNT to R.string.sublist_button_count,
        SubListStore.BUTTON_PEEK to R.string.sublist_button_peek,
    )

    val STYLES = listOf(
        SubListStore.STYLE_ICONS to R.string.sublist_style_icons,
        SubListStore.STYLE_ICONS_NAMES to R.string.sublist_style_icons_names,
        SubListStore.STYLE_NAMES to R.string.sublist_style_names,
    )

    private fun nameOf(options: List<Pair<String, Int>>, value: String) =
        (options.firstOrNull { it.first == value } ?: options.first()).second

    /** Long-press on the button: choose apps, the button's look, what it shows, most-used apps. */
    fun show(context: Context, onChanged: () -> Unit) {
        val store = SubListStore(context)
        val (dialog, binding) = baseDialog(context, context.getString(R.string.sublist))
        binding.tvAction.setText(R.string.okay)
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        val options = column(context)
        fun option(text: String, action: () -> Unit) = options.addView(row(context, text).apply {
            setOnClickListener { dialog.dismiss(); action() }
        })
        option(context.getString(R.string.sublist_choose_apps_count, store.apps().size)) { chooseApps(context, onChanged) }
        option(context.getString(R.string.sublist_button_row, context.getString(nameOf(BUTTONS, store.button)))) {
            ListDialog.show(context, context.getString(R.string.sublist_button), BUTTONS.map { context.getString(it.second) }) { i ->
                store.button = BUTTONS[i].first
                onChanged()
            }
        }
        option(context.getString(R.string.sublist_style_row, context.getString(nameOf(STYLES, store.style)))) {
            ListDialog.show(context, context.getString(R.string.sublist_style), STYLES.map { context.getString(it.second) }) { i ->
                store.style = STYLES[i].first
                onChanged()
            }
        }
        option(context.getString(R.string.sublist_free_row, context.getString(if (store.freePlacement) R.string.on else R.string.off))) {
            store.freePlacement = !store.freePlacement
            onChanged()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val state = context.getString(if (store.auto) R.string.on else R.string.off)
            option(context.getString(R.string.sublist_auto_row, state)) {
                if (store.auto) store.auto = false
                else if (context.appUsagePermissionGranted()) store.auto = true
                else openUsageAccess(context)
                onChanged()
            }
        }
        binding.contentContainer.addView(options)
        binding.contentContainer.isVisible = true
        dialog.showRespectingStatusBar()
    }

    /** Usage access is granted on a system screen; turn "Add most-used apps" on again after that. */
    private fun openUsageAccess(context: Context) {
        context.showToast(context.getString(R.string.sublist_auto_hint))
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** The full-screen app picker as a checklist: picked apps on top, tap to add or remove. */
    fun chooseApps(context: Context, onChanged: () -> Unit) {
        val store = SubListStore(context)
        AppPickerScreen.pickMany(
            context, context.getString(R.string.sublist_apps), store.apps(),
            onToggle = { app, on -> if (on) store.add(app) else store.remove(app) },
            onDone = onChanged,
            // Picked apps first, then the most-used ones (when "Add most-used apps" is on)
            suggested = SubList.mostUsed(context),
        )
    }

    /** Held an app in the sub list without dragging it: remove it; keep or hide a most-used one. */
    fun showItem(context: Context, entry: SubList.Entry, onChanged: () -> Unit) {
        val store = SubListStore(context)
        val (dialog, binding) = baseDialog(context, entry.app.label)
        binding.tvAction.setText(R.string.okay)
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        val options = column(context)
        fun option(label: Int, action: () -> Unit) =
            options.addView(row(context, context.getString(label)).apply {
                setOnClickListener {
                    dialog.dismiss()
                    action()
                    onChanged()
                }
            })
        if (entry.auto) {
            option(R.string.sublist_keep) { store.add(entry.app) }
            option(R.string.sublist_no_suggest) { store.addNoSuggest(entry.app.packageName) }
        } else {
            option(R.string.sublist_remove) { store.remove(entry.app) }
        }
        app.olauncher.extras.AppActions.addOptions(entry.app.packageName, ::option, context, entry.user)
        binding.contentContainer.addView(options)
        binding.contentContainer.isVisible = true
        dialog.showRespectingStatusBar()
    }

    private fun baseDialog(context: Context, title: String): Pair<OlDialog, DialogBaseBinding> {
        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.text = title
        binding.ivClose.setOnClickListener { dialog.dismiss() }
        dialog.setView(binding.root)
        return dialog to binding
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 8), 0, 0)
    }

    private fun row(context: Context, value: String) = TextView(context, null, 0, R.style.TextSmall).apply {
        text = value
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 10), 0, dp(context, 10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
