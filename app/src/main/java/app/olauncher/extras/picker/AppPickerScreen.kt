package app.olauncher.extras.picker

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.ExtrasAppPickerBinding
import app.olauncher.databinding.ExtrasAppPickerRowBinding
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.search.DrawerExtras
import app.olauncher.extras.search.LetterIndex
import app.olauncher.helper.appLabelMatches
import app.olauncher.helper.WindowBlur
import java.util.concurrent.Executors
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.showKeyboard

/**
 * Full-screen app picker, like the app list: search, icons, the A–Z strip (same side and height
 * settings) and the same keyboard behaviour. Use it for every app choice instead of a plain dialog
 * list ([pickOne] for one app, [pickMany] for a checklist).
 */
class AppPickerScreen private constructor(
    context: Context,
    title: String,
    private val many: Boolean,
    selected: List<FolderApp>,
    suggestedPackages: List<String> = emptyList(),
) : Dialog(context, R.style.ExtrasFullScreenPicker) {

    private val binding = ExtrasAppPickerBinding.inflate(LayoutInflater.from(this.context))
    /** Read in the background ([load]), so the screen opens at once; empty until then. */
    private var apps: List<FolderApp> = emptyList()
    private var loaded = false
    private val chosen = selected.map(InstalledApps::key).toMutableSet()
    /** Shown on top in their own order; fixed while the picker is open, so rows don't jump. */
    private var pinned: List<FolderApp> = emptyList()
    /** e.g. most-used apps, shown after the selected ones (main profile). */
    private var suggested: List<FolderApp> = emptyList()
    private var rows: List<AppPickerRows.Row> = emptyList()
    private var table = LetterIndex.build(emptyList())
    private var lastJump = -1
    private val adapter = Adapter()
    private val density = context.resources.displayMetrics.density
    private var headerOnLeft = false

    private var onApp: (FolderApp) -> Unit = {}

    init {
        setContentView(binding.root)
        binding.pickerTitle.text = title
        binding.pickerClose.setOnClickListener { dismiss() }
        binding.pickerList.layoutManager = LinearLayoutManager(this.context)
        binding.pickerList.adapter = adapter
        binding.pickerList.itemAnimator = null
        binding.pickerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) binding.pickerSearch.hideKeyboard()
            }
        })
        binding.pickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh(scrollToTop = true)
        })
        binding.pickerSearch.setOnEditorActionListener { v, _, _ ->
            // Enter with one result picks it, like the app list
            val only = rows.singleOrNull() as? AppPickerRows.Row.App
            if (only != null && !many) onApp(only.app) else v.hideKeyboard()
            true
        }
        setUpScroller()
        refresh(scrollToTop = true)
        load(selected, suggestedPackages)
    }

    /** Every app with its label is a slow read with many apps: done off the main thread. */
    private fun load(selected: List<FolderApp>, suggestedPackages: List<String>) {
        val app = context.applicationContext
        loader.execute {
            val all = try { InstalledApps.all(app) } catch (e: Exception) { emptyList() }
            main.post {
                apps = all
                pinned = selected.mapNotNull { s -> all.firstOrNull { InstalledApps.key(it) == InstalledApps.key(s) } }
                suggested = suggestedPackages.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } }
                loaded = true
                refresh(scrollToTop = true)
            }
        }
    }

    override fun show() {
        val window = window ?: return super.show()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Draw behind the bars and pad for them (and for the keyboard) ourselves
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        super.show()
        app.olauncher.extras.HomeButton.track(this)
        if (!Prefs(context).showStatusBar) window.hideStatusBar()
        // Blur the screen behind on Android 12+, like the app's dialogs
        blur = WindowBlur(window).apply { fadeIn() }
        binding.pickerSearch.showKeyboard(Prefs(context).autoShowKeyboard)
    }

    private var blur: WindowBlur? = null

    override fun dismiss() {
        binding.pickerSearch.hideKeyboard()
        val blur = blur ?: return super.dismiss()
        this.blur = null
        blur.fadeOut { super.dismiss() }
    }

    private fun query() = binding.pickerSearch.text?.toString().orEmpty()

    private fun refresh(scrollToTop: Boolean = false) {
        rows = AppPickerRows.build(
            apps, if (many) pinned else emptyList(), query(),
            context.getString(R.string.app_picker_selected), context.getString(R.string.app_picker_all),
            matches = { label, q -> appLabelMatches(label, q) },
            suggested = if (many) suggested else emptyList(),
            suggestedTitle = context.getString(R.string.app_picker_most_used),
        )
        table = LetterIndex.build(AppPickerRows.letterLabels(rows))
        binding.pickerScroller.setPresent(table.present)
        binding.pickerEmpty.isVisible = loaded && rows.isEmpty()
        adapter.notifyDataSetChanged() // the whole list changes with every search letter
        if (scrollToTop) (binding.pickerList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
    }

    // ---- A–Z strip: same side and height settings as the app list (as in WidgetPicker) ----

    private fun setUpScroller() {
        val scroller = binding.pickerScroller
        val lp = scroller.layoutParams as FrameLayout.LayoutParams
        val thumb = DrawerExtras.scrollerThumbReach(context)
        lp.gravity = DrawerExtras.scrollerSide(context) or (if (thumb) Gravity.BOTTOM else Gravity.TOP)
        scroller.layoutParams = lp
        if (thumb) (scroller.parent as View).addOnLayoutChangeListener { frame, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) frame.post {
                lp.height = ((frame.height - lp.bottomMargin) * 0.6f).toInt()
                scroller.layoutParams = lp
            }
        }
        headerOnLeft = Gravity.getAbsoluteGravity(lp.gravity, binding.pickerList.layoutDirection) and
            Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT

        scroller.onTouchStart = {
            lastJump = -1
            binding.pickerSearch.hideKeyboard()
        }
        scroller.onLetter = { letter ->
            val index = table.jump[LetterIndex.LETTERS.indexOf(letter)]
            val moves = index >= 0 && index != lastJump
            if (moves) {
                lastJump = index
                (binding.pickerList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
            }
            moves
        }
        scroller.onDoubleTap = { binding.pickerSearch.showKeyboard() }
        scroller.onKeyboardButton = { binding.pickerSearch.showKeyboard() }
    }

    // ---- List ----

    private fun dp(v: Int) = (v * density).toInt()
    /** The side next to the strip leaves room for its letters. */
    private val startPad get() = dp(if (headerOnLeft) 48 else 24)
    private val endPad get() = dp(if (headerOnLeft) 24 else 48)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is AppPickerRows.Row.Header) TYPE_HEADER else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) object : RecyclerView.ViewHolder(
                TextView(parent.context, null, 0, R.style.TextSmallLight).apply {
                    textSize = 13f
                    isAllCaps = true
                    setPaddingRelative(startPad, dp(16), endPad, dp(4))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }) {}
            else AppHolder(ExtrasAppPickerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is AppPickerRows.Row.Header -> (holder.itemView as TextView).text = row.title
                is AppPickerRows.Row.App -> (holder as AppHolder).bind(row.app)
            }
        }
    }

    private inner class AppHolder(val b: ExtrasAppPickerRowBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setPaddingRelative(startPad, b.root.paddingTop, endPad, b.root.paddingBottom)
        }

        fun bind(app: FolderApp) {
            b.rowLabel.text = app.label
            AppIcons.decorate(b.rowLabel, app.packageName, app.activityClassName, Folders.userOf(context, app))
            val isChosen = many && InstalledApps.key(app) in chosen
            b.rowCheck.visibility = if (isChosen) View.VISIBLE else View.INVISIBLE
            b.root.contentDescription = if (isChosen) app.label + ", " + context.getString(R.string.app_picker_selected_state) else app.label
            b.root.setOnClickListener { onApp(app) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP = 1
        private val loader = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        /** Pick one app; [noneLabel] adds a button that picks nothing (null). Closing changes nothing. */
        fun pickOne(context: Context, title: String, noneLabel: String?, onChosen: (FolderApp?) -> Unit) {
            val screen = AppPickerScreen(context, title, many = false, selected = emptyList())
            screen.onApp = { app -> screen.dismiss(); onChosen(app) }
            if (noneLabel != null) screen.binding.pickerAction.apply {
                text = noneLabel
                isVisible = true
                setOnClickListener { screen.dismiss(); onChosen(null) }
            }
            screen.show()
        }

        /**
         * Tick apps on and off ([onToggle] saves each change); [onDone] runs when it closes
         * (Done, ✕ or back). [selected] shows on top, then [suggested] packages (e.g. most used).
         */
        fun pickMany(
            context: Context,
            title: String,
            selected: List<FolderApp>,
            onToggle: (FolderApp, Boolean) -> Unit,
            onDone: () -> Unit,
            suggested: List<String> = emptyList(),
        ) {
            val screen = AppPickerScreen(context, title, many = true, selected = selected, suggestedPackages = suggested)
            screen.onApp = { app ->
                val key = InstalledApps.key(app)
                val on = key !in screen.chosen
                if (on) screen.chosen += key else screen.chosen -= key
                onToggle(app, on)
                screen.rows.forEachIndexed { i, row ->
                    if (row is AppPickerRows.Row.App && InstalledApps.key(row.app) == key) screen.adapter.notifyItemChanged(i)
                }
            }
            screen.binding.pickerAction.apply {
                setText(R.string.app_picker_done)
                isVisible = true
                setOnClickListener { screen.dismiss() }
            }
            screen.setOnDismissListener { onDone() }
            screen.show()
        }
    }
}
