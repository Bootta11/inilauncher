package app.olauncher.extras.search

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import app.olauncher.R
import app.olauncher.helper.Motion
import app.olauncher.helper.animationsOn
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A–Z (+ #) strip on the app list's edge. Drag along it to jump to a letter.
 *
 * While touched, the letters near the finger bulge out towards the list in a wave (fisheye), and a
 * big bubble with the current letter shows beside the thumb, so it is readable while the finger
 * covers the strip. Changing letter slides the old one out and pops the new one in.
 * The bubble and wave draw outside the view's 32dp bounds, so the parent must not clip children
 * (set in [onAttachedToWindow]); the touch area stays the thin strip.
 *
 * Pulling sideways off the strip, towards the list, while on a letter picks one of that letter's
 * apps: the letter locks, the bubble fades, and each further step selects the next app
 * ([pickCount], [onPickSlot]; the host orders them, most used first). Lifting the finger then
 * opens it ([onPick]); sliding back onto the strip cancels the pick.
 *
 * The bottom slot is a keyboard button; a double tap anywhere on the letters is reported too
 * (both open/close the keyboard, see DrawerExtras). Letters without apps can be dimmed.
 *
 * Drawing is kept cheap for 60–120 fps: the growing wave letters and the shadowed bubble are
 * rendered once into small bitmaps ("sprites") and only scaled and faded per frame, instead of
 * re-rasterizing shadowed text at a new size every frame. onDraw allocates nothing.
 */
class AlphabetScrollerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The finger reached a new letter; return true when the list moved (then it ticks). */
    var onLetter: (Char) -> Boolean = { true }
    /** A new drag or tap on the letters started. */
    var onTouchStart: () -> Unit = {}
    /** Second tap of a double tap on the letters (instead of [onTouchStart]/[onLetter]). */
    var onDoubleTap: () -> Unit = {}
    /** The keyboard button at the bottom was tapped. */
    var onKeyboardButton: () -> Unit = {}
    /** How many apps can be picked for a letter by pulling sideways; 0 turns picking off. */
    var pickCount: (Char) -> Int = { 0 }
    /** The pull reached app [slot] of the letter (-1 = cancelled); return true when the highlight moved. */
    var onPickSlot: (letter: Char, slot: Int) -> Boolean = { _, _ -> false }
    /** The finger was lifted while an app was picked. */
    var onPick: () -> Unit = {}

    private val letters = LetterIndex.LETTERS
    private val labels = Array(letters.size) { letters[it].toString() }

    /** Letters that have apps of their own; the others are drawn fainter. */
    private var present = BooleanArray(letters.size) { true }

    fun setPresent(value: BooleanArray) {
        if (value.size != present.size || value.contentEquals(present)) return
        present = value.copyOf()
        invalidate()
    }

    private var active = -1
    private var previous = -1
    /** +1 when the finger moved down to the new letter, -1 when up: the bubble's slide direction. */
    private var direction = 1

    // Finger position and its smoothed follower (drives the wave and bubble so they glide)
    private var touchY = 0f
    private var smoothY = 0f

    /** 0 = idle strip, 1 = fully expanded (wave + bubble). */
    private var progress = 0f
    /** 0 → 1 after each letter change: old letter slides out, new one pops in. */
    private var swap = 1f

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val idleColor = context.getColorFromAttr(R.attr.primaryColorTrans50)
    private val strongColor = context.getColorFromAttr(R.attr.primaryColor)
    private val inverseColor = context.getColorFromAttr(R.attr.primaryInverseColor)
    private val shadowColor = context.getColorFromAttr(R.attr.primaryTextShadowColor)
    private val idleAlpha = idleColor ushr 24
    private val strongAlpha = strongColor ushr 24

    private val baseTextSize = sp(11f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = baseTextSize
        color = idleColor
        setShadowLayer(2 * dp, 0f, 0f, shadowColor)
    }
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = strongColor }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(36f)
        isFakeBoldText = true
        color = inverseColor
    }

    // Wave shape
    private val waveShift = 30 * dp              // how far the nearest letter moves towards the list
    private val waveScale = 1.6f                 // extra size of the nearest letter (2.6× total)
    private val maxTextSize = baseTextSize * (1 + waveScale)

    // Bubble shape
    private val bubbleSize = 68 * dp
    private val bubbleGap = 64 * dp              // from the strip's centre to the bubble's near edge
    private val bubbleLift = 36 * dp             // above the finger, clear of the thumb
    private val bubbleShadowPad = 14 * dp
    private val bubbleRect = RectF()

    // Sprites: each letter at full wave size (regular + bold), and the bubble with its shadow
    private var regularSprites: Array<Bitmap>? = null
    private var boldSprites: Array<Bitmap>? = null
    private var spriteBaseline = 0f              // from a sprite's top to the text baseline
    private var bubbleSprite: Bitmap? = null
    private var bubbleSpriteDir = 0
    private val dst = RectF()

    // Keyboard button: the bottom slot of the strip
    private val keySlot = 40 * dp
    private val keyIconSize = (20 * dp).toInt()
    private val keyIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_extras_keyboard)?.mutate()
    private var keyPressed = false

    // What the current gesture is
    private var gesture = GESTURE_NONE
    private val doubleTap = android.view.ViewConfiguration.get(context).let {
        DoubleTap(android.view.ViewConfiguration.getDoubleTapTimeout().toLong(), it.scaledTouchSlop.toFloat(), it.scaledDoubleTapSlop.toFloat())
    }

    private var step = 0f
    private var waveRadius = 1f                  // letters this far from the finger are affected
    private var inward = -1                      // -1 when the strip is on the right edge, +1 on the left

    // Pull-to-pick
    private val pullStart = 48 * dp              // from the strip's centre: below this the letters scroll
    private val pullStepMax = 36 * dp
    private var picking = false
    private var pickSlot = -1
    /** 1 = bubble shown, 0 = faded while picking. */
    private var bubbleAlpha = 1f
    private var bubbleFade: ValueAnimator? = null

    private var showAnimator: ValueAnimator? = null
    private var swapAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
    }

    override fun onDetachedFromWindow() {
        showAnimator?.cancel(); swapAnimator?.cancel(); bubbleFade?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        step = ((h - keySlot) / letters.size).coerceAtLeast(1f)
        waveRadius = (4.5f * step).coerceAtLeast(1f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val parentWidth = (parent as? View)?.width ?: 0
        inward = if (left + (right - left) / 2 > parentWidth / 2) -1 else 1
    }

    override fun onDraw(canvas: Canvas) {
        // Glide the wave/bubble towards the finger each frame
        val diff = touchY - smoothY
        if (abs(diff) > 0.5f && animatorsEnabled()) {
            smoothY += diff * 0.5f
            postInvalidateOnAnimation()
        } else smoothY = touchY

        val cx = width / 2f
        val dir = inward
        val shown = progress > 0f

        // Soft track behind the strip while touched
        if (shown) {
            trackPaint.alpha = (progress.coerceIn(0f, 1f) * 28).toInt()
            val r = width / 2f - 3 * dp
            canvas.drawRoundRect(cx - r, 0f, cx + r, height.toFloat(), r, r, trackPaint)
        }

        val regular = regularSprites
        val bold = boldSprites
        for (i in letters.indices) {
            val y = step * i + step / 2
            val f = if (shown) influence(y) * progress else 0f
            val dim = if (present[i]) 1f else DIM
            if (f < 0.02f || regular == null || bold == null) {
                // Resting letter: plain text at a fixed size (its glyphs stay cached)
                paint.alpha = (idleAlpha * dim).toInt()
                canvas.drawText(labels[i], cx, y + baseTextSize / 3, paint)
                continue
            }
            val isActive = i == active
            val sprite = if (isActive || f > 0.6f) bold[i] else regular[i]
            val k = (1 + waveScale * f.coerceAtMost(1f)) / (1 + waveScale)
            val x = cx + dir * waveShift * f
            val baseline = y + maxTextSize * k / 3
            val w = sprite.width * k
            dst.set(x - w / 2, baseline - spriteBaseline * k, x + w / 2, baseline - spriteBaseline * k + sprite.height * k)
            val a = if (isActive) strongAlpha else ((idleAlpha + (strongAlpha - idleAlpha) * f.coerceAtMost(1f)) * dim).toInt()
            spritePaint.alpha = a.coerceIn(0, 255)
            canvas.drawBitmap(sprite, null, dst, spritePaint)
        }

        keyIcon?.let { icon ->
            val half = keyIconSize / 2
            val cy = (height - keySlot / 2).toInt()
            icon.setBounds(cx.toInt() - half, cy - half, cx.toInt() + half, cy + half)
            icon.setTint(if (keyPressed) strongColor else idleColor)
            icon.draw(canvas)
        }

        if (progress > 0.01f) drawBubble(canvas, cx, dir)
    }

    /** Raised-cosine bell: 1 at the finger, 0 at [waveRadius] and beyond. */
    private fun influence(y: Float): Float {
        val d = abs(y - smoothY) / waveRadius
        return if (d >= 1f) 0f else ((cos(Math.PI * d) + 1) / 2).toFloat()
    }

    private fun drawBubble(canvas: Canvas, cx: Float, dir: Int) {
        val index = if (active >= 0) active else previous
        if (index < 0) return
        val sprite = bubbleSprite(dir)
        val size = bubbleSize
        val top = (smoothY - bubbleLift - size / 2).coerceIn(-size / 3, height - size)
        val nearX = cx + dir * bubbleGap
        val farX = nearX + dir * size
        bubbleRect.set(min(nearX, farX), top, max(nearX, farX), top + size)

        // Grow out of the sharp corner
        val pivotX = if (dir < 0) bubbleRect.right else bubbleRect.left
        val pivotY = bubbleRect.bottom
        val alpha = progress.coerceIn(0f, 1f) * bubbleAlpha
        if (alpha <= 0.01f) return
        canvas.save()
        canvas.scale(progress, progress, pivotX, pivotY)
        spritePaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(sprite, bubbleRect.left - bubbleShadowPad, bubbleRect.top - bubbleShadowPad, spritePaint)

        // Rectangle clip (cheap) keeps the sliding letters inside the bubble
        canvas.save()
        canvas.clipRect(bubbleRect)
        val textX = bubbleRect.centerX()
        val baseY = bubbleRect.centerY() + bubbleTextPaint.textSize / 3
        val travel = size * 0.6f
        // Old letter leaves in the direction of travel
        val old = previous
        if (old >= 0 && old != index && swap < 1f) {
            bubbleTextPaint.alpha = ((1 - swap) * alpha * 255).toInt()
            drawScaled(canvas, old, textX, baseY - direction * travel * swap, 1f - 0.3f * swap)
        }
        // New letter comes in from the other side with a little overshoot
        bubbleTextPaint.alpha = (min(1f, swap * 1.5f) * alpha * 255).toInt()
        drawScaled(canvas, index, textX, baseY + direction * travel * (1 - swap), 0.6f + 0.4f * swap)
        canvas.restore()
        canvas.restore()
    }

    private fun drawScaled(canvas: Canvas, index: Int, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.scale(scale, scale, x, y - bubbleTextPaint.textSize / 3)
        canvas.drawText(labels[index], x, y, bubbleTextPaint)
        canvas.restore()
    }

    // ---- Sprites (built once, on the first touch) ----

    private fun ensureSprites() {
        if (regularSprites != null) return
        val p = Paint(paint).apply { textSize = maxTextSize; color = strongColor or (0xFF shl 24) }
        val pad = 3 * dp
        val fm = p.fontMetrics
        spriteBaseline = pad - fm.ascent
        val h = ceil(fm.descent - fm.ascent + 2 * pad).toInt()
        fun build(bold: Boolean) = Array(letters.size) { i ->
            p.isFakeBoldText = bold
            val w = ceil(p.measureText(labels[i]) + 2 * pad).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawText(labels[i], w / 2f, spriteBaseline, p)
            }
        }
        regularSprites = build(false)
        boldSprites = build(true)
    }

    /** The teardrop bubble (three round corners, the one facing the finger sharp) with its shadow. */
    private fun bubbleSprite(dir: Int): Bitmap {
        bubbleSprite?.let { if (bubbleSpriteDir == dir) return it }
        val pad = bubbleShadowPad
        val total = ceil(bubbleSize + 2 * pad).toInt()
        val bitmap = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val r = bubbleSize / 2
        val radii = FloatArray(8) { r }
        val sharp = if (dir < 0) 4 else 6   // bottom-right when bubble is left of the strip, else bottom-left
        radii[sharp] = 4 * dp; radii[sharp + 1] = 4 * dp
        val path = Path().apply { addRoundRect(RectF(pad, pad, pad + bubbleSize, pad + bubbleSize), radii, Path.Direction.CW) }
        Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strongColor
            setShadowLayer(10 * dp, 0f, 3 * dp, 0x66000000)
        })
        bubbleSprite = bitmap
        bubbleSpriteDir = dir
        return bitmap
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                when {
                    y >= height - keySlot -> {
                        gesture = GESTURE_KEY
                        doubleTap.cancel()
                        keyPressed = true
                        invalidate()
                    }
                    doubleTap.down(event.eventTime, y) -> {
                        gesture = GESTURE_DOUBLE
                        performHapticFeedback(confirmHaptic)
                        onDoubleTap()
                    }
                    else -> {
                        gesture = GESTURE_LETTERS
                        picking = false
                        pickSlot = -1
                        bubbleFade?.cancel()
                        bubbleAlpha = 1f
                        ensureSprites()
                        touchY = y.coerceIn(0f, height - keySlot)
                        smoothY = touchY
                        onTouchStart()
                        animateShown(true)
                        follow(y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == GESTURE_LETTERS && !pull(event.x)) {
                    doubleTap.move(y)
                    touchY = y.coerceIn(0f, height - keySlot)
                    follow(y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = event.actionMasked == MotionEvent.ACTION_UP
                when (gesture) {
                    GESTURE_KEY -> {
                        keyPressed = false
                        invalidate()
                        if (up && y >= height - keySlot * 1.5f) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onKeyboardButton()
                        }
                    }
                    GESTURE_DOUBLE -> doubleTap.up(event.eventTime, y)
                    GESTURE_LETTERS -> {
                        if (picking) {
                            doubleTap.cancel()
                            if (up && pickSlot >= 0) {
                                performHapticFeedback(confirmHaptic)
                                onPick()
                            } else if (active >= 0) onPickSlot(letters[active], -1)
                            picking = false
                            pickSlot = -1
                        } else if (up) doubleTap.up(event.eventTime, y) else doubleTap.cancel()
                        previous = active
                        active = -1
                        animateShown(false)
                    }
                }
                gesture = GESTURE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Pull-to-pick at finger [x]: true while picking (the letter is locked, Y is ignored).
     * Entering locks the letter and fades the bubble; leaving clears the pick.
     */
    private fun pull(x: Float): Boolean {
        val letter = if (active >= 0) letters[active] else return false
        val pulled = (x - width / 2f) * inward
        val count = if (pulled >= pullStart) pickCount(letter) else 0
        if (count <= 0) {
            if (picking) {
                picking = false
                pickSlot = -1
                onPickSlot(letter, -1)
                fadeBubble(1f)
            }
            return false
        }
        if (!picking) {
            picking = true
            doubleTap.cancel()
            fadeBubble(0f)
        }
        // Spread the letter's apps over the room up to the list's far edge, at most one step per 36 dp
        val centre = left + width / 2f
        val parentWidth = (parent as? View)?.width?.toFloat() ?: 0f
        val room = (if (inward < 0) centre else parentWidth - centre) - pullStart - 24 * dp
        val step = min(pullStepMax, room / count).coerceAtLeast(1f)
        val slot = ((pulled - pullStart) / step).toInt().coerceIn(0, count - 1)
        if (slot != pickSlot) {
            pickSlot = slot
            if (onPickSlot(letter, slot)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return true
    }

    private fun fadeBubble(to: Float) {
        bubbleFade?.cancel()
        if (!context.animationsOn()) { bubbleAlpha = to; invalidate(); return }
        bubbleFade = ValueAnimator.ofFloat(bubbleAlpha, to).apply {
            duration = Motion.SHORT
            interpolator = Motion.easing
            addUpdateListener { bubbleAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Letter under the finger at [y]; jumps (and ticks) when it changes. */
    private fun follow(y: Float) {
        val index = (y / (height - keySlot) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (index != active) {
            val old = active
            direction = if (old < 0 || old < index) 1 else -1
            previous = old
            active = index
            if (old >= 0) animateSwap() else swap = 1f
            if (onLetter(letters[index])) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        postInvalidateOnAnimation()
    }

    private val confirmHaptic =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY

    private fun animateShown(show: Boolean) {
        showAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(progress, if (show) 1f else 0f).apply {
            duration = if (show) 320 else 220
            interpolator = if (show) OvershootInterpolator(1.6f) else DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateSwap() {
        swapAnimator?.cancel()
        swap = 0f
        swapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { swap = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    companion object {
        private const val GESTURE_NONE = 0
        private const val GESTURE_LETTERS = 1
        private const val GESTURE_DOUBLE = 2
        private const val GESTURE_KEY = 3
        /** Alpha factor for letters without apps. */
        private const val DIM = 0.4f

        private fun animatorsEnabled() =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

        /** Index of the first label starting with [letter] ('#' = digits/symbols); -1 if none. Pure, unit tested. */
        fun indexFor(labels: List<String>, letter: Char): Int =
            LetterIndex.build(labels).jump[LetterIndex.LETTERS.indexOf(letter).coerceAtLeast(0)]
    }
}
