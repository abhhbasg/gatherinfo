package com.metrowin.keyboard.theme

import kotlin.math.max
import kotlin.math.min

/** Colour helpers on plain ARGB ints, with no Android classes so they run in unit tests. */
object ColorMath {
    fun alpha(c: Int) = c ushr 24 and 0xFF
    fun red(c: Int) = c shr 16 and 0xFF
    fun green(c: Int) = c shr 8 and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)
    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** [t] of the way from [a] to [b] (0 = a, 1 = b), keeping the alpha of [a] blended too. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * k + 0.5f).toInt()
        return argb(ch(alpha(a), alpha(b)), ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    /** Relative luminance (WCAG), 0 black to 1 white. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
    }

    fun isDark(c: Int) = luminance(c) < 0.4

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Int) = if (luminance(background) > 0.45) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    fun lighten(c: Int, t: Float) = mix(c, 0xFFFFFFFF.toInt(), t)
    fun darken(c: Int, t: Float) = mix(c, 0xFF000000.toInt(), t)

    /** Hue (0-360), saturation and value (0-1). */
    fun hsv(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    /**
     * The colour that best stands for a picture: the pixels are grouped in coarse colour buckets, and the bucket that is most
     * colourful (and common) wins, so a grey wall with a red door gives red. Falls back to the average for a grey picture.
     */
    fun dominant(pixels: IntArray): Int {
        if (pixels.isEmpty()) return Accents.metro[Accents.DEFAULT_INDEX]
        val count = IntArray(4096); val sr = LongArray(4096); val sg = LongArray(4096); val sb = LongArray(4096)
        var tr = 0L; var tg = 0L; var tb = 0L
        for (p in pixels) {
            val r = red(p); val g = green(p); val b = blue(p)
            tr += r; tg += g; tb += b
            val i = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            count[i]++; sr[i] += r.toLong(); sg[i] += g.toLong(); sb[i] += b.toLong()
        }
        var best = -1; var bestScore = 0.0
        for (i in 0 until 4096) {
            if (count[i] == 0) continue
            val c = argb(255, (sr[i] / count[i]).toInt(), (sg[i] / count[i]).toInt(), (sb[i] / count[i]).toInt())
            val hsv = hsv(c)
            // Near-black and near-white buckets carry no colour; a vivid bucket is worth more than a pale one.
            val vividness = hsv[1] * min(1f, hsv[2] * 1.6f) * (if (hsv[2] > 0.97f && hsv[1] < 0.1f) 0f else 1f)
            val score = count[i] * (0.15 + vividness)
            if (vividness > 0.18f && score > bestScore) { bestScore = score; best = i }
        }
        if (best < 0) {
            val n = pixels.size
            return argb(255, (tr / n).toInt(), (tg / n).toInt(), (tb / n).toInt())
        }
        return argb(255, (sr[best] / count[best]).toInt(), (sg[best] / count[best]).toInt(), (sb[best] / count[best]).toInt())
    }

    /** The colour to use as an accent: the picture's colour, pushed away from muddy or washed-out values. */
    fun asAccent(c: Int): Int {
        val h = hsv(c)
        val s = h[1].coerceIn(0.45f, 0.95f)
        val v = h[2].coerceIn(0.55f, 0.95f)
        return hsvToArgb(h[0], s, v)
    }

    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1)); val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return argb(255, ((r + m) * 255 + 0.5f).toInt(), ((g + m) * 255 + 0.5f).toInt(), ((b + m) * 255 + 0.5f).toInt())
    }
}
