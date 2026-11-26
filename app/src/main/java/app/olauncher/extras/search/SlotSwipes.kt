package app.olauncher.extras.search

import android.content.Context
import android.content.Intent
import app.olauncher.R
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.helper.showToast
import org.json.JSONObject

/**
 * Per-app swipe actions: swiping left or right on a home-screen app name opens another
 * app instead of upstream's screen-wide swipe app. Stored as {"1L":"pkg","3R":"pkg"}.
 */
object SlotSwipes {

    fun key(slot: Int, left: Boolean) = "$slot${if (left) "L" else "R"}"

    private fun load(context: Context): JSONObject =
        try { JSONObject(ExtrasPrefs(context).string(ExtrasKeys.SLOT_SWIPES) ?: "{}") } catch (e: Exception) { JSONObject() }

    fun target(context: Context, slot: Int, left: Boolean): String? =
        load(context).optString(key(slot, left)).ifBlank { null }

    fun setTarget(context: Context, slot: Int, left: Boolean, packageName: String?) {
        val json = load(context)
        if (packageName == null) json.remove(key(slot, left)) else json.put(key(slot, left), packageName)
        ExtrasPrefs(context).setString(ExtrasKeys.SLOT_SWIPES, json.toString())
    }

    /** Opens the slot's swipe app if one is set. Returns false to fall back to upstream's swipe. */
    fun handle(context: Context, slot: Int, left: Boolean): Boolean {
        val pkg = target(context, slot, left) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            context.showToast(context.getString(R.string.app_not_found))
            return true
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                app.olauncher.extras.gestures.GestureMotion.launchOptions(context))
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_app))
        }
        return true
    }
}
