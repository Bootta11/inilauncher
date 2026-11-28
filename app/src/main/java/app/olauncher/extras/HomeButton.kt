package app.olauncher.extras

import android.app.Dialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Collections
import java.util.WeakHashMap

/**
 * The Home button always lands on the plain home screen: MainActivity pops back to it, and
 * [pressed] closes whatever is still open on top (dialogs, popup menus, the sub list…).
 * Dialogs and menus register when shown ([track]); home-screen parts register a close action
 * for as long as their view lives ([onPress]). Each closes with its own animation.
 */
object HomeButton {

    private val dialogs: MutableSet<Dialog> = Collections.newSetFromMap(WeakHashMap())
    private val popups: MutableSet<PopupMenu> = Collections.newSetFromMap(WeakHashMap())
    private val actions = mutableListOf<() -> Unit>()

    fun track(dialog: Dialog) { dialogs += dialog }
    fun track(popup: PopupMenu) { popups += popup }

    /** Dialogs on screen now (the showcase adds them to its screenshots). */
    fun showingDialogs(): List<Dialog> = dialogs.filter { it.isShowing }

    /** Runs [action] on every Home press until [owner] is destroyed. */
    fun onPress(owner: LifecycleOwner, action: () -> Unit) {
        actions += action
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) actions -= action
        })
    }

    fun pressed() {
        popups.toList().forEach { runCatching { it.dismiss() } }
        popups.clear()
        dialogs.toList().filter { it.isShowing }.forEach { runCatching { it.dismiss() } }
        dialogs.clear()
        actions.toList().forEach { runCatching(it) }
    }
}
