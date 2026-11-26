package app.olauncher.extras.folders

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.extras.AppActions
import app.olauncher.extras.drag.DragColumn
import app.olauncher.extras.drag.DragSession
import app.olauncher.extras.drag.HoldDrag
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.showToast

/** Dialogs for opening, editing and filling folders. Uses upstream's dialog layout. */
object FolderUi {

    /** Opens a folder: tap an app to launch it, hold and drag it to move it, hold and let go for its menu. */
    fun open(context: Context, folderId: String) {
        val store = FolderStore(context)
        val folder = store.get(folderId) ?: return
        val (dialog, binding) = baseDialog(context, folder.name)
        binding.tvAction.setText(R.string.folder_edit)
        binding.tvAction.setOnClickListener {
            dialog.dismiss()
            showFolderMenu(context, folderId)
        }

        val list = DragColumn(context).apply { setPadding(0, dp(context, 8), 0, 0) }
        if (folder.apps.isEmpty()) list.addView(text(context, R.style.TextSmallLight, context.getString(R.string.folder_empty)))
        // The home screen refreshes once the folder closes; doing it behind the open dialog would nudge the dialog
        var movedAny = false
        dialog.setOnDismissListener { if (movedAny) Folders.notifyChanged() }
        fun move(from: Int, to: Int) {
            val row = list.getChildAt(from) ?: return
            list.moveChild(from, to)
            store.moveApp(folderId, row.tag as FolderApp, to)
            movedAny = true
        }
        fun moveBy(row: View, by: Int): Boolean {
            val from = list.indexOfChild(row)
            if (from < 0 || from + by !in 0 until list.childCount || DragSession.of(list) != null) return false
            move(from, from + by)
            return true
        }
        folder.apps.forEach { app ->
            list.addView(text(context, R.style.TextMedium, app.label).apply {
                tag = app
                fun menu() {
                    dialog.dismiss()
                    showAppMenu(context, folderId, app)
                }
                setOnClickListener {
                    dialog.dismiss()
                    Folders.launch(context, app)
                }
                setOnLongClickListener {
                    menu()
                    true
                }
                setOnTouchListener(HoldDrag({ startDrag(list, it, ::move) }, { menu() }))
                ViewCompat.addAccessibilityAction(this, context.getString(R.string.folder_move_up)) { row, _ -> moveBy(row, -1) }
                ViewCompat.addAccessibilityAction(this, context.getString(R.string.folder_move_down)) { row, _ -> moveBy(row, 1) }
            })
        }
        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        dialog.showRespectingStatusBar()
    }

    private fun startDrag(list: DragColumn, row: View, move: (Int, Int) -> Unit): DragSession? {
        if (DragSession.of(list) != null) return null
        // Rows are as wide as the dialog: grow from the text's side so it isn't cut off
        row.pivotX = if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) row.width.toFloat() else 0f
        row.pivotY = row.height / 2f
        return DragSession(
            list, row, (0 until list.childCount).toList(), list::slots,
            liftScale = 1.04f, vertical = true, keepInside = true, onDrop = move,
        )
    }

    private fun showAppMenu(context: Context, folderId: String, app: FolderApp) {
        val store = FolderStore(context)
        val (dialog, binding) = baseDialog(context, app.label)
        binding.tvAction.setText(R.string.okay)
        binding.tvAction.setOnClickListener { dialog.dismiss() }
        val options = column(context)
        options.addView(text(context, R.style.TextSmall, context.getString(R.string.folder_remove_app)).apply {
            setOnClickListener {
                store.removeApp(folderId, app)
                Folders.notifyChanged()
                dialog.dismiss()
                open(context, folderId)
            }
        })
        // App info / Uninstall leave the launcher, so the folder isn't reopened after them
        AppActions.addOptions(app.packageName, { label, action ->
            options.addView(text(context, R.style.TextSmall, context.getString(label)).apply {
                setOnClickListener { dialog.dismiss(); action() }
            })
        }, context, Folders.userOf(context, app))
        binding.contentContainer.addView(options)
        binding.contentContainer.isVisible = true
        dialog.showRespectingStatusBar()
    }

    /** Rename or delete a folder. */
    fun showFolderMenu(context: Context, folderId: String) {
        val store = FolderStore(context)
        val folder = store.get(folderId) ?: return
        val (dialog, binding) = baseDialog(context, folder.name)
        val input = nameInput(context, folder.name)
        binding.contentContainer.addView(input)
        binding.contentContainer.isVisible = true
        binding.tvNeutral.setText(R.string.folder_delete)
        binding.tvNeutral.isVisible = true
        binding.tvNeutral.setOnClickListener {
            store.delete(folderId)
            Folders.notifyChanged()
            dialog.dismiss()
        }
        binding.tvAction.setText(R.string.save)
        binding.tvAction.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                store.rename(folderId, name)
                Folders.notifyChanged()
            }
            dialog.dismiss()
        }
        showWithKeyboard(dialog)
    }

    /** "Add to folder": pick an existing folder or make a new one. */
    fun addToFolder(context: Context, appModel: AppModel) {
        if (appModel !is AppModel.App || Folders.isFolder(appModel.appPackage)) return
        val store = FolderStore(context)
        val app = Folders.folderAppOf(context, appModel)
        val (dialog, binding) = baseDialog(context, context.getString(R.string.folder_add_to))
        val list = column(context)
        store.all().forEach { folder ->
            list.addView(text(context, R.style.TextMedium, folder.name).apply {
                setOnClickListener {
                    store.addApp(folder.id, app)
                    Folders.notifyChanged()
                    context.showToast(context.getString(R.string.folder_added, appModel.appLabel, folder.name))
                    dialog.dismiss()
                }
            })
        }
        val input = nameInput(context, "").apply { setHint(R.string.folder_new_name_hint) }
        list.addView(input)
        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        binding.tvAction.setText(R.string.folder_create)
        binding.tvAction.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                context.showToast(context.getString(R.string.folder_name_needed))
                return@setOnClickListener
            }
            val folder = store.create(name, app)
            Folders.notifyChanged()
            context.showToast(context.getString(R.string.folder_added, appModel.appLabel, folder.name))
            dialog.dismiss()
        }
        showWithKeyboard(dialog)
    }

    /** Shows [dialog] and lets its text field bring up the keyboard (AlertDialog blocks it by default). */
    private fun showWithKeyboard(dialog: OlDialog) {
        dialog.showRespectingStatusBar()
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
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

    private fun text(context: Context, style: Int, value: String) = TextView(context, null, 0, style).apply {
        text = value
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 10), 0, dp(context, 10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun nameInput(context: Context, value: String): EditText =
        (LayoutInflater.from(context).inflate(R.layout.extras_folder_name_input, android.widget.FrameLayout(context), false) as EditText).apply {
            setText(value)
            setSelection(text.length)
        }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
