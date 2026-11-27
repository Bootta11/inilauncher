package app.olauncher.extras.sublist

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.extras.drag.DragSession
import app.olauncher.extras.drag.HoldDrag
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.notifications.BadgedIcon
import app.olauncher.extras.notifications.NotificationDots
import app.olauncher.extras.privacy.HiddenApps
import app.olauncher.extras.search.LaunchHistory
import app.olauncher.extras.search.UsageSort
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.beginTransition
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sub list under the home apps (layout/extras_home_sublist.xml): a small button that opens a
 * wrapping row of apps you use often but don't want in the main list. Bound from
 * HomeExtras.decorateHomeApps, so it follows the same refreshes (resume, reorder, notifications).
 * Open/closed lives in the views, so every new home view starts closed; it also closes when the
 * home screen stops and after an app is opened from it. While open, the home apps keep their place
 * (see [open]) instead of re-centring into the clock block.
 */
object SubList {

    /** One app shown: picked by you, or added by "Add most-used apps" ([auto]). */
    data class Entry(val app: FolderApp, val user: UserHandle, val auto: Boolean)

    private const val USAGE_DAYS = 30
    private const val RANK_TTL = 30 * 60 * 1000L
    private const val PEEK_ICONS = 4

    /** Launchable packages by use (main profile), refreshed at most every [RANK_TTL]. */
    @Volatile private var ranked: List<String> = emptyList()
    private var rankedAt = 0L
    private var ranking = false

    fun isEnabled(context: Context) = SubListStore(context).enabled

    /**
     * With "Add most-used apps" on: the most-used apps it would add (not on the home screen, not
     * "Don't suggest"), for the Choose apps picker. Empty until the first count has run.
     */
    fun mostUsed(context: Context, limit: Int = SubListEntries.FILL_TO): List<String> {
        val store = SubListStore(context)
        if (!autoOn(context, store)) return emptyList()
        return SubListEntries.autoPackages(emptyList(), ranked, exclusions(context, store), fillTo = limit)
    }

    private fun exclusions(context: Context, store: SubListStore): Set<String> {
        val prefs = Prefs(context)
        val home = (1..prefs.homeAppsNum.coerceIn(0, 8)).map { prefs.getAppPackage(it) }.filter { it.isNotEmpty() }
        // Hidden apps (main profile) as well, also ones hidden since the last ranking
        val me = "|" + Process.myUserHandle()
        val hidden = HiddenApps.hiddenSet(context).filter { it.endsWith(me) }.map { it.removeSuffix(me) }
        return home.toSet() + store.noSuggest() + hidden + context.packageName
    }

    fun bind(binding: FragmentHomeBinding) {
        val ui = binding.extrasSubList
        // Not while an app is being dragged: its end binds again
        if (DragSession.of(ui.extrasSubListItems) != null) {
            ui.extrasSubListItems.setTag(R.id.extras_sublist_bind_waits, true)
            return
        }
        val context = binding.root.context
        val store = SubListStore(context)
        ui.root.isVisible = store.enabled
        if (!store.enabled) {
            close(binding)
            return
        }
        val alignment = Prefs(context).homeAlignment and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        ui.root.gravity = alignment
        ui.extrasSubListItems.alignment = alignment

        val entries = entries(context, store)
        if (entries.isEmpty() && ui.extrasSubListScroll.isVisible) {
            binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton)
            close(binding)
        }
        val color = textColor(context)
        bindItems(binding, store, entries, color)
        applyGrid(binding, store, entries)
        bindButton(binding, store, entries, color)
        // Items changed while open (notifications, menu): fit them again
        if (ui.extrasSubListScroll.isVisible) binding.homeAppsLayout.post {
            if (!binding.root.isAttachedToWindow) return@post
            binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton)
            place(binding)
        }
        refreshRanking(binding, store)
    }

    /** Closes it when the home screen stops (an app opens, home is left, the screen turns off). */
    fun observe(binding: FragmentHomeBinding, owner: LifecycleOwner) {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) close(binding)
        })
    }

    /** A tap on the home screen outside the sub list's apps: closes it if open. True when it did. */
    fun closeOnTapOutside(binding: FragmentHomeBinding): Boolean {
        val ui = binding.extrasSubList
        if (!ui.root.isVisible || !ui.extrasSubListScroll.isVisible) return false
        binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton)
        close(binding)
        val store = SubListStore(binding.root.context)
        bindButton(binding, store, entries(binding.root.context, store), textColor(binding.root.context))
        return true
    }

    private fun toggle(binding: FragmentHomeBinding) {
        val ui = binding.extrasSubList
        val context = binding.root.context
        if (ui.extrasSubListItems.childCount == 0) {
            context.showToast(context.getString(R.string.sublist_empty_hint))
            return
        }
        // The button is rebuilt below; animating it would fade it out and back in (a blink)
        binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton)
        if (ui.extrasSubListScroll.isVisible) close(binding) else open(binding)
        // Only the button changes (its dot shows only while closed); the items stay as they are
        val store = SubListStore(context)
        bindButton(binding, store, entries(context, store), textColor(context))
    }

    private fun launch(binding: FragmentHomeBinding, entry: Entry) {
        close(binding)
        Folders.launch(binding.root.context, entry.app)
    }

    // ---- Fitting it on the screen ----

    /** The home apps' place before opening: the block's top and bottom, and the layout's own padding and gravity. */
    private class Anchor(val top: Int, val bottom: Int, val paddingTop: Int, val gravity: Int)

    /**
     * Upstream centres the home apps, so a growing sub list would push them up into the clock,
     * note and notifications. Instead the apps stay where they were and the list opens below them.
     */
    private fun open(binding: FragmentHomeBinding) {
        val layout = binding.homeAppsLayout
        val first = (0 until layout.childCount).map { layout.getChildAt(it) }.firstOrNull { it.isVisible } ?: return
        if (layout.getTag(R.id.extras_sublist_anchor) == null)
            layout.setTag(R.id.extras_sublist_anchor, Anchor(first.top, binding.extrasSubList.root.bottom, layout.paddingTop, layout.gravity))
        binding.extrasSubList.extrasSubListScroll.isVisible = true
        place(binding)
    }

    /**
     * Uses the room below the apps first; only if that's not enough, moves the apps up into the free
     * space under the clock block (never over it). Whatever still doesn't fit scrolls.
     */
    private fun place(binding: FragmentHomeBinding) {
        val layout = binding.homeAppsLayout
        val anchor = layout.getTag(R.id.extras_sublist_anchor) as? Anchor ?: return
        val ui = binding.extrasSubList
        val width = layout.width - layout.paddingLeft - layout.paddingRight
        if (width <= 0) return
        ui.extrasSubListItems.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val wanted = ui.extrasSubListItems.measuredHeight
        val below = layout.height - layout.paddingBottom - anchor.bottom
        val ceiling = if (binding.dateTimeLayout.isVisible) binding.dateTimeLayout.bottom + dp(layout.context, 16) else anchor.paddingTop
        val above = (anchor.top - ceiling).coerceAtLeast(0)
        val shift = (wanted - below).coerceIn(0, above)
        layout.gravity = (anchor.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) or Gravity.TOP
        layout.setPadding(layout.paddingLeft, anchor.top - shift, layout.paddingRight, layout.paddingBottom)
        ui.extrasSubListScroll.maxHeight = (below + shift).coerceAtLeast(dp(layout.context, 48))
    }

    /** Closes it and gives the home apps upstream's layout back. */
    private fun close(binding: FragmentHomeBinding) {
        DragSession.of(binding.extrasSubList.extrasSubListItems)?.stopNow()
        binding.extrasSubList.extrasSubListScroll.isVisible = false
        val layout = binding.homeAppsLayout
        val anchor = layout.getTag(R.id.extras_sublist_anchor) as? Anchor ?: return
        layout.setTag(R.id.extras_sublist_anchor, null)
        layout.gravity = anchor.gravity
        layout.setPadding(layout.paddingLeft, anchor.paddingTop, layout.paddingRight, layout.paddingBottom)
    }

    // ---- Entries ----

    private fun entries(context: Context, store: SubListStore): List<Entry> {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        // Not installed (or mid-update): skipped, but kept, so it comes back
        val picked = store.apps().mapNotNull { app ->
            val user = Folders.userOf(context, app)
            if (installed(launcher, app.packageName, user)) Entry(app, user, auto = false) else null
        }
        if (!autoOn(context, store)) return picked
        val exclude = exclusions(context, store)
        val me = Process.myUserHandle()
        val serial = (context.getSystemService(Context.USER_SERVICE) as UserManager).getSerialNumberForUser(me)
        val auto = SubListEntries.autoPackages(picked.map { it.app.packageName }, ranked, exclude).mapNotNull { pkg ->
            val info = try {
                launcher.getActivityList(pkg, me).firstOrNull()
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            Entry(FolderApp(info.label.toString(), pkg, info.componentName.className, serial), me, auto = true)
        }
        return picked + auto
    }

    private fun autoOn(context: Context, store: SubListStore) =
        store.auto && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.appUsagePermissionGranted()

    private fun installed(launcher: LauncherApps, packageName: String, user: UserHandle) = try {
        launcher.getActivityList(packageName, user).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** Counts launches off the main thread, then binds again if the order changed. */
    private fun refreshRanking(binding: FragmentHomeBinding, store: SubListStore) {
        val context = binding.root.context
        if (!autoOn(context, store) || ranking || System.currentTimeMillis() - rankedAt < RANK_TTL) return
        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        val app = context.applicationContext
        ranking = true
        owner.lifecycleScope.launch {
            try {
                val fresh = withContext(Dispatchers.Default) { rankLaunchable(app) }
                rankedAt = System.currentTimeMillis()
                if (fresh != ranked) {
                    ranked = fresh
                    if (binding.root.isAttachedToWindow) bind(binding)
                }
            } catch (e: Exception) {
                // Keep the last ranking; try again on the next bind
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                ranking = false
            }
        }
    }

    private fun rankLaunchable(context: Context): List<String> {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val me = Process.myUserHandle()
        // Hidden apps never land on the home screen through here, lock or not
        val hidden = HiddenApps.hiddenSet(context)
        return SubListEntries.rank(UsageSort.counts(LaunchHistory.launches(context, USAGE_DAYS)))
            .asSequence()
            .filter { it != context.packageName && HiddenApps.key(it, me) !in hidden && installed(launcher, it, me) }
            .take(SubListEntries.FILL_TO * 3)
            .toList()
    }

    // ---- Dragging ----

    private fun shownEntries(flow: SubListFlow) = (0 until flow.childCount).mapNotNull { flow.getChildAt(it).tag as? Entry }

    /**
     * Where the app at [from] may go: your own apps among themselves; a most-used one (after them
     * all) anywhere among them, which keeps it.
     */
    private fun candidates(flow: SubListFlow, from: Int): List<Int> {
        val entries = shownEntries(flow)
        val picked = entries.count { !it.auto }
        return (if (entries.getOrNull(from)?.auto == true) 0..picked else 0 until picked).toList()
    }

    private fun cellKey(entry: Entry) = SubListStore.cellKey(entry.app)

    /** Free placement on: every shown app gets its cell; off: back to one after the other. */
    private fun applyGrid(binding: FragmentHomeBinding, store: SubListStore, entries: List<Entry>) {
        val ui = binding.extrasSubList
        val flow = ui.extrasSubListItems
        val context = binding.root.context
        val keys = if (store.freePlacement) entries.map(::cellKey) else null
        val saved = if (keys != null) store.cells() else emptyMap()
        flow.cellWidth = context.resources.getDimensionPixelSize(
            when (store.style) {
                SubListStore.STYLE_NAMES -> R.dimen.extras_sublist_cell_name
                SubListStore.STYLE_ICONS_NAMES -> R.dimen.extras_sublist_cell_named
                else -> R.dimen.extras_sublist_cell_icon
            }
        )
        if (flow.sameGrid(keys, saved)) return
        // Apps move to their cells (or back into a row) instead of jumping
        if (flow.isLaidOut && ui.extrasSubListScroll.isVisible) binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton)
        flow.setGrid(keys, saved)
    }

    /** The shown entries in the order they read: by cell with free placement. */
    private fun readingOrder(flow: SubListFlow, entries: List<Entry>): List<Entry> =
        if (!flow.isGrid || flow.cells.size != entries.size) entries
        else SubListGrid.readingOrder(flow.cells).map { entries[it] }

    /**
     * Free placement: drops it on any cell (one row more than shown while dragging, for a new
     * row); an app already there swaps with it.
     */
    private fun startGridDrag(binding: FragmentHomeBinding, view: View, from: Int): DragSession {
        val ui = binding.extrasSubList
        val flow = ui.extrasSubListItems
        val scroll = ui.extrasSubListScroll
        val cells = flow.cells
        val columns = flow.columns
        val scrolls = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
        flow.clipChildren = false
        scroll.clipChildren = scrolls
        fun resize(extra: Boolean) {
            if (flow.extraRow == extra) return
            // Only the list's height animates; the apps are placed by the drag itself
            binding.homeAppsLayout.beginTransition(exclude = ui.extrasSubListButton, excludeInside = listOf(flow))
            flow.extraRow = extra
        }
        return DragSession(
            flow, view, (0 until (SubListGrid.rows(cells, columns) + 1) * columns).toList(), { flow.slotsForCells(cells) },
            keepInside = true,
            onDrop = { _, to -> moveCell(binding, from, to) },
            onEnd = {
                resize(false)
                if (flow.getTag(R.id.extras_sublist_bind_waits) == true) {
                    flow.setTag(R.id.extras_sublist_bind_waits, null)
                    flow.doOnNextLayout { it.post { if (binding.root.isAttachedToWindow) bind(binding) } }
                    flow.requestLayout()
                }
            },
            start = cells[from],
            arrange = { to -> flow.slotsForCells(SubListGrid.swap(cells, from, to)) },
            rowWeight = 1f,
            onLift = { resize(true) },
        )
    }

    /**
     * Free placement: the app shown at [from] goes to cell [to] (swapping with an app there).
     * Saves every shown app's cell, so they all stay where they are now; a most-used app moved
     * becomes yours. Nothing is rebuilt.
     */
    private fun moveCell(binding: FragmentHomeBinding, from: Int, to: Int) {
        val flow = binding.extrasSubList.extrasSubListItems
        val context = flow.context
        val store = SubListStore(context)
        val view = flow.getChildAt(from) ?: return
        val entry = view.tag as? Entry ?: return
        val height = flow.height
        val columns = flow.columns
        val cells = SubListGrid.swap(flow.cells, from, to)
        val shown = shownEntries(flow)
        val keep = store.apps().map(SubListStore::cellKey).toSet() + shown.map(::cellKey)
        store.setCells(
            store.cells().filterKeys { it in keep } +
                shown.mapIndexed { i, e -> cellKey(e) to SubListGrid.Cell(cells[i] / columns, cells[i] % columns) }
        )
        if (entry.auto) {
            // Picked apps come first, so its view goes after them
            store.add(entry.app)
            flow.moveChild(from, shown.count { !it.auto })
            view.tag = entry.copy(auto = false)
        }
        val entries = shownEntries(flow)
        flow.setGrid(entries.map(::cellKey), store.cells())
        val color = textColor(context)
        flow.setTag(R.id.extras_sublist_items_shown, itemsKey(context, store, entries, color))
        bindButton(binding, store, entries, color) // its peek shows the first apps
        flow.doOnNextLayout {
            if (it.height != height) it.post {
                if (!binding.root.isAttachedToWindow || !binding.extrasSubList.extrasSubListScroll.isVisible) return@post
                binding.homeAppsLayout.beginTransition(exclude = binding.extrasSubList.extrasSubListButton)
                place(binding)
            }
        }
    }

    private fun startDrag(binding: FragmentHomeBinding, view: View): DragSession? {
        val flow = binding.extrasSubList.extrasSubListItems
        val scroll = binding.extrasSubList.extrasSubListScroll
        val from = flow.indexOfChild(view)
        if (from < 0 || DragSession.of(flow) != null) return null
        if (flow.isGrid && flow.cells.size == flow.childCount) return startGridDrag(binding, view, from)
        // The lifted app may stick out a little, unless scrolled-away rows would show too
        val scrolls = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
        flow.clipChildren = false
        scroll.clipChildren = scrolls
        return DragSession(
            flow, view, candidates(flow, from), flow::slots, keepInside = true,
            onDrop = { a, b -> moveItem(binding, a, b) },
            onEnd = {
                if (flow.getTag(R.id.extras_sublist_bind_waits) == true) {
                    flow.setTag(R.id.extras_sublist_bind_waits, null)
                    // After the drop is laid out, or its transition would start from the old places
                    flow.doOnNextLayout { it.post { if (binding.root.isAttachedToWindow) bind(binding) } }
                    flow.requestLayout()
                }
            },
        )
    }

    /** Saves the app shown at [from] to [to] and moves its view there, without rebuilding the others. */
    private fun moveItem(binding: FragmentHomeBinding, from: Int, to: Int) {
        val flow = binding.extrasSubList.extrasSubListItems
        val context = flow.context
        val store = SubListStore(context)
        val view = flow.getChildAt(from) ?: return
        val entry = view.tag as? Entry ?: return
        val height = flow.height
        flow.moveChild(from, to)
        view.tag = entry.copy(auto = false)
        val entries = shownEntries(flow)
        store.place(entry.app, entries.drop(to + 1).firstOrNull { !it.auto }?.app)
        val color = textColor(context)
        flow.setTag(R.id.extras_sublist_items_shown, itemsKey(context, store, entries, color))
        bindButton(binding, store, entries, color) // its peek shows the first apps
        // Names of other widths may need another row
        flow.doOnNextLayout {
            if (it.height != height) it.post {
                if (!binding.root.isAttachedToWindow || !binding.extrasSubList.extrasSubListScroll.isVisible) return@post
                binding.homeAppsLayout.beginTransition(exclude = binding.extrasSubList.extrasSubListButton)
                place(binding)
            }
        }
    }

    /** One place earlier or later, for TalkBack. */
    private fun moveBy(binding: FragmentHomeBinding, view: View, by: Int): Boolean {
        val flow = binding.extrasSubList.extrasSubListItems
        val from = flow.indexOfChild(view)
        if (from < 0 || DragSession.of(flow) != null) return false
        if (flow.isGrid && flow.cells.size == flow.childCount) {
            // The cell before or after (swapping with an app there)
            val to = flow.cells[from] + by
            if (to < 0) return false
            moveCell(binding, from, to)
            return true
        }
        if (from + by !in candidates(flow, from)) return false
        moveItem(binding, from, from + by)
        return true
    }

    // ---- Views ----

    private fun bindButton(binding: FragmentHomeBinding, store: SubListStore, entries: List<Entry>, color: Int) {
        val ui = binding.extrasSubList
        val context = binding.root.context
        val button = ui.extrasSubListButton
        button.removeAllViews()
        val style = if (entries.isEmpty() && store.button == SubListStore.BUTTON_PEEK) SubListStore.BUTTON_DOTS else store.button
        when (style) {
            SubListStore.BUTTON_GRID -> button.addView(ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_extras_sublist)?.mutate()?.apply { setTint(color) })
                val size = dp(context, 20)
                layoutParams = LinearLayout.LayoutParams(size, size)
            })
            SubListStore.BUTTON_COUNT -> button.addView(label(context, context.getString(R.string.sublist_count, entries.size)))
            SubListStore.BUTTON_PEEK -> {
                val size = context.resources.getDimensionPixelSize(R.dimen.extras_sublist_icon_peek)
                readingOrder(ui.extrasSubListItems, entries).take(PEEK_ICONS).forEach { entry ->
                    button.addView(ImageView(context).apply {
                        setImageDrawable(icon(context, entry, size, color))
                        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, 4) }
                    })
                }
                button.addView(label(context, context.getString(R.string.sublist_peek_more)))
            }
            else -> button.addView(label(context, context.getString(R.string.sublist_dots)).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
        }
        if (!ui.extrasSubListScroll.isVisible) {
            val total = entries.sumOf { NotificationDots.count(context, it.app.packageName, it.app.activityClassName) }
            val dot = NotificationDots.suffixFor(context, total)
            if (dot.isNotEmpty()) button.addView(label(context, dot))
        }
        button.alpha = 0.7f
        button.setOnClickListener { toggle(binding) }
        button.setOnLongClickListener {
            SubListMenu.show(context) { bind(binding) }
            true
        }
    }

    /** What the item views are built from; when only notifications changed, the views are kept. */
    private data class ItemsKey(val style: String, val color: Int, val icons: String, val dots: Boolean, val apps: List<List<Any?>>)

    private fun itemsKey(context: Context, store: SubListStore, entries: List<Entry>, color: Int) =
        ItemsKey(store.style, color, AppIcons.mode(context), NotificationDots.dotsEnabled(context),
            entries.map { listOf(it.app.packageName, it.app.activityClassName, it.user, it.app.label) })

    private fun bindItems(binding: FragmentHomeBinding, store: SubListStore, entries: List<Entry>, color: Int) {
        val context = binding.root.context
        val flow = binding.extrasSubList.extrasSubListItems
        val style = store.style
        val dots = NotificationDots.dotsEnabled(context)
        val dotStyle = NotificationDots.style(context)
        val key = itemsKey(context, store, entries, color)
        // Same apps, same look: only the dots change, so nothing blinks and a badge fades in or out
        if (flow.getTag(R.id.extras_sublist_items_shown) == key && flow.childCount == entries.size) {
            entries.forEachIndexed { i, entry ->
                flow.getChildAt(i).tag = entry // may have become picked or most-used
                updateDot(flow, flow.getChildAt(i), style, entry, dotStyle)
            }
            return
        }
        flow.setTag(R.id.extras_sublist_items_shown, key)
        flow.removeAllViews()
        flow.horizontalGap = dp(context, if (style == SubListStore.STYLE_NAMES) 18 else 6)
        flow.verticalGap = dp(context, 2)
        entries.forEach { entry ->
            val suffix = NotificationDots.suffix(context, entry.app.packageName, entry.app.activityClassName)
            val view: View = when (style) {
                SubListStore.STYLE_NAMES -> label(context, entry.app.label + suffix).apply {
                    maxLines = 1
                    // Free placement gives it a fixed-width cell
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(paddingLeft, dp(context, 8), paddingRight, dp(context, 8))
                }
                // The dot goes on the icon: after the name, the fixed-width cell would cut it off
                SubListStore.STYLE_ICONS_NAMES -> label(context, entry.app.label).apply {
                    val size = context.resources.getDimensionPixelSize(R.dimen.extras_sublist_icon_named)
                    val badged = icon(context, entry, size, color)?.let { BadgedIcon(context, it, color).apply { setBounds(0, 0, size, size) } }
                    badged?.setBadge(badgeCount(context, entry), dotStyle, animate = false)
                    setCompoundDrawablesRelative(null, badged, null, null)
                    compoundDrawablePadding = dp(context, 4)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.extras_sublist_name_size))
                    smallNameHalo(this, color)
                    width = context.resources.getDimensionPixelSize(R.dimen.extras_sublist_cell_named)
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(context, 6), 0, dp(context, 6))
                }
                else -> LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 4))
                    val size = context.resources.getDimensionPixelSize(R.dimen.extras_sublist_icon)
                    addView(ImageView(context).apply {
                        setImageDrawable(icon(context, entry, size, color))
                        layoutParams = LinearLayout.LayoutParams(size, size)
                    })
                    // A line for the dot under every icon, so the rows stay even
                    if (dots) addView(label(context, suffix.trim()).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.extras_sublist_name_size))
                        setPadding(0, 0, 0, 0)
                    })
                }
            }
            view.contentDescription = entry.app.label
            view.isClickable = true
            view.isFocusable = true
            view.tag = entry
            fun menu() = (view.tag as? Entry)?.let { SubListMenu.showItem(context, it) { bind(binding) } }
            view.setOnClickListener { (view.tag as? Entry)?.let { launch(binding, it) } }
            view.setOnLongClickListener {
                menu()
                true
            }
            view.setOnTouchListener(HoldDrag({ startDrag(binding, it) }, { menu() }))
            ViewCompat.addAccessibilityAction(view, context.getString(R.string.sublist_move_earlier)) { v, _ -> moveBy(binding, v, -1) }
            ViewCompat.addAccessibilityAction(view, context.getString(R.string.sublist_move_later)) { v, _ -> moveBy(binding, v, 1) }
            flow.addView(view)
        }
    }

    /** Notifications for the icon badge; 0 while dots are off. */
    private fun badgeCount(context: Context, entry: Entry) =
        if (NotificationDots.dotsEnabled(context)) NotificationDots.count(context, entry.app.packageName, entry.app.activityClassName) else 0

    /** Updates one kept item's dot in place: the icon badge fades; a text dot resizes smoothly. */
    private fun updateDot(flow: ViewGroup, view: View, style: String, entry: Entry, dotStyle: String) {
        val context = view.context
        val suffix = NotificationDots.suffix(context, entry.app.packageName, entry.app.activityClassName)
        val text: TextView
        val wanted: String
        when (style) {
            SubListStore.STYLE_ICONS_NAMES -> {
                ((view as? TextView)?.compoundDrawablesRelative?.get(1) as? BadgedIcon)
                    ?.setBadge(badgeCount(context, entry), dotStyle, animate = true)
                return
            }
            SubListStore.STYLE_NAMES -> {
                text = view as? TextView ?: return
                wanted = entry.app.label + suffix
            }
            else -> {
                text = (view as? ViewGroup)?.getChildAt(1) as? TextView ?: return
                wanted = suffix.trim()
            }
        }
        if (text.text.toString() == wanted) return
        flow.beginTransition()
        text.text = wanted
    }

    private fun icon(context: Context, entry: Entry, size: Int, color: Int) =
        AppIcons.icon(context, entry.app.packageName, entry.app.activityClassName, entry.user, size, color, entry.app.label)

    /**
     * Small names under the icons: bold, with a tight, strong halo in the opposite of the text
     * colour, so thin small letters stay readable on a busy wallpaper (the usual home text glow is
     * too faint at this size).
     */
    private fun smallNameHalo(view: TextView, color: Int) {
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
        val halo = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5) 0xE6000000.toInt() else 0xE6FFFFFF.toInt()
        view.setShadowLayer(dp(view.context, 3).toFloat(), 0f, 0f, halo)
    }

    private fun label(context: Context, value: String) = TextView(context, null, 0, R.style.ExtrasHomeText).apply { text = value }

    /** The home text colour (tints monochrome icons and the grid button). */
    private fun textColor(context: Context) = TextView(context, null, 0, R.style.ExtrasHomeText).currentTextColor

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
