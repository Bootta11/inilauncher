package app.olauncher.extras.iconpack

/** The colour an icon "is" (Viber purple, Spotify green), for "Colour letter dots". Pure, unit tested. */
object IconColour {

    private const val BUCKETS = 12

    /**
     * Dominant colour of ARGB [pixels]: near-transparent pixels are skipped, the rest are grouped
     * by hue weighted by saturation, and the strongest hue group is averaged. So the white or grey
     * background of an adaptive icon doesn't win over its logo, and a multi-colour logo (Chrome)
     * gets one of its real colours instead of a muddy average. All grey → the plain average;
     * nothing visible → null.
     */
    fun dominant(pixels: IntArray): Int? {
        val weight = DoubleArray(BUCKETS); val sr = DoubleArray(BUCKETS); val sg = DoubleArray(BUCKETS); val sb = DoubleArray(BUCKETS)
        var pr = 0L; var pg = 0L; var pb = 0L; var count = 0
        for (p in pixels) {
            if ((p ushr 24) < 0x80) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            pr += r; pg += g; pb += b; count++
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            if (max == min) continue
            val saturation = (max - min).toDouble() / max
            // Squared, so strong colours dominate faint tints; very dark pixels count little
            val w = saturation * saturation * (max / 255.0)
            if (w < 0.04) continue
            val bucket = (hue(r, g, b, max, min) / 360.0 * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            weight[bucket] += w; sr[bucket] += r * w; sg[bucket] += g * w; sb[bucket] += b * w
        }
        if (count == 0) return null
        val best = weight.indices.maxByOrNull { weight[it] } ?: 0
        // Only a sprinkle of coloured pixels (anti-aliasing) on an otherwise grey icon: stay grey
        if (weight.sum() < count * 0.02) return rgb(avg(pr, count), avg(pg, count), avg(pb, count))
        val w = weight[best]
        return rgb(Math.round(sr[best] / w).toInt(), Math.round(sg[best] / w).toInt(), Math.round(sb[best] / w).toInt())
    }

    /**
     * [colour] made readable as thin lines (ring, letters) where [textColour] is: blended toward
     * the text colour until it has about 4.5:1 contrast against the opposite of the text colour
     * (the background it's meant for). Light text: dark navy is lifted; dark text: pale yellow is
     * darkened. Already readable colours are unchanged.
     */
    fun readable(colour: Int, textColour: Int): Int {
        val opaque = colour or (0xFF shl 24)
        val text = textColour or (0xFF shl 24)
        val background = if (luminance(text) > 0.5) 0.0 else 1.0 // black or white
        var c = opaque
        var step = 0
        while (contrast(luminance(c), background) < 4.5 && step < 10) {
            step++
            c = blend(opaque, text, step / 10.0)
        }
        return c
    }

    /** WCAG relative luminance (0 black … 1 white). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    private fun blend(a: Int, b: Int, t: Double): Int {
        fun mix(shift: Int) = Math.round(((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return rgb(mix(16), mix(8), mix(0))
    }

    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Double {
        val d = (max - min).toDouble()
        val h = when (max) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * 60
    }

    private fun avg(sum: Long, n: Int) = (sum / n).toInt()

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
