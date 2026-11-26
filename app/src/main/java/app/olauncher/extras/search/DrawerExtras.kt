package app.olauncher.extras.search

import android.view.View
import android.view.ViewGroup
import app.olauncher.helper.animateChanges
import app.olauncher.helper.Motion
import app.olauncher.helper.beginTransition
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.extras.privacy.HiddenAppsLock
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.openUrl
import app.olauncher.helper.showToast
import app.olauncher.ui.AppDrawerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * The app list's extras: the "Suggested" row, extra search results, the A–Z scroller and
 * "Sort by usage".
 * Hooked into upstream's AppDrawerFragment with one call in onViewCreated and one in
 * onQueryTextChange. The A–Z scroller, icon prefetch and keyboard handling work in every list
 * (also "Please select an app" and hidden apps); suggestions, search results and sort by usage
 * only when the list is used to launch apps.
 */
class DrawerExtras(
    private val fragment: Fragment,
    private val binding: FragmentAppDrawerBinding,
    private val flag: Int,
    private val adapter: () -> AppDrawerAdapter,
    private val launch: (AppModel) -> Unit,
) {
    private val context: Context get() = fragment.requireContext()
    private val prefs = ExtrasPrefs(fragment.requireContext())
    private val top get() = binding.extrasDrawerTop
    private var webJob: Job? = null
    private var suggestionsCache: List<String> = emptyList()

    private val launching get() = flag == Constants.FLAG_LAUNCH_APP

    /** [onUsageLoaded] re-sorts the list once fresh usage counts arrive. */
    fun attach(onUsageLoaded: () -> Unit = {}) {
        adapter().autoLaunchAllowed = { query -> allowsAutoLaunch(query.orEmpty()) }
        if (flag == Constants.FLAG_HIDDEN_APPS && HiddenAppsLock.isEnabled(context)) secureWindow()
        setUpScroller()
        // The suggested row fades/slides away while typing; result rows stay instant (typing must not lag)
        (top.root as? ViewGroup)?.animateChanges(changing = false)
        setUpIconPrefetch()
        hideKeyboardOnScroll()
        if (!launching) return
        if (prefs.bool(ExtrasKeys.SEARCH_SHORTCUTS)) {
            // Every app's shortcuts: a slow system call, done while the list appears
            val app = context.applicationContext
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { SearchProviders.loadShortcuts(app) }
        }
        loadSuggestions()
        loadUsageCounts(onUsageLoaded)
    }

    /** The locked hidden-apps list: no screenshots, screen sharing or recents thumbnail while it's open. */
    private fun secureWindow() {
        val window = fragment.requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        })
    }

    // ---- Sort by usage ----

    private val sortByUsage get() = launching && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            prefs.bool(ExtrasKeys.SORT_BY_USAGE) && context.appUsagePermissionGranted()

    /** [apps] (already A–Z) with the most-used apps first inside each letter, or unchanged when off. */
    fun sortByUsage(apps: List<AppModel>): List<AppModel> {
        if (!sortByUsage || usageCounts.isEmpty()) return apps
        val counts = usageCounts
        return UsageSort.sortWithinLetters(apps, { it.appLabel }) { app ->
            if (app is AppModel.App) counts[app.appPackage] ?: 0 else 0
        }
    }

    private fun loadUsageCounts(onLoaded: () -> Unit) {
        if (!sortByUsage) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.Default) { UsageSort.counts(launchEvents(USAGE_DAYS)) }
            if (fresh == usageCounts) return@launch
            usageCounts = fresh
            if (fragment.view != null) onLoaded()
        }
    }

    // ---- A–Z scroller ----

    private fun setUpScroller() {
        val scroller = binding.extrasAzScroller
        scroller.isVisible = prefs.bool(ExtrasKeys.AZ_SCROLLER)
        if (!scroller.isVisible) return
        keepSpareRows()
        placeScroller(scroller)
        scroller.onTouchStart = {
            lastJump = -1
            dockOrder = null
            // Remember where we were, in case this turns out to be the first tap of a double tap
            beforeTap = listPosition()
            keyboardWasOpen = keyboardOpen()
            // The keyboard covers the lower letters: get it out of the way
            if (keyboardWasOpen) binding.search.hideKeyboard()
        }
        scroller.onLetter = { letter ->
            val index = letterTable().jump[LetterIndex.LETTERS.indexOf(letter)]
            // Letters without apps map to the same row as their neighbour: don't re-scroll to it
            val moves = index >= 0 && index != lastJump
            if (moves) {
                lastJump = index
                layoutManager()?.scrollToPositionWithOffset(index, 0)
            }
            moves
        }
        scroller.onDoubleTap = {
            // Undo the first tap's jump, then flip the keyboard from how it was before that tap
            beforeTap?.let { (position, offset) -> layoutManager()?.scrollToPositionWithOffset(position, offset) }
            lastJump = -1
            if (keyboardWasOpen) binding.search.hideKeyboard() else binding.search.showKeyboard()
        }
        setUpPick(scroller)
        scroller.onKeyboardButton = {
            if (keyboardOpen()) binding.search.hideKeyboard() else binding.search.showKeyboard()
        }
        refreshLetters()
    }

    // ---- Pull off the strip to pick an app ----
    // Order: the suggested row first, from the app nearest the strip to the farthest, then the held
    // letter's apps from the top of the list down.

    private var highlight: RowHighlight? = null
    private var dockHighlight: DockHighlight? = null
    private var picked: AppModel? = null
    private var pickFor: List<AppModel>? = null
    private val letterRows = HashMap<Char, List<Int>>()
    /** Suggested-row items for the current touch, nearest the strip first (null = not worked out yet). */
    private var dockOrder: List<View>? = null

    /** List positions of [letter]'s apps, top to bottom; cached per list (like [letterTable]). */
    private fun letterRows(letter: Char): List<Int> {
        val list = adapter().appFilteredList
        if (list !== pickFor) { letterRows.clear(); pickFor = list }
        return letterRows.getOrPut(letter) {
            LetterPick.rows(list.map { app -> if (app.isPickable()) app.appLabel else null }, letter)
        }
    }

    private fun AppModel.isPickable() =
        this is AppModel.App && appPackage.isNotEmpty() && !appPackage.startsWith("inilauncher.")

    /** The suggested row's apps while it's on screen, nearest the strip first. */
    private fun dockOrder(): List<View> = dockOrder ?: run {
        val row = top.extrasSuggested
        val strip = binding.extrasAzScroller
        if (!row.isShown || row.childCount == 0) return@run emptyList()
        val loc = IntArray(2)
        strip.getLocationOnScreen(loc)
        val stripX = loc[0] + strip.width / 2
        (0 until row.childCount).map { row.getChildAt(it) }
            .filter { it.isVisible && it.tag is AppModel.App }
            .sortedBy { child -> child.getLocationOnScreen(loc); kotlin.math.abs(loc[0] + child.width / 2 - stripX) }
    }.also { dockOrder = it }

    private fun setUpPick(scroller: AlphabetScrollerView) {
        val rowHighlight = RowHighlight(binding.recyclerView).also { highlight = it }
        val dock = DockHighlight(top.extrasSuggested).also { dockHighlight = it }
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                rowHighlight.release(); dock.release()
                highlight = null; dockHighlight = null
            }
        })
        scroller.pickCount = { letter -> dockOrder().size + letterRows(letter).size }
        scroller.onPickSlot = { letter, slot ->
            val docked = dockOrder()
            val dockItem = docked.getOrNull(slot)
            val row = if (slot >= docked.size) letterRows(letter).getOrNull(slot - docked.size) else null
            val app = (dockItem?.tag as? AppModel) ?: row?.let { adapter().appFilteredList.getOrNull(it) }
            val moved = app != null && app !== picked
            picked = app
            dock.show(dockItem)
            if (row != null) keepVisible(row)
            rowHighlight.show(row ?: RecyclerView.NO_POSITION)
            moved
        }
        scroller.onPick = {
            val app = picked
            picked = null
            dock.show(null)
            rowHighlight.show(RecyclerView.NO_POSITION)
            if (app != null) launch(app)
        }
    }

    /** Scrolls (instantly) just enough for the row at [position] to be fully on screen. */
    private fun keepVisible(position: Int) {
        val lm = layoutManager() ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || position < first || position > last) lm.scrollToPosition(position)
    }

    private var beforeTap: Pair<Int, Int>? = null
    private var keyboardWasOpen = false

    private fun layoutManager() = binding.recyclerView.layoutManager as? LinearLayoutManager

    /** First visible row and its offset, to come back to after a double tap. */
    private fun listPosition(): Pair<Int, Int>? {
        val lm = layoutManager() ?: return null
        val position = lm.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val top = lm.findViewByPosition(position)?.top ?: 0
        return position to top - binding.recyclerView.paddingTop
    }

    private fun keyboardOpen(): Boolean =
        androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            ?: binding.search.hasFocus()

    /**
     * Side: opposite the app names (auto), or fixed left/right for left- or right-handed use
     * (absolute, so a right-to-left language doesn't flip it). The list leaves room for the
     * strip so names don't run under it. "Thumb reach" makes it cover the lower 60% only.
     */
    private fun placeScroller(scroller: AlphabetScrollerView) {
        val lp = scroller.layoutParams as android.widget.FrameLayout.LayoutParams
        val horizontal = scrollerSide(context)
        val thumb = scrollerThumbReach(context)
        lp.gravity = horizontal or (if (thumb) android.view.Gravity.BOTTOM else android.view.Gravity.TOP)
        scroller.layoutParams = lp
        // Keep the strip (its ⌨ button is at the bottom) clear of the navigation bar
        val baseMargin = lp.bottomMargin
        (scroller.parent as android.view.View).doOnLayout { frame ->
            val navBar = androidx.core.view.ViewCompat.getRootWindowInsets(frame)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val frameBottomOnScreen = IntArray(2).also { frame.getLocationOnScreen(it) }[1] + frame.height
            val hidden = (frameBottomOnScreen - (frame.rootView.height - navBar)).coerceAtLeast(0)
            lp.bottomMargin = maxOf(baseMargin, hidden + (8 * frame.resources.displayMetrics.density).toInt())
            if (thumb) lp.height = ((frame.height - lp.bottomMargin) * 0.6f).toInt()
            scroller.layoutParams = lp
        }

        val rv = binding.recyclerView
        val onLeft = android.view.Gravity.getAbsoluteGravity(horizontal, rv.layoutDirection) and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.LEFT
        val room = lp.width
        if (onLeft) rv.setPadding(rv.paddingLeft + room, rv.paddingTop, rv.paddingRight, rv.paddingBottom)
        else rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight + room, rv.paddingBottom)
    }

    /** Dims the letters that have no apps in the list as it is now (search results included). */
    private fun refreshLetters() {
        if (binding.extrasAzScroller.isVisible) binding.extrasAzScroller.setPresent(letterTable().present)
    }

    // ---- Keyboard: dragging the list puts it away ----

    private fun hideKeyboardOnScroll() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && keyboardOpen()) binding.search.hideKeyboard()
            }
        })
    }

    // ---- Icons: render the whole list's icons in the background once it's shown ----

    private val prefetch = Runnable {
        if (fragment.view != null) app.olauncher.extras.iconpack.AppIcons.prefetch(context, adapter().appFilteredList)
    }

    private fun setUpIconPrefetch() {
        adapter().registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshLetters()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refreshLetters()
            private fun schedule() {
                refreshLetters()
                // After the first rows are drawn (they set the icon size), and once per burst of changes
                binding.recyclerView.removeCallbacks(prefetch)
                binding.recyclerView.postDelayed(prefetch, 400)
            }
        })
    }

    /**
     * A letter jump replaces every row on screen at once. RecyclerView keeps only 5 spare rows by
     * default, so each jump inflated several new ones (the row layout, with its hidden rename
     * box, takes ~6 ms each): that was the stutter. Keep enough spares, and make them while idle.
     */
    private fun keepSpareRows() {
        val rv = binding.recyclerView
        val pool = rv.recycledViewPool
        pool.setMaxRecycledViews(AppDrawerAdapter.VIEW_TYPE_APP, SPARE_ROWS)
        rv.post {
            var toMake = rv.childCount.coerceAtLeast(12) + 2 - pool.getRecycledViewCount(AppDrawerAdapter.VIEW_TYPE_APP)
            android.os.Looper.myQueue().addIdleHandler {
                if (fragment.view == null || toMake <= 0) return@addIdleHandler false
                pool.putRecycledView(adapter().createViewHolder(rv, AppDrawerAdapter.VIEW_TYPE_APP))
                toMake--
                toMake > 0
            }
        }
    }

    private var lastJump = -1
    private var tableFor: List<AppModel>? = null
    private var table = LetterIndex.Table(IntArray(LetterIndex.LETTERS.size) { -1 }, BooleanArray(LetterIndex.LETTERS.size) { true })

    /** Built once per list (the adapter swaps in a new list on every change or search). */
    private fun letterTable(): LetterIndex.Table {
        val list = adapter().appFilteredList
        if (list !== tableFor) {
            table = LetterIndex.build(list.map { app ->
                if (app is AppModel.PrivateSpaceHeader || app.appPackage.startsWith("inilauncher.")) null else app.appLabel
            })
            tableFor = list
        }
        return table
    }

    // ---- Suggested apps ----

    private fun loadSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!prefs.bool(ExtrasKeys.SUGGESTED_APPS) || !context.appUsagePermissionGranted()) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            suggestionsCache = withContext(Dispatchers.Default) { rankedPackages() }
            // The app list may still be loading; wait for it briefly
            repeat(20) {
                if (adapter().appsList.size > 1) return@repeat
                delay(100)
            }
            if (fragment.view != null) showSuggestions()
        }
    }

    private fun rankedPackages(): List<String> {
        val now = System.currentTimeMillis()
        val launches = launchEvents(14, now)
        val calendar = Calendar.getInstance()
        val hourOf: (Long) -> Int = { calendar.timeInMillis = it; calendar.get(Calendar.HOUR_OF_DAY) }
        return SuggestionRanking.rank(launches, now, hourOf, exclude = setOf(context.packageName) + SuggestedBlocklist.blocked(context), max = 8)
    }

    private fun launchEvents(days: Int, now: Long = System.currentTimeMillis()): List<LaunchEvent> =
        LaunchHistory.launches(context, days, now)

    /** Packages the dock shows now, so an unchanged list isn't rebuilt (no blink). */
    private var shownSuggestions: List<String> = emptyList()
    /** The icon style the dock was built in, so changing it in settings rebuilds the dock. */
    private var shownIconMode: String? = null

    /**
     * The suggested apps as a small dock: each app's icon (in the list's icon style) with its
     * name under it, in the list's font, or just the names when icons are off. 4 apps, 6 on a
     * wide screen.
     */
    private fun showSuggestions() {
        val row = top.extrasSuggested
        val apps = adapter().appsList.filterIsInstance<AppModel.App>()
        // Pinned apps first, in the order they were pinned, then the ranked ones
        val pinned = SuggestedBlocklist.pinned(context)
        val picked = (pinned + suggestionsCache.filter { it !in pinned })
            .mapNotNull { pkg -> apps.firstOrNull { it.appPackage == pkg } }
            .take(dockSlots())
        val packages = picked.map { it.appPackage }
        val iconMode = AppIcons.mode(context)
        val styleChanged = iconMode != shownIconMode
        if (styleChanged || packages != shownSuggestions || row.childCount != shownSuggestions.size) {
            val kept = shownSuggestions.filter { it in packages }
            if (!styleChanged && row.childCount == shownSuggestions.size && packages.take(kept.size) == kept) {
                // Some left (e.g. "Don't suggest"), the rest stays in order: remove those and add
                // the newcomers at the end, so the row animates instead of being rebuilt
                for (i in shownSuggestions.indices.reversed()) if (shownSuggestions[i] !in packages) row.removeViewAt(i)
                picked.drop(kept.size).forEach { app -> row.addView(dockItem(app)) }
            } else if (!styleChanged && row.childCount == shownSuggestions.size && kept.isNotEmpty()) {
                // Pinned or unpinned: same views in a new order, so they slide to their places.
                // The row's own transition is off meanwhile; it only knows adding and removing.
                val views = shownSuggestions.indices.associate { shownSuggestions[it] to row.getChildAt(it) }
                val transition = row.layoutTransition
                row.layoutTransition = null
                row.beginTransition(Motion.medium(context))
                row.removeAllViews()
                picked.forEach { app -> row.addView(views[app.appPackage] ?: dockItem(app)) }
                row.layoutTransition = transition
            } else {
                row.removeAllViews()
                picked.forEach { app -> row.addView(dockItem(app)) }
            }
            shownSuggestions = packages
            shownIconMode = iconMode
        }
        // Pinned or unpinned in place: the badge fades in or out
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i) as? TextView ?: continue
            val app = item.tag as? AppModel.App ?: continue
            (item.compoundDrawablesRelative[1] as? PinBadge)?.show(app.appPackage in pinned)
        }
        row.isVisible = picked.isNotEmpty()
        updateTopVisibility()
    }

    /** 4 apps, 6 on a wide screen. */
    private fun dockSlots(): Int = if (context.resources.configuration.screenWidthDp >= 600) 6 else 4

    private fun dockItem(app: AppModel.App): TextView = TextView(context, null, 0, R.style.TextLarge).apply {
        text = app.appLabel
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        val iconsOff = AppIcons.mode(context) == AppIcons.MODE_OFF
        val size = pad(44)
        // AppIcons.icon() falls back to system icons when off (the home icon grid needs them); the dock follows the list
        val icon = if (iconsOff) null else AppIcons.icon(
            context, app.appPackage, app.activityClassName, app.user, size, currentTextColor, app.appLabel
        )
        // The pin badge sits on the icon. Without one, a small pin above the name takes 10 dp of
        // the top padding, so the name never moves when it shows.
        val pinned = app.appPackage in SuggestedBlocklist.pinned(context)
        val badge = PinBadge(context, icon, currentTextColor, pinned).apply {
            setBounds(0, 0, icon?.bounds?.width() ?: pad(10), icon?.bounds?.height() ?: pad(10))
        }
        setPadding(pad(2), pad(if (icon == null) 0 else 8), pad(2), pad(if (icon == null) 12 else 8))
        compoundDrawablePadding = pad(if (icon == null) 2 else 6)
        setCompoundDrawablesRelative(null, badge, null, null)
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        contentDescription = app.appLabel
        tag = app
        setOnClickListener { launch(app) }
        setOnLongClickListener { view ->
            val canPin = SuggestedBlocklist.pinned(context).size < dockSlots()
            SuggestedBlocklist.showMenu(view, app.appLabel, app.appPackage, app.user, canPin) {
                // Not suggested any more: the next app slides into the gap (rankedPackages keeps spares)
                if (app.appPackage in SuggestedBlocklist.blocked(context)) suggestionsCache = suggestionsCache - app.appPackage
                if (fragment.view == null) return@showMenu
                this@DrawerExtras.top.extrasSuggested.animateChanges(changing = false)
                showSuggestions()
            }
            true
        }
    }

    // ---- Search results ----

    /**
     * Upstream opens the only matching app by itself. While the search could still be a sum (calculator
     * on), it waits: otherwise the "1" of "12*3" opens whichever app is the only one with a 1 in its name.
     * A letter ("1p") makes it a normal search again.
     */
    fun allowsAutoLaunch(query: String): Boolean =
        !(launching && prefs.bool(ExtrasKeys.SEARCH_CALCULATOR) && ExpressionEvaluator.couldBeCalculation(query))

    /** Enter on a sum copies its answer (true); anything else is left to upstream (false). */
    fun submit(query: String): Boolean {
        if (!launching || fragment.view == null || !prefs.bool(ExtrasKeys.SEARCH_CALCULATOR)) return false
        val answer = SearchProviders.calculation(query.trim()) ?: return false
        open(answer)
        return true
    }

    fun onQuery(raw: String) {
        if (!launching || fragment.view == null) return
        val query = raw.trim()
        top.extrasSuggested.isVisible = query.isEmpty() && top.extrasSuggested.isNotEmpty()
        // In memory: shown at once, with every letter
        val calculation = if (query.isNotEmpty() && prefs.bool(ExtrasKeys.SEARCH_CALCULATOR))
            listOfNotNull(SearchProviders.calculation(query)) else emptyList()
        val shortcuts = if (query.isNotEmpty() && prefs.bool(ExtrasKeys.SEARCH_SHORTCUTS))
            SearchProviders.shortcuts(context, query) else emptyList()
        render(calculation + shortcuts)

        // Contacts (a database query) and the web (a request) run in the background once typing pauses
        webJob?.cancel()
        val contactsOn = query.length >= 2 && prefs.bool(ExtrasKeys.SEARCH_CONTACTS)
        val webOn = SearchProviders.sendsToWeb(query, prefs.bool(ExtrasKeys.SEARCH_WEB), prefs.bool(ExtrasKeys.SEARCH_CALCULATOR))
        if (!contactsOn && !webOn) return
        val app = context.applicationContext
        webJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(CONTACTS_DELAY_MS)
            val contacts = if (contactsOn) withContext(Dispatchers.IO) { SearchProviders.contacts(app, query) } else emptyList()
            if (fragment.view == null) return@launch
            if (contacts.isNotEmpty()) render(calculation + contacts + shortcuts)
            if (!webOn) return@launch
            delay(WEB_DELAY_MS - CONTACTS_DELAY_MS)
            val web = SearchProviders.webSuggestions(query)
            if (fragment.view != null && web.isNotEmpty()) render(calculation + contacts + shortcuts + web)
        }
    }

    private fun render(results: List<SearchResult>) {
        val list = top.extrasResults
        list.removeAllViews()
        var lastKind: Int? = null
        results.forEach { result ->
            val kind = when (result) {
                is SearchResult.Calculation -> R.string.search_calculator
                is SearchResult.Contact -> R.string.search_contacts
                is SearchResult.Shortcut -> R.string.search_shortcuts
                is SearchResult.WebSuggestion -> R.string.search_web
            }
            if (kind != lastKind) {
                list.addView(TextView(context, null, 0, R.style.TextSmallLight).apply {
                    setText(kind)
                    textSize = 12f
                    setPadding(0, pad(8), 0, 0)
                })
                lastKind = kind
            }
            list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = when (result) {
                    is SearchResult.Shortcut -> context.getString(R.string.search_shortcut_line, result.label, result.appLabel)
                    else -> result.label
                }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, pad(6), 0, pad(6))
                setOnClickListener { open(result) }
            })
        }
        list.isVisible = results.isNotEmpty()
        updateTopVisibility()
    }

    private fun open(result: SearchResult) {
        when (result) {
            is SearchResult.Calculation -> try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(result.value, result.value))
                context.showToast(context.getString(R.string.search_copied, result.value))
            } catch (e: Exception) {
                // Some phones refuse clipboard writes (work profiles, policies)
                context.showToast(context.getString(R.string.search_copy_failed))
            }
            is SearchResult.Contact -> try {
                context.startActivity(SearchProviders.contactIntent(result))
            } catch (e: Exception) {
                context.showToast(context.getString(R.string.unable_to_open_app))
            }
            is SearchResult.Shortcut -> if (!SearchProviders.launchShortcut(context, result.info))
                context.showToast(context.getString(R.string.unable_to_open_shortcut))
            is SearchResult.WebSuggestion -> context.openUrl(Constants.URL_DUCK_SEARCH + android.net.Uri.encode(result.label))
        }
    }

    private fun updateTopVisibility() {
        top.root.isVisible = top.extrasSuggested.isVisible || top.extrasResults.isVisible
    }

    private fun pad(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val SPARE_ROWS = 32
        private const val CONTACTS_DELAY_MS = 100L
        private const val WEB_DELAY_MS = 250L
        const val SIDE_LEFT = "left"
        const val SIDE_RIGHT = "right"
        const val HEIGHT_THUMB = "thumb"
        private const val USAGE_DAYS = 30

        /** The A–Z strip's horizontal gravity from the settings: opposite the app names, or fixed left/right. */
        fun scrollerSide(context: Context): Int {
            val labelsOnRight = (app.olauncher.data.Prefs(context).appLabelAlignment and android.view.Gravity.END) == android.view.Gravity.END
            return when (ExtrasPrefs(context).string(ExtrasKeys.AZ_SIDE)) {
                SIDE_LEFT -> android.view.Gravity.LEFT
                SIDE_RIGHT -> android.view.Gravity.RIGHT
                else -> if (labelsOnRight) android.view.Gravity.START else android.view.Gravity.END
            }
        }

        /** "Thumb reach": the strip covers the lower 60% only. */
        fun scrollerThumbReach(context: Context): Boolean = ExtrasPrefs(context).string(ExtrasKeys.AZ_HEIGHT) == HEIGHT_THUMB

        /** Last usage counts, kept so reopening the list is sorted at once; refreshed on every open. */
        @Volatile private var usageCounts: Map<String, Int> = emptyMap()
    }
}
