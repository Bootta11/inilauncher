package app.olauncher.ui

import android.animation.LayoutTransition
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.Filterable
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.appLabelMatches
import app.olauncher.helper.expandVisible
import app.olauncher.helper.fadeVisible
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        private const val AUTO_MENU = "autoMenu" // Inilauncher

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    private var autoLaunchedQuery: String? = null
    var allowAutoLaunch = true
    // Inilauncher: a last say for the query these results belong to (asked when they're shown, not when typed)
    var autoLaunchAllowed: (String?) -> Boolean = { true }
    // Inilauncher: hold search (search-bar toggle), and the single match whose actions show by themselves
    @Volatile var holdToggled = false
    var autoMenuEnabled = false // Inilauncher: setting "Show actions for a single match"
    private var autoMenuApp: AppModel? = null
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    openMenu = appModel === autoMenuApp, // Inilauncher
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true) && !holdToggled // Inilauncher

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    app !is AppModel.PrivateSpaceHeader && appLabelMatches(app.appLabel, charSearch)
                } as MutableList<AppModel>)

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        updateAutoMenu(constraint) // Inilauncher
                        autoLaunch(constraint?.toString())
                    }
                }
            }
        }
    }

    private fun autoLaunch(query: String?) {
        // App list refreshes re-run the current query, so launch only once until it changes
        if (query == autoLaunchedQuery) return
        autoLaunchedQuery = null
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
                // Skip the empty bottom-padding item, left alone when the app list is empty
                && appFilteredList[0].appPackage.isNotEmpty()
                && autoLaunchAllowed(query) // Inilauncher: e.g. not while typing a sum
            ) {
                autoLaunchedQuery = query
                appClickListener(appFilteredList[0])
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Inilauncher: in a hold search, a lone match opens its actions as if long-pressed
    private fun updateAutoMenu(query: CharSequence?) {
        val only = appFilteredList.singleOrNull { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotEmpty() }
        val target = only?.takeIf {
            autoMenuEnabled && app.olauncher.extras.search.HoldSearch.isHold(query, flag, holdToggled) &&
                !app.olauncher.extras.folders.Folders.isFolder(it.appPackage)
        }
        if (target === autoMenuApp) return
        val old = autoMenuApp?.let { appFilteredList.indexOf(it) } ?: -1
        autoMenuApp = target
        // A payload keeps the same row (no item-animator crossfade), so the menu fades in place
        if (old >= 0) notifyItemChanged(old, AUTO_MENU)
        target?.let { notifyItemChanged(appFilteredList.indexOf(it), AUTO_MENU) }
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList)
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotEmpty() }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            openMenu: Boolean = false,
        ) = with(binding) {
            // Inilauncher: an open action row on this same app stays (auto menu) or fades away;
            // anything else resets at once, as upstream did
            val sameApp = boundModel == appModel
            val collapsing = appHideLayout.getTag(R.id.motion_expand) != null && appHideLayout.getTag(R.id.motion_expand_dir) == false
            val menuShown = sameApp && appHideLayout.isVisible && appHideLayout.getTag(R.id.motion_hiding) != true && !collapsing
            when {
                openMenu && menuShown -> Unit
                menuShown -> closeMenu()
                else -> {
                    appHideLayout.animate().cancel()
                    (appHideLayout.getTag(R.id.motion_expand) as? android.animation.ValueAnimator)?.cancel()
                    appHideLayout.setTag(R.id.motion_expand, null)
                    appHideLayout.setTag(R.id.motion_hiding, null)
                    wantBelow = false
                    menuBelow(false)
                    appHideLayout.alpha = 1f
                    appHideLayout.visibility = View.GONE
                    appTitle.animate().cancel()
                    appTitle.alpha = 1f
                    appTitle.visibility = View.VISIBLE
                }
            }
            renameLayout.visibility = View.GONE
            boundModel = appModel

            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
                append(app.olauncher.extras.notifications.NotificationDots.suffix( // Inilauncher
                    root.context, appModel.appPackage, (appModel as? AppModel.App)?.activityClassName))
            }
            appTitle.gravity = appLabelGravity
            if (appModel is AppModel.App) app.olauncher.extras.iconpack.AppIcons.decorate( // Inilauncher
                appTitle, appModel.appPackage, appModel.activityClassName, appModel.user)
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }

            appTitle.setOnLongClickListener {
                // Inilauncher: folders have their own edit menu
                if (app.olauncher.extras.folders.Folders.isFolder(appModel.appPackage)) {
                    app.olauncher.extras.folders.FolderUi.showFolderMenu(root.context, (appModel as AppModel.App).activityClassName.orEmpty())
                    return@setOnLongClickListener true
                }
                showMenu(flag, appModel, animate = false) // Inilauncher: body moved to showMenu
                true
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appFolder.setOnClickListener { // Inilauncher
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                app.olauncher.extras.folders.FolderUi.addToFolder(root.context, appModel)
            }
            appSubList.setOnClickListener { // Inilauncher
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                app.olauncher.extras.sublist.SubListActions.toggle(root.context, appModel)
            }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                if (below) closeMenu() // Inilauncher
                else {
                    appHideLayout.visibility = View.GONE
                    appTitle.visibility = View.VISIBLE
                }
            }
            if (openMenu && !menuShown) showMenu(flag, appModel, animate = true) // Inilauncher
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
        }

        private var boundModel: AppModel? = null

        /** The long-press action row. [animate]: fade it in over the title (the auto-opened menu). */
        private fun showMenu(flag: Int, appModel: AppModel, animate: Boolean) = with(binding) {
            if (appModel.appPackage.isNotEmpty()) {
                appDelete.alpha = when (
                    appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                ) {
                    true -> 1.0f
                    false -> 0.5f
                }
                appDelete.text = if (appModel is AppModel.PinnedShortcut)
                    root.context.getString(R.string.remove)
                else
                    root.context.getString(R.string.delete)
                appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                    root.context.getString(R.string.adapter_show)
                else
                    root.context.getString(R.string.adapter_hide)
                if (!animate) appTitle.visibility = View.INVISIBLE
                appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                    true -> 0.5f
                    false -> 1.0f
                }
                if (animate) openMenuBelow() else { menuBelow(false); appHideLayout.visibility = View.VISIBLE }
                // Only allow renaming non hidden apps
                appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                appFolder.isVisible = appModel is AppModel.App && flag == Constants.FLAG_LAUNCH_APP // Inilauncher
                appSubList.isVisible = flag == Constants.FLAG_LAUNCH_APP && // Inilauncher
                        app.olauncher.extras.sublist.SubListActions.canAdd(root.context, appModel)
            }
        }

        // Inilauncher: the auto-opened menu sits under the app's name (so you see which app matched)
        // and grows the row; a long-press menu covers the name, as upstream
        private var below = false
        private var rowTransition: LayoutTransition? = null

        private fun menuBelow(on: Boolean) = with(binding) {
            if (below == on) return@with
            below = on
            val lp = appHideLayout.layoutParams as FrameLayout.LayoutParams
            val edge = (1 * root.resources.displayMetrics.density).toInt()
            lp.height = if (on) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
            if (!on) wantBelow = false
            lp.topMargin = if (on) appTitle.height else edge
            lp.bottomMargin = edge
            appHideLayout.layoutParams = lp
            appHideLayout.setTag(R.id.motion_expand_margins, null) // expandVisible caches margins
            // The row grows frame by frame; its own layout transition would aim at a stale size
            if (on) { rowTransition = root.layoutTransition; root.layoutTransition = null }
            else root.layoutTransition = rowTransition
        }

        private var wantBelow = false

        private fun openMenuBelow(): Unit = with(binding) {
            wantBelow = true
            if (!appTitle.isLaidOut || appTitle.height == 0) {
                // A fresh row: place the actions once the name has its height
                // (posted: a layout change made during the list's layout pass would be dropped)
                appTitle.doOnLayout { appTitle.post { if (wantBelow && !below) openMenuBelow() } }
                return@with
            }
            appTitle.animate().cancel()
            appTitle.alpha = 1f
            appTitle.visibility = View.VISIBLE
            appHideLayout.visibility = View.GONE
            menuBelow(true)
            appHideLayout.expandVisible(true)
        }

        private fun closeMenu() = with(binding) {
            if (below) {
                appHideLayout.expandVisible(false)
                return@with
            }
            appTitle.animate().cancel()
            appTitle.alpha = 0f
            appTitle.visibility = View.VISIBLE
            appTitle.animate().alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
            appHideLayout.fadeVisible(false)
            if (!root.context.animationsOn()) { appTitle.animate().cancel(); appTitle.alpha = 1f }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
