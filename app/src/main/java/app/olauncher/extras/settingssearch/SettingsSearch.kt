package app.olauncher.extras.settingssearch

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.ApkUpdates
import app.olauncher.R
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.Motion
import app.olauncher.helper.animateChanges
import app.olauncher.helper.animationsOn
import app.olauncher.helper.fadeVisible
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard

/**
 * "Search settings" at the top of upstream's settings screen. Finds settings on both that screen
 * and the Extras screen ([SettingsIndex]); tapping one scrolls to it and lights it up briefly,
 * opening Extras first when it lives there.
 */
object SettingsSearch {

    /** Nav argument: the label string of the Extras row to land on. */
    const val ARG_FOCUS = "focusSetting"

    private const val HOLD_MS = 900L

    fun attach(fragment: Fragment, binding: FragmentSettingsBinding) {
        val context = fragment.requireContext()
        val layout = binding.scrollLayout
        val box = LayoutInflater.from(context).inflate(R.layout.extras_settings_search, layout, false) as LinearLayout
        layout.addView(box, 0)
        val field = box.findViewById<TextView>(R.id.settingsSearch)
        val results = box.findViewById<LinearLayout>(R.id.settingsSearchResults)
        val none = box.findViewById<View>(R.id.settingsSearchNone)
        val entries = SettingsIndex.available(ApkUpdates.supported)
        val text = { id: Int -> context.getString(id) }
        // Cards fade out and back in; the results card's height follows typing instantly
        layout.animateChanges(changing = false)
        box.animateChanges(changing = false)

        val clearOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { field.text = "" }
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, clearOnBack)

        var searching = false
        fun setSearching(on: Boolean) {
            if (on == searching) return
            searching = on
            clearOnBack.isEnabled = on
            layout.children.filter { it !== box }.forEach { card ->
                // Upstream hides some footer links for good; only bring back what was shown
                if (on) { card.setTag(R.id.settings_search_shown, card.visibility); card.fadeVisible(false) }
                else if (card.getTag(R.id.settings_search_shown) == View.VISIBLE) card.fadeVisible(true)
            }
            results.fadeVisible(on)
        }

        fun open(entry: SettingEntry) {
            field.hideKeyboard()
            field.text = ""
            when (entry.screen) {
                SettingsScreen.MAIN -> {
                    val view = binding.root.findViewById<View>(entry.viewId) ?: return
                    // The setting's row, unless the view sits right in a card or in the column itself
                    // (the version line): then just the view
                    val row = (view.parent as? View)?.takeIf { it !== layout && it.parent !== layout } ?: view
                    afterTransitions(layout) { focus(binding.scrollView, row) }
                }
                SettingsScreen.EXTRAS -> fragment.findNavController().navigate(
                    R.id.action_settingsFragment_to_extrasSettingsFragment, bundleOf(ARG_FOCUS to entry.label)
                )
            }
        }

        fun show(query: String) {
            val found = SettingsIndex.search(query, entries, text)
            // Typing isn't animated: swap the rows in place
            results.layoutTransition = null
            results.removeViews(1, results.childCount - 1)
            none.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            found.forEach { results.addView(resultRow(results, it, text) { open(it) }) }
        }

        field.doAfterTextChanged {
            val query = it?.toString().orEmpty()
            if (query.isNotBlank()) show(query)
            setSearching(query.isNotBlank())
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            SettingsIndex.search(field.text.toString(), entries, text).firstOrNull()?.let(::open)
            true
        }
    }

    private fun resultRow(parent: ViewGroup, entry: SettingEntry, str: (Int) -> String, onClick: () -> Unit): View {
        val context = parent.context
        val dp = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
            addView(TextView(context, null, 0, R.style.TextSmall).apply { text = str(entry.label) })
            addView(TextView(context, null, 0, R.style.TextSmallLight).apply {
                textSize = 14f
                text = when (entry.screen) {
                    SettingsScreen.MAIN -> str(entry.section)
                    SettingsScreen.EXTRAS -> context.getString(R.string.settings_search_in_extras, str(entry.section))
                }
            })
        }
    }

    /**
     * On the Extras screen: scroll to the row for [label] (or its parent row while it's hidden, or
     * else its section card) and light it up. [sections] maps each section title to its card,
     * whose rows are tagged with their label (ExtrasSettingsFragment.addRow).
     */
    fun focusExtras(scrollView: ScrollView, sections: Map<Int, ViewGroup>, label: Int) {
        val entry = SettingsIndex.entries.firstOrNull { it.screen == SettingsScreen.EXTRAS && it.label == label } ?: return
        fun row(l: Int) = sections.values.asSequence().flatMap { it.children }.firstOrNull { it.getTag(R.id.settings_label) == l }
        val target = row(label) ?: entry.fallback.takeIf { it != 0 }?.let(::row) ?: sections[entry.section] ?: return
        // Scrolled before the screen has faded in; lit once it has, so the light is seen
        scrollView.post { focus(scrollView, target, highlightDelay = Motion.medium(scrollView.context)) }
    }

    /** Puts [view] a third of the way down the screen (instantly: scrolling isn't animated) and lights it up. */
    private fun focus(scrollView: ScrollView, view: View, highlightDelay: Long = 0) {
        var top = 0
        var v: View? = view
        while (v != null && v !== scrollView) {
            top += v.top
            v = v.parent as? View
        }
        scrollView.scrollTo(0, (top - scrollView.height / 3).coerceAtLeast(0))
        if (highlightDelay == 0L) flashHighlight(view)
        else view.postDelayed({ if (view.isAttachedToWindow) flashHighlight(view) }, highlightDelay)
    }

    /** Runs [action] once [group]'s layout transition (cards fading back) has settled. */
    private fun afterTransitions(group: ViewGroup, action: () -> Unit) {
        val transition = group.layoutTransition
        group.post {
            if (transition == null || !transition.isRunning) return@post action()
            transition.addTransitionListener(object : LayoutTransition.TransitionListener {
                override fun startTransition(t: LayoutTransition, c: ViewGroup, v: View, type: Int) {}
                override fun endTransition(t: LayoutTransition, c: ViewGroup, v: View, type: Int) {
                    if (t.isRunning) return
                    t.removeTransitionListener(this)
                    group.post(action)
                }
            })
        }
    }

    /**
     * A soft rounded tint over [view] that fades in, holds and fades out. Drawn as the view's
     * foreground, so nothing moves. On e-ink / with animations off it appears and goes instantly.
     */
    fun flashHighlight(view: View) {
        val context = view.context
        val dp = context.resources.displayMetrics.density
        val pill = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(context.getColorFromAttr(R.attr.primaryColor))
            alpha = 0
        }
        val max = 0x26
        val previous = view.foreground
        view.foreground = pill
        val done = { if (view.foreground === pill) view.foreground = previous }
        if (!context.animationsOn()) {
            pill.alpha = max
            view.postDelayed(done, HOLD_MS + Motion.medium(context))
            return
        }
        ValueAnimator.ofInt(0, max).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { pill.alpha = it.animatedValue as Int }
            doOnEnd {
                ValueAnimator.ofInt(max, 0).apply {
                    startDelay = HOLD_MS
                    duration = Motion.medium(context)
                    interpolator = Motion.easing
                    addUpdateListener { pill.alpha = it.animatedValue as Int }
                    doOnEnd { done() }
                }.start()
            }
        }.start()
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })
}
