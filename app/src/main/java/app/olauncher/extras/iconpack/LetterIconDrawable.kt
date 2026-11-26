package app.olauncher.extras.iconpack

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * Letter dot icon: the app's monogram in a circle.
 * - [Style.PLAIN] ("Letter dots"): a soft circle with a thin ring, all in the text colour.
 * - [Style.FILLED] ("Colour letter dots"): a solid disc in [accent], with white or near-black
 *   letters, whichever reads better on it.
 * - [Style.OUTLINE] ("Colour outline dots"): the plain look, but ring and letters in [accent].
 * Without an [accent] every style falls back to PLAIN.
 * PLAIN and OUTLINE sit on a mostly solid disc in the opposite of the text colour (white under
 * dark text, black under light text), so they stay readable on a busy or same-coloured wallpaper.
 */
class LetterIconDrawable(private val letters: String, color: Int, style: Style = Style.PLAIN, accent: Int? = null) : Drawable() {

    enum class Style { PLAIN, FILLED, OUTLINE }

    private val style = if (accent == null) Style.PLAIN else style
    private val tint = accent ?: color

    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val light = ColorUtils.calculateLuminance(color) > 0.5
        this.color = ColorUtils.setAlphaComponent(if (light) Color.BLACK else Color.WHITE, 0xCC)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (this@LetterIconDrawable.style == Style.FILLED) tint else ColorUtils.setAlphaComponent(tint, 0x2E)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        this.color = if (this@LetterIconDrawable.style == Style.OUTLINE) tint else ColorUtils.setAlphaComponent(tint, 0x80)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = when {
            this@LetterIconDrawable.style != Style.FILLED -> tint
            ColorUtils.calculateLuminance(tint) > 0.5 -> Color.rgb(0x20, 0x20, 0x20)
            else -> Color.WHITE
        }
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = b.width().coerceAtMost(b.height()) / 2f
        if (style != Style.FILLED) {
            // At least 1.5dp, so the ring still shows on the small sub list and app list dots
            ring.strokeWidth = maxOf(r * 0.08f, 1.5f * Resources.getSystem().displayMetrics.density)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - ring.strokeWidth / 2, backdrop)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - ring.strokeWidth / 2, fill)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - ring.strokeWidth / 2, ring)
        } else {
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill)
        }
        // One letter large; two letters shrunk to stay well inside the circle
        text.textSize = if (letters.length > 1) r * 0.85f else r * 1.05f
        val maxWidth = r * 1.3f
        val width = text.measureText(letters)
        if (width > maxWidth) text.textSize *= maxWidth / width
        val baseline = b.exactCenterY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(letters, b.exactCenterX(), baseline, text)
    }

    override fun setAlpha(alpha: Int) {
        listOf(backdrop, fill, ring, text).forEach { it.alpha = it.alpha * alpha / 255 }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(backdrop, fill, ring, text).forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
