package app.olauncher.extras.notes

import app.olauncher.helper.fadeVisible
import app.olauncher.helper.fadeTo
import android.content.Context
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.helper.createDialog

/** A short note shown under the clock on the home screen. Tap it to edit. */
object HomeNote {

    const val MAX_LENGTH = 500

    fun isEnabled(prefs: ExtrasPrefs) = prefs.bool(ExtrasKeys.NOTE_ENABLED)

    fun text(prefs: ExtrasPrefs): String = prefs.string(ExtrasKeys.NOTE_TEXT).orEmpty()

    /** Shows the note (or a hint to add one) in [view], or hides it when the feature is off. */
    fun bind(view: TextView) {
        val prefs = ExtrasPrefs(view.context)
        if (!isEnabled(prefs)) return view.fadeVisible(false)
        val note = text(prefs)
        view.text = note.ifBlank { view.context.getString(R.string.note_tap_to_add) }
        val alpha = if (note.isBlank()) 0.6f else 1f
        view.fadeTo(alpha) // already shown: fades to it
        view.fadeVisible(true, alpha) // just shown: fades in to it
        view.setOnClickListener { edit(view.context) { bind(view) } }
    }

    fun edit(context: Context, onSaved: () -> Unit) {
        val prefs = ExtrasPrefs(context)
        var input: EditText? = null
        context.createDialog(
            title = R.string.note,
            action = R.string.save,
            onAction = {
                prefs.setString(ExtrasKeys.NOTE_TEXT, input?.text?.toString()?.trim()?.take(MAX_LENGTH))
                onSaved()
            },
            content = { parent: ViewGroup ->
                LayoutInflater.from(parent.context).inflate(R.layout.extras_dialog_note, parent, false).also {
                    input = it.findViewById<EditText>(R.id.noteInput).apply {
                        setText(text(prefs))
                        setSelection(text.length)
                    }
                }
            },
        ).apply {
            showRespectingStatusBar()
            // Open ready to type: dialogs block the keyboard by default
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            input?.let { field ->
                field.requestFocus()
                field.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }
}
