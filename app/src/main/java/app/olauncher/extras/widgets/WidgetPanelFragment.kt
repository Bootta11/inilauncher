package app.olauncher.extras.widgets

import app.olauncher.helper.fadeVisible
import app.olauncher.helper.beginTransition
import app.olauncher.helper.Motion
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.core.view.ViewCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.extras.ExtrasState
import app.olauncher.extras.ForegroundStart
import app.olauncher.extras.HomeButton
import app.olauncher.extras.drag.DragSession
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.databinding.FragmentExtrasWidgetsBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.BaseFragment

/** Full-screen panel of app widgets in a 4-column grid: add, remove, drag to reorder, resize by handles. */
class WidgetPanelFragment : BaseFragment() {

    private var _binding: FragmentExtrasWidgetsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: AppWidgetHost
    private lateinit var manager: AppWidgetManager
    private lateinit var store: WidgetStore
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** A widget's settings screen is open; the panel stays open behind it. */
    private var settingsOpen = false
    private val settingsClosed = Runnable { endSettings() }
    private var picker: WidgetPicker? = null
    private val closePicker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { picker?.close() }
    }

    private val closeEdit = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { _binding?.widgetEdit?.done() }
    }

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ExtrasState.awaitingSystemScreen = false
        if (result.resultCode == Activity.RESULT_OK) configureOrAdd(pendingId) else discardPending()
    }

    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ExtrasState.awaitingSystemScreen = false
        if (result.resultCode == Activity.RESULT_OK) addPending() else discardPending()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtrasWidgetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        manager = AppWidgetManager.getInstance(requireContext())
        host = WidgetHosting.host(requireContext())
        store = WidgetStore(requireContext())
        binding.widgetAdd.setOnClickListener { showProviderPicker() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closePicker)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closeEdit)
        binding.widgetEdit.bind(editHost)
        HomeButton.onPress(viewLifecycleOwner) { binding.widgetEdit.done() }
        render()
    }

    override fun onStart() {
        super.onStart()
        try { host.startListening() } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        // Some setup screens (Microsoft To Do) close and reopen themselves at once, which shows the
        // panel for a moment; only a panel that stays on screen means the settings are done
        if (settingsOpen) view?.postDelayed(settingsClosed, 700)
    }

    override fun onPause() {
        super.onPause()
        view?.removeCallbacks(settingsClosed)
    }

    private fun endSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        ExtrasState.awaitingSystemScreen = false
    }

    override fun onStop() {
        super.onStop()
        DragSession.of(binding.widgetContainer)?.stopNow()
        binding.widgetEdit.done()
        try { host.stopListening() } catch (e: Exception) { }
    }

    private fun render() {
        val grid = binding.widgetContainer
        // Added, removed or resized widgets slide/fade into their new places instead of jumping.
        // Not on the first draw: the panel opens already in its final state (no hint flashing,
        // no "Add widget" sliding down from under the title)
        val first = !grid.isLaidOut
        DragSession.of(grid)?.stopNow()
        if (!first) (grid.parent as ViewGroup).beginTransition(Motion.medium(requireContext()), excludeInside = grid.cells)
        // Drop widgets whose app was uninstalled
        val placed = store.all().filter { manager.getAppWidgetInfo(it.id) != null }
        if (placed.size != store.all().size) store.save(placed)
        if (first) binding.widgetEmpty.isVisible = placed.isEmpty()
        else binding.widgetEmpty.fadeVisible(placed.isEmpty())
        val ids = placed.map { it.id }.toSet()
        grid.cells.filter { grid.cell(it).id !in ids }.forEach { grid.removeView(it) }
        WidgetHosting.keepOnly(ids)

        val density = resources.displayMetrics.density
        val cellWidthDp = cellWidthDp()
        // Only what changed: cells that stay are the same views, so they don't blink
        val shown = grid.cells.associateBy { grid.cell(it).id }
        placed.forEachIndexed { index, widget ->
            val info = manager.getAppWidgetInfo(widget.id) ?: return@forEachIndexed
            val span = if (widget.span > 0) widget.span else autoSpan(info, cellWidthDp)
            val heightDp = if (widget.heightDp > 0) widget.heightDp else (info.minHeight / density).toInt().coerceAtLeast(WidgetGridLayout.MIN_HEIGHT_DP)
            val cell = shown[widget.id]
            if (cell == null) {
                grid.addView(newCell(widget.id, info), index.coerceAtMost(grid.childCount), WidgetGrid.Cell(widget.id, span, heightDp))
            } else {
                grid.cell(cell).span = span
                grid.cell(cell).heightDp = heightDp
                grid.indexOfChild(cell).let { if (it != index) grid.moveChild(it, index.coerceAtMost(grid.childCount - 1)) }
            }
        }
        grid.cells.forEach { tellSize(it) }
        grid.requestLayout()
    }

    /** Grid width = screen minus the panel's side padding; each column the same width. */
    private fun cellWidthDp(): Float {
        val container = binding.widgetContainer
        val gridWidthPx = resources.displayMetrics.widthPixels - container.paddingStart - container.paddingEnd -
            (container.parent as View).let { it.paddingStart + it.paddingEnd }
        return gridWidthPx / resources.displayMetrics.density / WidgetStore.COLUMNS
    }

    /** One grid cell: the widget. Hold and drag moves it; hold and let go shows the resize frame. */
    private fun newCell(id: Int, info: AppWidgetProviderInfo): View {
        val half = (4 * resources.displayMetrics.density).toInt()
        val view = WidgetHosting.view(requireContext(), id, info)
        return WidgetTouchFrame(requireContext()).apply {
            setPadding(half, 0, half, 0)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            startDrag = { startDrag(this) }
            onHold = { if (_binding != null) binding.widgetEdit.edit(this) }
            // TalkBack can't hold through the widget's own views or drag; offer a menu as an action
            ViewCompat.addAccessibilityAction(this, getString(R.string.widgets_edit)) { _, _ ->
                showEditMenu(this)
                true
            }
        }
    }

    private fun startDrag(cell: View): DragSession? {
        val grid = _binding?.widgetContainer ?: return null
        if (DragSession.of(grid) != null || cell.parent !== grid) return null
        return DragSession(
            grid, cell, (0 until grid.childCount).toList(), grid::slots, liftScale = 1.02f,
            onDrop = { from, to ->
                // The cells are already where they belong; only what's below the grid still moves
                (grid.parent as ViewGroup).beginTransition(exclude = null, excludeInside = listOf(grid))
                grid.moveChild(from, to)
                store.moveTo(grid.cell(cell).id, to)
            },
        )
    }

    private fun tellSize(cell: View) {
        val params = binding.widgetContainer.cell(cell)
        val view = (cell as ViewGroup).getChildAt(0) as? AppWidgetHostView ?: return
        val widthDp = params.span * cellWidthDp() - 8
        val heightDp = params.heightDp.toFloat()
        if (WidgetHosting.sizeChanged(params.id, widthDp, heightDp)) tellSize(view, widthDp, heightDp)
    }

    /** What the resize frame needs from the panel. */
    private val editHost = object : WidgetEditOverlay.Host {
        override val scroll get() = binding.widgetScroll
        override val grid get() = binding.widgetContainer
        override fun hasSettings(cell: View) = manager.getAppWidgetInfo(grid.cell(cell).id)?.configure != null
        override fun openSettings(cell: View) {
            binding.widgetEdit.done()
            manager.getAppWidgetInfo(grid.cell(cell).id)?.let { openSettings(grid.cell(cell).id, it) }
        }
        override fun remove(cell: View) {
            binding.widgetEdit.done()
            removeWidget(grid.cell(cell).id)
            render()
        }
        override fun resized(cell: View) {
            val params = grid.cell(cell)
            store.resize(params.id, params.span, params.heightDp)
            tellSize(cell)
        }
        override fun startDrag(cell: View) = this@WidgetPanelFragment.startDrag(cell)
        override fun onEditing(editing: Boolean) { closeEdit.isEnabled = editing }
    }

    private fun removeWidget(id: Int) {
        store.remove(id)
        WidgetHosting.forget(id)
        host.deleteAppWidgetId(id)
    }

    private fun autoSpan(info: AppWidgetProviderInfo, cellWidthDp: Float): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0)
            return info.targetCellWidth.coerceIn(1, WidgetStore.COLUMNS)
        return WidgetStore.autoSpan(info.minWidth / resources.displayMetrics.density, cellWidthDp)
    }

    /** Lets the widget pick the layout that fits its cell instead of guessing. */
    @Suppress("DEPRECATION")
    private fun tellSize(view: AppWidgetHostView, widthDp: Float, heightDp: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            } else {
                view.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
            }
        } catch (e: Exception) { }
    }

    private fun showProviderPicker() {
        binding.widgetEdit.done()
        val picker = picker ?: WidgetPicker(binding.widgetPicker, cellWidthDp(), onChosen = { startAdding(it) }, onClosed = {
            closePicker.isEnabled = false
            if (_binding != null) binding.widgetScroll.fadeVisible(true)
        }).also { picker = it }
        val placedProviders = store.all().mapNotNull { manager.getAppWidgetInfo(it.id)?.provider }.toSet()
        picker.open(manager, placedProviders, store.recentApps()) {
            context?.let { it.showToast(it.getString(R.string.widgets_none_installed)) }
        }
        binding.widgetScroll.fadeVisible(false)
        closePicker.isEnabled = true
    }

    private fun startAdding(info: AppWidgetProviderInfo) {
        pendingId = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(pendingId, info.provider)) {
            configureOrAdd(pendingId)
        } else {
            // Android asks you once per app whether Inilauncher may show its widgets
            ExtrasState.awaitingSystemScreen = true
            bindLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            })
        }
    }

    private fun configureOrAdd(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return discardPending()
        if (info.configure == null) return addPending()
        try {
            ExtrasState.awaitingSystemScreen = true
            configureLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            })
        } catch (e: Exception) {
            ExtrasState.awaitingSystemScreen = false
            // Some widgets' setup screens can't be opened by other launchers; add it as is
            addPending()
        }
    }

    private fun addPending() {
        if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        store.add(pendingId)
        manager.getAppWidgetInfo(pendingId)?.provider?.packageName?.let { store.addRecentApp(it) }
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (_binding != null) render()
    }

    private fun discardPending() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    /** Move, resize, settings and remove as a menu, for TalkBack. */
    private fun showEditMenu(cell: View) {
        val (id, span, heightDp) = binding.widgetContainer.cell(cell).let { Triple(it.id, it.span, it.heightDp) }
        val info = manager.getAppWidgetInfo(id) ?: return
        val actions = buildList<Pair<Int, () -> Unit>> {
            // Only widgets with a setup screen have settings
            if (info.configure != null) add(R.string.widgets_settings to { openSettings(id, info) })
            add(R.string.widgets_taller to { store.setHeight(id, heightDp + 40) })
            add(R.string.widgets_shorter to { store.setHeight(id, (heightDp - 40).coerceAtLeast(WidgetGridLayout.MIN_HEIGHT_DP)) })
            add(R.string.widgets_wider to { store.setSpan(id, span + 1) })
            add(R.string.widgets_narrower to { store.setSpan(id, span - 1) })
            add(R.string.folder_move_up to { store.move(id, -1) })
            add(R.string.folder_move_down to { store.move(id, 1) })
            add(R.string.widgets_remove to { removeWidget(id) })
        }
        listDialog(getString(R.string.widgets_edit), actions.map { getString(it.first) }) { index ->
            actions[index].second()
            if (_binding != null) render()
        }
    }

    /**
     * Opens the widget's own setup screen again (e.g. to pick another list). Goes through Android
     * first, which can open setup screens other apps can't start directly; Android 14+ blocks that
     * unless the launcher lends its right to start it ([ForegroundStart]). The widget redraws itself
     * when you're done; Back or cancel keeps it as it was.
     */
    private fun openSettings(id: Int, info: AppWidgetProviderInfo) {
        ExtrasState.awaitingSystemScreen = true
        settingsOpen = true
        try {
            host.startAppWidgetConfigureActivityForResult(requireActivity(), id, 0, REQUEST_SETTINGS, ForegroundStart.options())
        } catch (e: Exception) {
            try {
                startActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                })
            } catch (e: Exception) {
                endSettings()
                requireContext().showToast(getString(R.string.widgets_settings_failed))
            }
        }
    }

    private fun listDialog(title: String, options: List<String>, onChosen: (Int) -> Unit) {
        val context = requireContext()
        if (options.isEmpty()) return context.showToast(getString(R.string.widgets_none_installed))
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.text = title
        b.ivClose.setOnClickListener { dialog.dismiss() }
        b.tvAction.setText(R.string.okay)
        b.tvAction.setOnClickListener { dialog.dismiss() }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pad = (10 * resources.displayMetrics.density).toInt()
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
                if (options.size > 8) (resources.displayMetrics.heightPixels * 0.5).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        b.contentContainer.isVisible = true
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
    }

    private companion object {
        /** The result isn't needed: the widget updates itself. MainActivity ignores this code. */
        const val REQUEST_SETTINGS = 0x1A2
    }

    override fun onDestroyView() {
        endSettings()
        super.onDestroyView()
        picker = null
        _binding = null
    }
}
