package app.olauncher.extras.widgets

import app.olauncher.helper.fadeVisible
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.ExtrasWidgetPickerBinding
import app.olauncher.databinding.ExtrasWidgetPickerCardBinding
import app.olauncher.databinding.ExtrasWidgetPickerHeaderBinding
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.search.DrawerExtras
import app.olauncher.extras.search.LetterIndex
import app.olauncher.helper.appLabelMatches
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showKeyboard

/**
 * The full-screen "Add widget" picker drawn over the widget panel: a search field, installed
 * widgets grouped under their app (tap an app to show its widgets as preview cards), a "Recent"
 * section and the drawer's A–Z strip. Tapping a card reports it via [onChosen].
 */
class WidgetPicker(
    private val binding: ExtrasWidgetPickerBinding,
    private val cellWidthDp: Float,
    private val onChosen: (AppWidgetProviderInfo) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val context: Context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private var groups: List<WidgetCatalog.AppGroup<AppWidgetProviderInfo>> = emptyList()
    private var recent: List<String> = emptyList()
    private var placed: Set<ComponentName> = emptySet()
    private val modes = HashMap<String, WidgetCatalog.Mode>()
    private var rows: List<WidgetCatalog.Row<AppWidgetProviderInfo>> = emptyList()
    private var table = LetterIndex.build(emptyList())
    private var lastJump = -1
    private val adapter = Adapter()
    private val cardPool = RecyclerView.RecycledViewPool()

    // Sideways row: ~2.4 cards fit, so the third one peeks out and shows there's more
    private val rowCardWidth: Int
    // Grid: as many ~170dp columns as fit (2 on a phone), filling the width
    private val columns: Int
    private val gridCardWidth: Int
    private val cardGap = (12 * density).toInt()

    /** The strip is on the left: headers leave room on that side instead. */
    private var headerOnLeft = false

    val isOpen get() = binding.root.isVisible

    init {
        // Screen minus the list's side padding (24dp on one side, 48dp next to the A–Z strip)
        val available = context.resources.displayMetrics.widthPixels - 72 * density
        rowCardWidth = (available / 2.4f).coerceIn(120 * density, 200 * density).toInt()
        columns = (available / density / 170).toInt().coerceAtLeast(2)
        gridCardWidth = ((available - cardGap * (columns - 1)) / columns).toInt()

        binding.pickerList.layoutManager = LinearLayoutManager(context)
        binding.pickerList.adapter = adapter
        binding.pickerList.itemAnimator = null
        binding.pickerList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) binding.pickerSearch.hideKeyboard()
            }
        })
        binding.pickerClose.setOnClickListener { close() }
        binding.pickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Search results start open again
                modes.clear()
                refresh(scrollToTop = true)
            }
        })
        binding.pickerSearch.setOnEditorActionListener { v, _, _ -> v.hideKeyboard(); true }
        setUpScroller()
        // Keep the list's end clear of the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, v.paddingTop, bars.right, bars.bottom)
            insets
        }
    }

    /**
     * Opens at once and fills in when the installed widgets are read (labels of every widget and app: slow, so
     * in the background). [onNone] runs, and the picker closes, when no app offers a home-screen widget.
     */
    fun open(manager: AppWidgetManager, placedProviders: Set<ComponentName>, recentApps: List<String>, onNone: () -> Unit) {
        groups = emptyList()
        loaded = false
        placed = placedProviders
        recent = recentApps
        modes.clear()
        binding.pickerSearch.setText("")
        refresh(scrollToTop = true)
        binding.root.fadeVisible(true)
        ViewCompat.requestApplyInsets(binding.root)

        val opening = ++opens
        val pm = context.applicationContext.packageManager
        loader.execute {
            val entries = try {
                val labels = HashMap<String, String>()
                fun appLabel(pkg: String) = labels.getOrPut(pkg) {
                    try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                }
                manager.installedProviders
                    .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
                    .map { info ->
                        val pkg = info.provider.packageName
                        WidgetCatalog.Entry(info, pkg, appLabel(pkg), info.loadLabel(pm)?.toString().orEmpty())
                    }
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                if (opening != opens || !isOpen) return@post
                if (entries.isEmpty()) {
                    close()
                    return@post onNone()
                }
                groups = WidgetCatalog.group(entries)
                loaded = true
                refresh(scrollToTop = true)
            }
        }
    }

    private var loaded = false
    /** Counts opens, so a slow read for an earlier open doesn't fill a later one. */
    private var opens = 0

    fun close() {
        if (!isOpen) return
        binding.pickerSearch.hideKeyboard()
        binding.root.fadeVisible(false)
        onClosed()
    }

    private fun query() = binding.pickerSearch.text?.toString().orEmpty()

    private fun refresh(scrollToTop: Boolean = false) {
        val q = query()
        val searching = q.isNotBlank()
        val shown = WidgetCatalog.filter(groups, q) { label, query -> appLabelMatches(label, query) }
        rows = WidgetCatalog.rows(
            shown, modes, searching, recent,
            context.getString(R.string.widgets_picker_recent), context.getString(R.string.widgets_picker_all),
            columns,
        )
        table = LetterIndex.build(WidgetCatalog.letterLabels(rows))
        binding.pickerScroller.setPresent(table.present)
        binding.pickerEmpty.isVisible = loaded && rows.isEmpty()
        adapter.notifyDataSetChanged()
        if (scrollToTop) (binding.pickerList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
    }

    /** Title tap: closed → row → grid → closed (row ↔ grid while searching). */
    private fun toggle(row: WidgetCatalog.Row.Header<AppWidgetProviderInfo>) {
        modes[WidgetCatalog.expandKey(row)] = WidgetCatalog.nextMode(row, query().isNotBlank())
        refresh()
    }

    // ---- A–Z strip: same side and height settings as the app list ----

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
        val onLeft = Gravity.getAbsoluteGravity(lp.gravity, binding.pickerList.layoutDirection) and
            Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
        headerOnLeft = onLeft

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
    private val startPad get() = dp(if (headerOnLeft) 48 else 24)
    private val endPad get() = dp(if (headerOnLeft) 24 else 48)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is WidgetCatalog.Row.Section -> TYPE_SECTION
            is WidgetCatalog.Row.Header -> TYPE_HEADER
            is WidgetCatalog.Row.Widgets -> TYPE_WIDGETS
            is WidgetCatalog.Row.Cards -> TYPE_CARDS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION -> object : RecyclerView.ViewHolder(
                    TextView(parent.context, null, 0, R.style.TextSmallLight).apply {
                        textSize = 13f
                        isAllCaps = true
                        setPadding(dp(24), dp(16), dp(24), dp(4))
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }) {}
                TYPE_HEADER -> HeaderHolder(ExtrasWidgetPickerHeaderBinding.inflate(inflater, parent, false))
                TYPE_WIDGETS -> WidgetsHolder(parent.context)
                else -> CardsHolder(parent.context)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is WidgetCatalog.Row.Section -> (holder.itemView as TextView).text = row.title
                is WidgetCatalog.Row.Header -> (holder as HeaderHolder).bind(row)
                is WidgetCatalog.Row.Widgets -> (holder as WidgetsHolder).bind(row.group)
                is WidgetCatalog.Row.Cards -> (holder as CardsHolder).bind(row.widgets)
            }
        }
    }

    private inner class HeaderHolder(val b: ExtrasWidgetPickerHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            if (headerOnLeft) b.root.setPaddingRelative(dp(48), b.root.paddingTop, dp(24), b.root.paddingBottom)
        }

        fun bind(row: WidgetCatalog.Row.Header<AppWidgetProviderInfo>) {
            val count = row.group.widgets.size
            b.headerLabel.text = row.group.appLabel
            AppIcons.decorate(b.headerLabel, row.group.packageName, null, Process.myUserHandle())
            b.headerCount.text = context.resources.getQuantityString(R.plurals.widgets_picker_count, count, count)
            // A single widget has nothing more to show
            val fixed = count == 1
            b.headerChevron.text = when {
                fixed -> ""
                row.mode == WidgetCatalog.Mode.CLOSED -> "▾"
                row.mode == WidgetCatalog.Mode.ROW -> "▦"
                else -> "▴"
            }
            b.root.isClickable = !fixed
            b.root.setOnClickListener(if (fixed) null else View.OnClickListener { toggle(row) })
            val action = when (row.mode) {
                WidgetCatalog.Mode.CLOSED -> R.string.widgets_picker_show
                WidgetCatalog.Mode.ROW -> R.string.widgets_picker_show_all
                WidgetCatalog.Mode.GRID -> R.string.widgets_picker_hide
            }
            b.root.contentDescription = "${row.group.appLabel}, ${b.headerCount.text}" +
                if (fixed) "" else ", ${context.getString(action)}"
        }
    }

    /** One app's widgets side by side; an edge fades and a ‹ / › button shows while more are that way. */
    private inner class WidgetsHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)) {
        private val cards = CardAdapter()
        private val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false).apply { initialPrefetchItemCount = 3 }
            setRecycledViewPool(cardPool)
            // Cards stop short of the A–Z strip instead of sliding under its letters
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setPaddingRelative(startPad, dp(4), endPad, dp(12))
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            itemAnimator = null
            adapter = cards
        }
        private fun arrow(forward: Boolean) = TextView(context, null, 0, R.style.TextSmallBold).apply {
            // Arrows point the way the list moves, flipped in right-to-left languages
            val rtl = list.layoutDirection == View.LAYOUT_DIRECTION_RTL
            text = if (forward != rtl) "›" else "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            contentDescription = context.getString(if (forward) R.string.widgets_picker_more else R.string.widgets_picker_back)
            val page = (context.resources.displayMetrics.widthPixels * 0.6f).toInt()
            setOnClickListener { list.smoothScrollBy(if (forward != rtl) page else -page, 0) }
        }
        private val more = arrow(forward = true)
        private val less = arrow(forward = false)

        init {
            val root = itemView as FrameLayout
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val size = dp(40)
            val top = dp(4) + (previewHeight(rowCardWidth) - size) / 2
            root.addView(more, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.TOP).apply {
                // Over the peeking card, centred on the previews
                marginEnd = endPad - dp(8)
                topMargin = top
            })
            root.addView(less, FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.TOP).apply {
                // Over the card scrolled half out at the start
                marginStart = startPad - dp(8)
                topMargin = top
            })
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateMore()
            })
            list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMore() }
        }

        private fun updateMore() {
            val rtl = list.layoutDirection == View.LAYOUT_DIRECTION_RTL
            more.isVisible = list.canScrollHorizontally(if (rtl) -1 else 1)
            less.isVisible = list.canScrollHorizontally(if (rtl) 1 else -1)
        }

        fun bind(group: WidgetCatalog.AppGroup<AppWidgetProviderInfo>) {
            cards.items = group.widgets
            cards.notifyDataSetChanged()
            list.scrollToPosition(0)
            list.post { updateMore() }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        var items: List<WidgetCatalog.Entry<AppWidgetProviderInfo>> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(Card(parent, rowCardWidth))
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.card.bind(items[position])
    }

    private inner class CardHolder(val card: Card) : RecyclerView.ViewHolder(card.b.root)

    /** One line of an app's widget grid: [columns] card slots, the unused ones hidden. */
    private inner class CardsHolder(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val slots: List<Card>

        init {
            val line = itemView as LinearLayout
            line.orientation = LinearLayout.HORIZONTAL
            line.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            line.setPaddingRelative(startPad, dp(4), endPad, dp(8))
            slots = List(columns) { i ->
                Card(line, gridCardWidth).also { card ->
                    (card.b.root.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = if (i < columns - 1) cardGap else 0
                    line.addView(card.b.root)
                }
            }
        }

        fun bind(widgets: List<WidgetCatalog.Entry<AppWidgetProviderInfo>>) {
            slots.forEachIndexed { i, card ->
                val entry = widgets.getOrNull(i)
                // Hidden slots keep their width so a short last line stays aligned
                card.b.root.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
                if (entry != null) card.bind(entry)
            }
        }
    }

    private fun previewHeight(cardWidth: Int) = (cardWidth * 0.75f).toInt()

    /** A widget card [width] px wide: preview, name, size and description; tap to add it. */
    private inner class Card(parent: ViewGroup, private val width: Int) {
        val b = ExtrasWidgetPickerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        private val live = WidgetPreviews.ScaledFrame(context)
        private val height = previewHeight(width)

        init {
            b.root.layoutParams = b.root.layoutParams.apply { this.width = this@Card.width }
            b.cardPreviewArea.layoutParams = b.cardPreviewArea.layoutParams.apply { height = this@Card.height }
            b.cardPreviewArea.addView(live, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun bind(entry: WidgetCatalog.Entry<AppWidgetProviderInfo>) {
            val info = entry.provider
            val pad = b.cardPreviewArea.paddingStart * 2
            WidgetPreviews.bind(live, b.cardImage, info, cellWidthDp, width - pad, height - pad)
            b.cardName.text = entry.label
            val size = WidgetPreviews.sizeLabel(context, info, cellWidthDp)
            val description = WidgetPreviews.description(context, info)
            b.cardMeta.text = if (description != null) "$size · $description" else size
            b.cardAdded.isVisible = info.provider in placed
            b.root.contentDescription = "${entry.label}, $size"
            b.root.setOnClickListener {
                close()
                onChosen(info)
            }
        }
    }

    private companion object {
        val loader: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        const val TYPE_SECTION = 0
        const val TYPE_HEADER = 1
        const val TYPE_WIDGETS = 2
        const val TYPE_CARDS = 3
    }
}
