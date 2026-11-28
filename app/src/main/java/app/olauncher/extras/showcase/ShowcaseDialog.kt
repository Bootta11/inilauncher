package app.olauncher.extras.showcase

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.extras.ListDialog
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.picker.AppPickerScreen
import app.olauncher.extras.picker.InstalledApps
import app.olauncher.extras.wallpaper.DailyWallpapers
import app.olauncher.extras.wallpaper.WallpaperList
import app.olauncher.helper.OlDialog
import app.olauncher.helper.animateChanges
import app.olauncher.helper.fadeTo
import app.olauncher.helper.getBitmapFromURL
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Extras → Backup & sharing → Showcase screens: the stand-in apps for home, sub list, folder and
 * app list, and the background. Each list opens the usual app picker (search, A–Z, icons).
 */
object ShowcaseDialog {

    /** [onOpen] starts the live showcase ([reset]: copy the current look again); [onPickImage] opens the photo picker. */
    fun show(context: Context, initial: ShowcaseChoice, onOpen: (reset: Boolean) -> Unit, onPickImage: (onPicked: () -> Unit) -> Unit) {
        var choice = initial
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.setText(R.string.showcase_title)
        b.tvMessage.setText(R.string.showcase_hint)
        b.tvMessage.isVisible = true
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(context, 8) }
        }
        body.animateChanges() // summaries get longer and shorter as apps are picked

        fun save(next: ShowcaseChoice) { choice = next; ShowcaseApps.save(context, next) }

        val home = row(context, body, R.string.showcase_home)
        val sublist = row(context, body, R.string.showcase_sublist)
        body.addView(label(context, R.string.showcase_folder_name, topPadding = 14))
        val folderName = EditText(context).apply {
            background = null
            setText(choice.folderName)
            setHint(R.string.showcase_folder_name)
            textSize = 16f
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setHintTextColor(context.getColorFromAttr(R.attr.primaryColorTrans50))
            filters = arrayOf(InputFilter.LengthFilter(40))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            alpha = 0.6f
            setPadding(0, dp(context, 2), 0, 0)
        }
        body.addView(folderName)
        val folder = row(context, body, R.string.showcase_folder, topPadding = 10)
        val drawer = row(context, body, R.string.showcase_app_list)
        val background = row(context, body, R.string.showcase_background)

        fun names(apps: List<FolderApp>) = apps.joinToString(", ") { it.label }.ifEmpty { context.getString(R.string.showcase_none) }
        fun refresh() {
            home.text = names(choice.home)
            sublist.text = names(choice.sublist)
            folder.text = names(choice.folder)
            drawer.text = context.resources.getQuantityString(R.plurals.showcase_apps, choice.drawer.size, choice.drawer.size)
            background.text = context.getString(when (choice.wallpaper) {
                ShowcaseWallpaper.Theme -> R.string.showcase_bg_theme
                ShowcaseWallpaper.Daily -> R.string.showcase_bg_daily
                is ShowcaseWallpaper.ListPair -> R.string.showcase_bg_list
                ShowcaseWallpaper.Image -> R.string.showcase_bg_image
            })
        }
        refresh()

        /** Ticks apps on and off in the picker; the list keeps the order they were ticked in. */
        fun pick(title: Int, current: List<FolderApp>, limit: Int, update: (List<FolderApp>) -> ShowcaseChoice) {
            val list = current.toMutableList()
            // The editor steps aside: the picker's keyboard would otherwise shrink its window for good
            dialog.hide()
            AppPickerScreen.pickMany(context, context.getString(title), current, onToggle = { app, on ->
                if (on) {
                    if (list.size >= limit) context.showToast(context.getString(R.string.showcase_limit, limit))
                    else list += app
                } else list.removeAll { InstalledApps.key(it) == InstalledApps.key(app) }
            }, onDone = { save(update(list)); refresh(); dialog.show() })
        }
        (home.parent as View).setOnClickListener { pick(R.string.showcase_home, choice.home, ShowcaseChoice.MAX_HOME) { choice.copy(home = it) } }
        (sublist.parent as View).setOnClickListener { pick(R.string.showcase_sublist, choice.sublist, 40) { choice.copy(sublist = it) } }
        (folder.parent as View).setOnClickListener { pick(R.string.showcase_folder, choice.folder, 40) { choice.copy(folder = it) } }
        (drawer.parent as View).setOnClickListener { pick(R.string.showcase_app_list, choice.drawer, 100) { list -> choice.copy(drawer = list.sortedBy { it.label.lowercase() }) } }
        (background.parent as View).setOnClickListener {
            val options = listOf(R.string.showcase_bg_theme, R.string.showcase_bg_daily, R.string.showcase_bg_list, R.string.showcase_bg_image).map(context::getString)
            ListDialog.show(context, context.getString(R.string.showcase_background), options) { i ->
                when (i) {
                    0 -> { save(choice.copy(wallpaper = ShowcaseWallpaper.Theme)); refresh() }
                    1 -> { save(choice.copy(wallpaper = ShowcaseWallpaper.Daily)); refresh() }
                    2 -> {
                        // One card at a time: the editor steps aside while the photos are open
                        dialog.hide()
                        pickListPhoto(context, onClosed = { dialog.show() }) { pair -> save(choice.copy(wallpaper = pair)); refresh() }
                    }
                    else -> onPickImage { save(choice.copy(wallpaper = ShowcaseWallpaper.Image)); refresh() }
                }
            }
        }
        folderName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                save(choice.copy(folderName = s?.toString()?.trim().orEmpty().ifBlank { ShowcaseChoice.DEFAULT_FOLDER }))
            }
        })

        b.contentContainer.addView(body)
        b.contentContainer.isVisible = true
        b.tvAction.setText(R.string.showcase_open)
        b.tvAction.setOnClickListener { dialog.dismiss(); onOpen(false) }
        b.tvNeutral.setText(R.string.showcase_reset)
        b.tvNeutral.isVisible = true
        b.tvNeutral.setOnClickListener {
            // Default apps again, and the showcase's own setting changes are dropped
            val app = context.applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                val reset = withContext(Dispatchers.IO) { ShowcaseApps.reset(app).also { ShowcaseSetup.prepare(app, it) } }
                choice = reset
                folderName.setText(reset.folderName)
                refresh()
                app.showToast(R.string.showcase_reset_done)
            }
        }
        b.ivClose.setOnClickListener { dialog.dismiss() }
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    /** A clickable row: a label and, below it, a faded summary (returned). */
    private fun row(context: Context, parent: LinearLayout, label: Int, topPadding: Int = 14): TextView {
        val color = context.getColorFromAttr(R.attr.primaryColor)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, topPadding), 0, dp(context, 6))
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        box.addView(label(context, label, topPadding = 0))
        val summary = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = 14f
            setTextColor(color)
            alpha = 0.6f
            setPadding(0, dp(context, 2), 0, 0)
        }
        box.addView(summary)
        parent.addView(box)
        return summary
    }

    private fun label(context: Context, text: Int, topPadding: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
        setText(text)
        textSize = 16f
        setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, topPadding), 0, 0)
    }

    /** The pairs from Inilauncher's photo list as small previews (light | dark); a tap picks one. */
    private fun pickListPhoto(context: Context, onClosed: () -> Unit, onChosen: (ShowcaseWallpaper.ListPair) -> Unit) {
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.setText(R.string.showcase_bg_list)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 12); marginEnd = dp(context, 8)
            }
        }
        val placeholder = context.getColorFromAttr(R.attr.primaryColorTrans50)
        fun thumb(url: String) = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(placeholder)
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 150), 1f).apply { marginEnd = dp(context, 4) }
            scope.launch {
                // Unsplash scales on request: a small preview instead of the full photo
                val small = if ("images.unsplash.com" in url) url.replace(Regex("([?&])w=\\d+"), "$1w=300") else url
                val bitmap = getBitmapFromURL(small, 300, 500)
                // Already on screen (see-through until loaded): fade it in place
                if (bitmap != null) { setImageBitmap(bitmap); fadeTo(1f) } else fadeTo(0.3f)
            }
        }
        scope.launch {
            val pairs = withContext(Dispatchers.IO) { DailyWallpapers.cachedPairs(context) }
            pairs.forEach { pair ->
                val light = WallpaperList.photoFor(pair, dark = false).url
                val dark = WallpaperList.photoFor(pair, dark = true).url
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(context, 8))
                    addView(thumb(light))
                    addView(thumb(dark))
                    setOnClickListener { dialog.dismiss(); onChosen(ShowcaseWallpaper.ListPair(light, dark)) }
                })
            }
        }
        b.contentContainer.addView(grid)
        b.contentContainer.isVisible = true
        b.tvAction.isVisible = false
        b.ivClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { scope.cancel(); onClosed() }
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
