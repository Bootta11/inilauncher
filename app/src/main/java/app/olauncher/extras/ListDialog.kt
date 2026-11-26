package app.olauncher.extras

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog

/** A titled list of choices in upstream's dialog style. */
object ListDialog {

    fun show(context: Context, title: String, options: List<String>, onChosen: (Int) -> Unit) {
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.text = title
        b.ivClose.setOnClickListener { dialog.dismiss() }
        b.tvAction.setText(R.string.okay)
        b.tvAction.setOnClickListener { dialog.dismiss() }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        options.forEachIndexed { i, label ->
            list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = label
                setPadding(0, pad, 0, pad)
                setOnClickListener { dialog.dismiss(); onChosen(i) }
            })
        }
        b.contentContainer.addView(ScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                if (options.size > 8) (context.resources.displayMetrics.heightPixels * 0.5).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        b.contentContainer.isVisible = true
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
    }
}
