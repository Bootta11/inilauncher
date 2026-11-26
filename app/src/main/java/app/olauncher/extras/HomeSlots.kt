package app.olauncher.extras

import android.content.Context
import androidx.core.content.edit
import app.olauncher.extras.search.SlotSwipes

/**
 * Moves a home app to another position: everything stored for its slot (name, app, activity,
 * user, shortcut, and its per-slot swipes) goes along, and the slots in between shift by one.
 * Upstream keeps each slot in numbered preferences ("APP_NAME_3"…), so they are moved by key.
 */
object HomeSlots {

    private const val UPSTREAM_PREFS = "app.olauncher"
    private val STRING_KEYS = listOf("APP_NAME_", "APP_PACKAGE_", "APP_ACTIVITY_CLASS_NAME_", "APP_USER_", "SHORTCUT_ID_")
    private const val BOOL_KEY = "IS_SHORTCUT_"

    /**
     * After moving slot [from] to [to] (1-based), which old slot each slot holds: the result maps
     * new slot → old slot, for the slots that change. Pure, unit tested.
     */
    fun sources(from: Int, to: Int): Map<Int, Int> {
        if (from == to) return emptyMap()
        val range = if (from < to) from..to else to..from
        val order = range.toMutableList()
        order.add(to - range.first, order.removeAt(from - range.first))
        return range.zip(order).toMap()
    }

    fun move(context: Context, from: Int, to: Int) {
        val moves = sources(from, to)
        if (moves.isEmpty()) return
        val prefs = context.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE)
        val strings = STRING_KEYS.associateWith { key -> moves.values.associateWith { prefs.getString(key + it, "") } }
        val bools = moves.values.associateWith { prefs.getBoolean(BOOL_KEY + it, false) }
        prefs.edit(commit = true) {
            moves.forEach { (slot, old) ->
                STRING_KEYS.forEach { key -> putString(key + slot, strings.getValue(key)[old]) }
                putBoolean(BOOL_KEY + slot, bools.getValue(old))
            }
        }
        // Per-slot swipes follow their app
        val swipes = moves.values.associateWith { old -> SlotSwipes.target(context, old, true) to SlotSwipes.target(context, old, false) }
        if (swipes.values.all { it.first == null && it.second == null }) return
        moves.forEach { (slot, old) ->
            val (left, right) = swipes.getValue(old)
            SlotSwipes.setTarget(context, slot, true, left)
            SlotSwipes.setTarget(context, slot, false, right)
        }
    }
}
