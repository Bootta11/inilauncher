package app.olauncher.extras

import android.view.View
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.helper.showPopupMenu

/**
 * Shows [options] in upstream's popup menu next to this view. When the row is low on the screen,
 * the page scrolls up first, so the menu doesn't open behind the navigation bar.
 */
fun View.showChoices(options: List<String>, onChosen: (Int) -> Unit) {
    val show = {
        showPopupMenu(configure = { menu ->
            options.forEachIndexed { i, option -> menu.add(0, i, i, option) }
        }) { onChosen(it.itemId) }
    }
    val scroll = generateSequence(parent) { it.parent }.firstOrNull { it is ScrollView } as? ScrollView
    val needed = (options.size * 48 + 32) * resources.displayMetrics.density
    val bottomInset = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    val location = IntArray(2).also { getLocationOnScreen(it) }
    val overflow = location[1] + height + needed - (rootView.height - bottomInset)
    if (scroll != null && overflow > 0 && scroll.canScrollVertically(1)) {
        scroll.smoothScrollBy(0, overflow.toInt())
        postDelayed({ show() }, 300)
    } else show()
}
