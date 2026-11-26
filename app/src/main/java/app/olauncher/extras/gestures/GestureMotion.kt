package app.olauncher.extras.gestures

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.NavOptions
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.search.SlotSwipes
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Optional gesture animations on the home screen ("Gesture animations" in Extras → Look):
 * the home screen follows the finger during a swipe and springs back, gestures leave in their
 * direction, a long press and the double-tap lock give feedback, and a swipe with nothing set
 * on it bounces. Upstream's fling detection still decides whether a gesture counts.
 *
 * Everything is a no-op when the setting is off or animations are off (e-ink, system scale 0),
 * so the home screen then behaves exactly like upstream.
 */
object GestureMotion {

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    private const val UP_MAX_DP = 64f
    private const val DOWN_MAX_DP = 32f
    private const val SIDE_MAX_DP = 48f
    private const val EXIT_DP = 96f
    private const val BOUNCE_DP = 16f
    private const val ROW_DP = 24f
    private const val HOLD_SCALE = 0.97f
    private const val PENDING_MS = 1000L

    private val homeRows = listOf(
        R.id.homeApp1, R.id.homeApp2, R.id.homeApp3, R.id.homeApp4,
        R.id.homeApp5, R.id.homeApp6, R.id.homeApp7, R.id.homeApp8,
    )

    fun enabled(context: Context) = ExtrasPrefs(context).bool(ExtrasKeys.GESTURE_MOTION) && context.animationsOn()

    // One gesture at a time on the home screen
    // Weak: the home view goes away with its fragment
    private var rootRef: WeakReference<View>? = null
    private var sourceRef: WeakReference<View>? = null
    private var labelRef: WeakReference<TextView>? = null
    private var root: View?
        get() = rootRef?.get()
        set(value) { rootRef = value?.let(::WeakReference) }
    private var source: View?
        get() = sourceRef?.get()
        set(value) { sourceRef = value?.let(::WeakReference) }
    private var label: TextView?
        get() = labelRef?.get()
        set(value) { labelRef = value?.let(::WeakReference) }
    private var active = false
    private var claimed = false
    private var holding = false
    private var downX = 0f
    private var downY = 0f
    private var axis = GestureMath.Axis.NONE
    private var labelDir: Dir? = null

    private var pendingLaunch: Dir? = null
    private var pendingLaunchAt = 0L
    private var pendingDrawerAt = 0L

    /**
     * Touch listener hook: moves the home screen with the finger and hands [detector] the event
     * in screen coordinates while it does, so moving the screen can't change the fling maths.
     */
    fun feed(view: View, event: MotionEvent, detector: (MotionEvent) -> Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) start(view, event)
        if (!active || source !== view) return detector(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) move(event)
        val screen = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        val handled = try { detector(screen) } finally { screen.recycle() }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) end()
        return handled
    }

    private fun start(view: View, event: MotionEvent) {
        val home = findRoot(view)
        active = home != null && enabled(view.context)
        if (!active) return
        root = home
        source = view
        claimed = false
        holding = false
        axis = GestureMath.Axis.NONE
        downX = event.rawX
        downY = event.rawY
        labelDir = null
        home!!.animate().cancel()
    }

    private fun move(event: MotionEvent) {
        val home = root ?: return
        if (holding || claimed) return
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (axis == GestureMath.Axis.NONE) {
            axis = GestureMath.axis(dx, dy, ViewConfiguration.get(home.context).scaledTouchSlop.toFloat())
            if (axis == GestureMath.Axis.NONE) return
        }
        val density = home.resources.displayMetrics.density
        if (axis == GestureMath.Axis.HORIZONTAL) {
            val max = SIDE_MAX_DP * density
            val offset = GestureMath.rubberBand(dx, max)
            home.translationX = offset
            home.translationY = 0f
            home.alpha = 1f
            showLabel(home, if (dx > 0) Dir.RIGHT else Dir.LEFT, GestureMath.progress(offset, max))
        } else {
            val max = (if (dy < 0) UP_MAX_DP else DOWN_MAX_DP) * density
            val offset = GestureMath.rubberBand(dy, max)
            home.translationX = 0f
            home.translationY = offset
            home.alpha = if (dy < 0) 1f - 0.4f * GestureMath.progress(offset, max) else 1f
        }
    }

    private fun end() {
        val home = root
        active = false
        if (home != null && !claimed) settle(home)
        if (!claimed) hideLabel()
        source = null
        holding = false
    }

    /** Back to rest, smoothly. */
    private fun settle(home: View) {
        home.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
    }

    /** Claims the current gesture for an action; null when gesture animations aren't running. */
    private fun claim(): View? {
        if (!active) return null
        val home = root?.takeIf { it.isAttachedToWindow && enabled(it.context) } ?: return null
        claimed = true
        holding = false
        return home
    }

    // Long press on the home screen

    /** Upstream's long press started: shrink a little while it waits to open settings. */
    fun holdStart() {
        val home = root ?: return
        if (!active || claimed || axis != GestureMath.Axis.NONE) return
        holding = true
        home.animate().scaleX(HOLD_SCALE).scaleY(HOLD_SCALE).setStartDelay(0)
            .setDuration(Constants.LONG_PRESS_DELAY_MS).setInterpolator(Motion.easing).start()
    }

    /** The long press fired (settings opens): stay shrunk while the screen fades away. */
    fun held() {
        if (holding) claimed = true
    }

    // Directional exits

    /** Swipe up: the home screen keeps lifting while the app list slides in from below. */
    fun swipeUp() {
        val home = claim() ?: return
        pendingDrawerAt = SystemClock.uptimeMillis()
        hideLabel()
        val density = home.resources.displayMetrics.density
        home.animate().translationX(0f).translationY(-EXIT_DP * density).alpha(0f).setStartDelay(0)
            .setDuration(Motion.medium(home.context)).setInterpolator(Motion.easing).start()
        restoreIfStillHere(home)
    }

    /** Nav options for the app list opened by [swipeUp]; null keeps the action's own animations. */
    fun drawerNavOptions(): NavOptions? {
        val recent = SystemClock.uptimeMillis() - pendingDrawerAt < PENDING_MS
        pendingDrawerAt = 0L
        if (!recent) return null
        return NavOptions.Builder()
            .setEnterAnim(R.anim.slide_in_bottom)
            .setExitAnim(R.anim.fade_exit)
            .setPopEnterAnim(R.anim.slide_in_top)
            .setPopExitAnim(R.anim.fade_exit)
            .build()
    }

    /** Swipe down: a small pull while the notification shade comes down. */
    fun swipeDown() {
        val home = claim() ?: return
        hideLabel()
        val density = home.resources.displayMetrics.density
        home.animate().translationX(0f).translationY(DOWN_MAX_DP * density).alpha(0.85f).setStartDelay(0)
            .setDuration(Motion.SHORT).setInterpolator(Motion.easing)
            .withEndAction {
                home.animate().translationY(0f).alpha(1f).setDuration(Motion.medium(home.context))
                    .setInterpolator(Motion.easing).start()
            }.start()
    }

    /** Swipe left/right opens an app: the home screen leaves that way, the app comes in from the other side. */
    fun launching(dir: Dir) {
        val home = claim() ?: return
        pendingLaunch = dir
        pendingLaunchAt = SystemClock.uptimeMillis()
        hideLabel()
        val density = home.resources.displayMetrics.density
        val to = (if (dir == Dir.LEFT) -EXIT_DP else EXIT_DP) * density
        home.animate().translationX(to).translationY(0f).alpha(0f).setStartDelay(0)
            .setDuration(Motion.medium(home.context)).setInterpolator(Motion.easing).start()
        restoreIfStillHere(home)
    }

    /** A home app row's own swipe app opens: that row slides the swipe's way, the rest settles. */
    fun slotLaunching(row: View, left: Boolean) {
        val home = claim() ?: return
        val dir = if (left) Dir.LEFT else Dir.RIGHT
        pendingLaunch = dir
        pendingLaunchAt = SystemClock.uptimeMillis()
        hideLabel()
        settle(home)
        if (row.getTag(R.id.gesture_row_alpha) == null) row.setTag(R.id.gesture_row_alpha, row.alpha)
        val density = row.resources.displayMetrics.density
        row.animate().translationX((if (left) -ROW_DP else ROW_DP) * density).alpha(0.4f).setStartDelay(0)
            .setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
        restoreIfStillHere(row) { resetRow(row, animated = true) }
    }

    /** Start options for the app [launching] opened (used once, and only right after it). */
    fun launchOptions(context: Context): Bundle? {
        val dir = pendingLaunch
        val recent = SystemClock.uptimeMillis() - pendingLaunchAt < PENDING_MS
        pendingLaunch = null
        if (dir == null || !recent || !context.animationsOn()) return null
        val enter = if (dir == Dir.LEFT) R.anim.slide_in_right else R.anim.slide_in_left
        return ActivityOptions.makeCustomAnimation(context, enter, R.anim.fade_exit).toBundle()
    }

    // Feedback

    /** A swipe with nothing set on it: stretch a little further that way and come back. */
    fun nothingHere(dir: Dir) {
        val home = claim() ?: return
        hideLabel()
        val density = home.resources.displayMetrics.density
        val extra = BOUNCE_DP * density
        val x = when (dir) {
            Dir.LEFT -> -(abs(home.translationX) + extra)
            Dir.RIGHT -> abs(home.translationX) + extra
            else -> 0f
        }
        val y = when (dir) {
            Dir.UP -> -(abs(home.translationY) + extra)
            Dir.DOWN -> abs(home.translationY) + extra
            else -> 0f
        }
        home.animate().translationX(x).translationY(y).alpha(1f).setStartDelay(0)
            .setDuration(Motion.SHORT).setInterpolator(Motion.easing)
            .withEndAction { settle(home) }.start()
    }

    /** Double-tap lock: dim to black, then lock. Locks straight away when gesture animations are off. */
    fun dimThenLock(activity: Activity, lock: () -> Unit) {
        if (!enabled(activity)) return lock()
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return lock()
        val dim = content.findViewWithTag(DIM_TAG) ?: View(activity).apply {
            tag = DIM_TAG
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = false
            content.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        dim.animate().cancel()
        dim.animate().alpha(1f).setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.easing).withEndAction {
            lock()
            // If the screen didn't turn off (lock not allowed, service off), fade back in
            dim.animate().alpha(0f).setStartDelay(LOCK_FALLBACK_MS).setDuration(Motion.medium(activity))
                .setInterpolator(Motion.easing).withEndAction { (dim.parent as? ViewGroup)?.removeView(dim) }.start()
        }.start()
    }

    /** Home screen shown again: everything back at rest, instantly (no half-moved screen on return). */
    fun reset(home: View) {
        home.animate().cancel()
        home.translationX = 0f
        home.translationY = 0f
        home.alpha = 1f
        home.scaleX = 1f
        home.scaleY = 1f
        homeRows.forEach { id -> home.findViewById<View>(id)?.let { resetRow(it, animated = false) } }
        (home.rootView.findViewById<ViewGroup>(android.R.id.content))?.let { content ->
            content.findViewWithTag<View>(DIM_TAG)?.let { it.animate().cancel(); content.removeView(it) }
            content.findViewWithTag<View>(LABEL_TAG)?.let { it.animate().cancel(); content.removeView(it) }
        }
        label = null
        labelDir = null
        active = false
        claimed = false
        holding = false
        root = null
        source = null
        pendingDrawerAt = 0L
    }

    // Helpers

    private const val DIM_TAG = "inilauncher_gesture_dim"
    private const val LABEL_TAG = "inilauncher_gesture_label"
    private const val LOCK_FALLBACK_MS = 1200L

    private fun findRoot(view: View): View? =
        generateSequence(view) { it.parent as? View }.firstOrNull { it.id == R.id.mainLayout }

    private fun resetRow(row: View, animated: Boolean) {
        val alpha = row.getTag(R.id.gesture_row_alpha) as? Float ?: return
        row.setTag(R.id.gesture_row_alpha, null)
        row.animate().cancel()
        if (animated) row.animate().translationX(0f).alpha(alpha).setStartDelay(0)
            .setDuration(Motion.SHORT).setInterpolator(Motion.easing).start()
        else { row.translationX = 0f; row.alpha = alpha }
    }

    /** If the action didn't take us away (app not found, nav failed), come back after a moment. */
    private fun restoreIfStillHere(view: View, restore: () -> Unit = { settle(view) }) {
        view.postDelayed({
            if (view.isAttachedToWindow && view.hasWindowFocus()) restore()
        }, Motion.medium(view.context) + 700L)
    }

    /** The app a sideways swipe would open, faded in at the edge the screen moves away from. */
    @SuppressLint("RtlHardcoded") // follows the finger's physical direction, not the text direction
    private fun showLabel(home: View, dir: Dir, progress: Float) {
        if (labelDir != dir) {
            hideLabel()
            labelDir = dir
            val text = targetName(home.context, dir) ?: return
            createLabel(home, if (dir == Dir.RIGHT) Gravity.LEFT else Gravity.RIGHT)?.text = text
        }
        label?.alpha = 0.6f * progress
    }

    /** Small text turned on its side, so it fits in the gap that opens at the screen edge. */
    @SuppressLint("RtlHardcoded")
    private fun createLabel(home: View, side: Int): TextView? {
        val content = home.rootView.findViewById<FrameLayout>(android.R.id.content) ?: return null
        val style = home.findViewById<TextView>(R.id.homeApp1)
        val margin = 12 * home.resources.displayMetrics.density
        return TextView(home.context).apply {
            tag = LABEL_TAG
            alpha = 0f
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (style != null) {
                setTextColor(style.currentTextColor)
                typeface = style.typeface
            }
            rotation = if (side == Gravity.LEFT) -90f else 90f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            content.addView(this, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or side))
            // Rotation turns around the centre: shift it so the turned text hugs the edge
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val shift = (r - l) / 2f - v.height / 2f - margin
                v.translationX = if (side == Gravity.LEFT) -shift else shift
            }
            label = this
        }
    }

    private fun hideLabel() {
        labelDir = null
        val view = label ?: return
        label = null
        view.animate().alpha(0f).setStartDelay(0).setDuration(Motion.SHORT).setInterpolator(Motion.easing)
            .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }.start()
    }

    private fun targetName(context: Context, dir: Dir): String? {
        val left = dir == Dir.LEFT
        val slot = homeRows.indexOf(source?.id ?: View.NO_ID) + 1
        if (slot > 0) SlotSwipes.target(context, slot, left)?.let { pkg ->
            return runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
        }
        val prefs = Prefs(context)
        return if (left) prefs.appNameSwipeLeft.takeIf { prefs.swipeLeftEnabled && it.isNotEmpty() }
        else prefs.appNameSwipeRight.takeIf { prefs.swipeRightEnabled && it.isNotEmpty() }
    }
}
