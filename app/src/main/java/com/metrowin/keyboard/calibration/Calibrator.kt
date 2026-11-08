package com.metrowin.keyboard.calibration

import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import kotlin.math.abs
import kotlin.math.hypot

/** The three size settings the calibration can change. */
data class Metrics(val heightPct: Int, val gapDp: Int, val widthPct: Int)

/** What a calibration found. [bias] is null when correcting the touches did not help on touches it had not seen. */
class Result(
    val bias: DoubleArray?,
    /** Share of taps that landed on another key than the one meant, without and with the correction (on held-out taps). */
    val before: Double,
    val after: Double,
    /** Misses left after the correction, by kind: another row, or another key of the same row. */
    val vertical: Double,
    val horizontal: Double,
    /** Average landing point relative to the centre of the key meant, in pixels (positive: right, down). */
    val meanDx: Double,
    val meanDy: Double,
    val samples: Int,
    /** Mean distance of a tap from the centre of the key meant, in pixels, without and with the correction (on held-out taps): the precision, which counts millimetres, not only misses. */
    val errBeforePx: Double,
    val errAfterPx: Double,
    /** Spread of the taps around where they land after the correction, in pixels, and the size of the keys they were measured against. */
    val sigmaX: Double,
    val sigmaY: Double,
    val keyW: Double,
    val keyH: Double,
)

/**
 * The calibration: the user types fixed sentences, and since the letter meant is always known, every tap tells where the finger lands
 * relative to the key it aimed at. From those taps it learns an offset of the touch point that depends on where on the keyboard the
 * tap is (a thumb errs differently on the left and the right, at the top and the bottom, and along the arc it sweeps), and how much the
 * size of the keys should change. The keys are not moved: the touch is corrected before the key under it is looked up.
 *
 * The sentence moves on only when the right letter is typed, so a wrong tap is kept as a sample (misses are the most informative taps)
 * and the position in the sentence never gets out of step with what the user does. An accented letter is asked for the way it is typed:
 * the base key held and the accent chosen; the finger lands on the base key either way, so it measures the same thing.
 *
 * A tap is stored as its offset from the key meant, in pixels, not as a position: the keys may be resized while the test runs
 * (see [liveBias] and [advise]) and the finger's error in millimetres does not change when they do.
 */
class Calibrator(sentences: List<String>) {
    private class Sample(val label: String, val dx: Float, val dy: Float)
    private class Pt(val key: Key, val x: Float, val y: Float)

    private val sentences = ArrayList(sentences)
    private val samples = ArrayList<Sample>()
    private var keys: List<Key> = emptyList()
    private var w = 0
    private var h = 0
    private var sentenceIdx = 0
    private var charIdx = 0

    var taps = 0; private set
    var misses = 0; private set
    var onChange: (() -> Unit)? = null

    /** The correction learned so far, used by the keyboard while the test runs so the next sentence is already typed with it. */
    @Volatile var liveBias: DoubleArray? = null

    val sentenceCount get() = sentences.size
    val sentenceNumber get() = minOf(sentenceIdx + 1, sentences.size)
    val sampleCount get() = samples.size
    val done get() = sentenceIdx >= sentences.size
    val sentence: String get() = sentences.getOrElse(sentenceIdx) { "" }
    val position get() = charIdx
    val canFinish get() = samples.size >= MIN_SAMPLES

    /** More sentences after the last one, keeping everything learned so far. */
    fun more(extra: List<String>) { sentences += extra }

    /** For an accented letter: the key to hold and the letter to choose; null when the letter is a key of its own. */
    fun holdHint(): Pair<String, String>? {
        if (done || sentence[charIdx] == ' ') return null
        val c = sentence[charIdx].toString()
        val k = letterKey(sentence[charIdx]) ?: return null
        return if (k.label != c) k.label to c else null
    }

    /**
     * One tap of the keyboard: where the finger went down, the keys as laid out, the key the view resolved the touch to and the text it
     * typed (the accent chosen, for a hold).
     */
    fun tap(x: Float, y: Float, keys: List<Key>, w: Int, h: Int, hit: Key, typed: String = hit.label) {
        if (done) return
        // On the symbols page there is nothing to measure: the sentence waits for the letters again.
        if (keys.none { it.type == KeyType.CHAR && it.label.firstOrNull()?.isLetter() == true }) return
        this.keys = keys; this.w = w; this.h = h
        // A letter the keyboard has no key for (another layout than the sentences) is skipped, not waited for.
        while (!done && sentence[charIdx] != ' ' && letterKey(sentence[charIdx]) == null) advance()
        if (done) return
        val c = sentence[charIdx]
        if (c == ' ') { if (hit.type == KeyType.SPACE) advance(); onChange?.invoke(); return }
        val meant = letterKey(c)!!
        if (hypot((x - meant.centerX) / meant.width, (y - meant.centerY) / meant.height) <= MAX_AIM_ERROR) {
            samples += Sample(meant.label, x - meant.centerX, y - meant.centerY); taps++
            if (hit !== meant) misses++
        }
        if (typed.lowercase() == c.toString()) advance()
        onChange?.invoke()
    }

    /** The key of a letter: the key itself, or the key that offers it on a hold (an accent). */
    private fun letterKey(c: Char): Key? {
        val s = c.toString()
        return keys.firstOrNull { it.type == KeyType.CHAR && it.label == s }
            ?: keys.firstOrNull { it.type == KeyType.CHAR && it.label.firstOrNull()?.isLetter() == true && s in it.popups }
    }

    private fun advance() { if (++charIdx >= sentence.length) { sentenceIdx++; charIdx = 0 } }

    /** Learns from the taps so far; null while there are fewer than [min]. */
    fun fit(min: Int = MIN_SAMPLES): Result? {
        val ref = keys.firstOrNull { it.type == KeyType.CHAR } ?: return null
        val pts = samples.mapNotNull { s -> keys.firstOrNull { it.type == KeyType.CHAR && it.label == s.label }?.let { Pt(it, it.centerX + s.dx, it.centerY + s.dy) } }
        if (pts.size < min) return null
        // Fold by fold: fit on four fifths of the taps, measure on the fifth left out, for no correction, a linear one and a curved one.
        // A model is kept only if it helps on taps it was not made from, and the curved one only if it beats the linear one by a margin.
        // The measure is the distance from the centre of the key meant, so a finger that is a few millimetres off is corrected even when
        // it still hits the right key; counting misses alone would ignore it.
        val err = DoubleArray(3); val miss = IntArray(3)
        for (f in 0 until FOLDS) {
            val train = pts.filterIndexed { i, _ -> i % FOLDS != f }; val test = pts.filterIndexed { i, _ -> i % FOLDS == f }
            for (m in 0..2) {
                val c = if (m == 0) null else solve(train, ref, if (m == 1) 3 else 6)
                err[m] += distanceOf(test, c, ref); miss[m] += missesOf(test, c, ref)
            }
        }
        val best = if (err[2] < err[1] * CURVE_GAIN) 2 else 1
        val model = if (err[best] < err[0] * MIN_GAIN) best else 0
        val bias = if (model == 0) null else solve(pts, ref, if (model == 1) 3 else 6)
        var vert = 0; var horiz = 0
        val rx = ArrayList<Double>(); val ry = ArrayList<Double>()
        for (p in pts) {
            val (px, py) = Bias.correct(p.x, p.y, w, h, ref.width, ref.height, bias)
            rx += (px - p.key.centerX).toDouble(); ry += (py - p.key.centerY).toDouble()
            val k = nearest(px, py) ?: continue
            if (k === p.key) continue
            if (k.top != p.key.top) vert++ else horiz++
        }
        val n = pts.size.toDouble()
        return Result(
            bias, miss[0] / n, miss[model] / n, vert / n, horiz / n,
            pts.sumOf { (it.x - it.key.centerX).toDouble() } / n, pts.sumOf { (it.y - it.key.centerY).toDouble() } / n, pts.size,
            err[0] / n, err[model] / n, spread(rx), spread(ry), ref.width.toDouble(), ref.height.toDouble(),
        )
    }

    private fun spread(v: List<Double>): Double { val m = v.average(); return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }

    private fun distanceOf(list: List<Pt>, c: DoubleArray?, ref: Key): Double = list.sumOf { p ->
        val (px, py) = Bias.correct(p.x, p.y, w, h, ref.width, ref.height, c)
        hypot((px - p.key.centerX).toDouble(), (py - p.key.centerY).toDouble())
    }

    private fun nearest(x: Float, y: Float): Key? {
        var best: Key? = null; var bestD = Float.MAX_VALUE
        for (k in keys) { val d = k.distanceSq(x, y); if (d == 0f) return k; if (d < bestD) { bestD = d; best = k } }
        return best
    }

    private fun missesOf(list: List<Pt>, c: DoubleArray?, ref: Key): Int = list.count { p ->
        val (px, py) = Bias.correct(p.x, p.y, w, h, ref.width, ref.height, c)
        nearest(px, py) !== p.key
    }

    /**
     * Weighted least squares of the landing offset (in key sizes) on the [n] position terms, one fit per axis; the slopes are shrunk a
     * little against noise. Three rounds with Huber weights make a wild tap (a thumb that slipped) count for less than a typical one.
     */
    private fun solve(list: List<Pt>, ref: Key, n: Int): DoubleArray {
        val wts = DoubleArray(list.size) { 1.0 }
        var c = DoubleArray(2 * n)
        repeat(ROUNDS) {
            val a = Array(n) { DoubleArray(n) }
            val bx = DoubleArray(n); val by = DoubleArray(n)
            list.forEachIndexed { k, p ->
                val f = Bias.features(p.x, p.y, w, h, n)
                val ex = (p.x - p.key.centerX) / ref.width.toDouble(); val ey = (p.y - p.key.centerY) / ref.height.toDouble()
                for (i in 0 until n) { for (j in 0 until n) a[i][j] += wts[k] * f[i] * f[j]; bx[i] += wts[k] * f[i] * ex; by[i] += wts[k] * f[i] * ey }
            }
            val lambda = SHRINK * list.size
            for (i in 1 until n) a[i][i] += lambda
            c = gauss(a, bx, n) + gauss(a, by, n)
            list.forEachIndexed { k, p ->
                val f = Bias.features(p.x, p.y, w, h, n)
                var px = 0.0; var py = 0.0
                for (i in 0 until n) { px += c[i] * f[i]; py += c[n + i] * f[i] }
                val r = hypot((p.x - p.key.centerX) / ref.width.toDouble() - px, (p.y - p.key.centerY) / ref.height.toDouble() - py)
                wts[k] = if (r <= HUBER) 1.0 else HUBER / r
            }
        }
        return c
    }

    companion object {
        const val MIN_SAMPLES = 60
        private const val FOLDS = 5
        private const val ROUNDS = 3
        private const val HUBER = 0.3              // in key sizes: a tap off by more than this counts for less
        private const val MAX_AIM_ERROR = 1.5      // taps farther than this many keys from the key meant are not aiming at it
        private const val SHRINK = 0.005
        private const val MIN_GAIN = 0.95          // the correction must cut the mean distance from the key centre by at least 5 % on unseen taps
        private const val CURVE_GAIN = 0.97        // the curved model must cut it by 3 % more than the linear one to be used
        private const val SPREAD_KEYS = 4.4        // a key should be about 4.4 standard deviations of the taps wide (97 % of the taps inside it)
        private const val TARGET_MISS = 0.03       // shortcut: thresholds are guesses, tune them with data from real users

        /** The calibration being run, if any; the keyboard reports its taps to it and uses its [liveBias] instead of the saved correction. */
        @Volatile var current: Calibrator? = null

        /** The size settings to try next, from the misses left after the correction and from how wide the taps spread. Never makes the keyboard smaller. */
        fun advise(m: Metrics, r: Result): Metrics {
            var height = m.heightPct; var gap = m.gapDp; var width = m.widthPct
            val neededH = SPREAD_KEYS * r.sigmaY
            if (r.vertical > TARGET_MISS || neededH > r.keyH) {
                val byMisses = height + if (r.vertical > 2 * TARGET_MISS) 10 else if (r.vertical > TARGET_MISS) 5 else 0
                val bySpread = Math.ceil(height * neededH / r.keyH / 5.0).toInt() * 5
                height = minOf(140, maxOf(height, byMisses, bySpread))
            }
            if (r.horizontal > TARGET_MISS || SPREAD_KEYS * r.sigmaX > r.keyW) { if (gap > 0) gap-- else width = 100 }
            return Metrics(height, gap, width)
        }

        private fun gauss(a: Array<DoubleArray>, b: DoubleArray, n: Int): DoubleArray {
            val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
            for (c in 0 until n) {
                val p = (c until n).maxByOrNull { abs(m[it][c]) }!!
                val t = m[c]; m[c] = m[p]; m[p] = t
                if (abs(m[c][c]) < 1e-12) return DoubleArray(n)
                for (r in 0 until n) if (r != c) { val f = m[r][c] / m[c][c]; for (j in c..n) m[r][j] -= f * m[c][j] }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}

/**
 * The touch correction: the offset of x and of y (in key sizes) as a function of where on the view the touch is, with `u` and `v` the
 * distance from the centre in widths and heights of the view. Six numbers are a plane per axis, `c0 + c1*u + c2*v`; twelve add the
 * curve, `c3*4u² + c4*4uv + c5*4v²`, which follows the arc a thumb sweeps. The first half is x, the second y.
 */
object Bias {
    fun features(x: Float, y: Float, w: Int, h: Int, n: Int): DoubleArray {
        val u = x.toDouble() / w - 0.5; val v = y.toDouble() / h - 0.5
        return if (n == 3) doubleArrayOf(1.0, u, v) else doubleArrayOf(1.0, u, v, 4 * u * u, 4 * u * v, 4 * v * v)
    }

    /** The touch moved to where the finger meant to be; unchanged without a correction. */
    fun correct(x: Float, y: Float, w: Int, h: Int, refW: Float, refH: Float, c: DoubleArray?): Pair<Float, Float> {
        if (c == null || w <= 0 || h <= 0) return x to y
        val n = c.size / 2
        val f = features(x, y, w, h, n)
        var ox = 0.0; var oy = 0.0
        for (i in 0 until n) { ox += c[i] * f[i]; oy += c[n + i] * f[i] }
        // shortcut: the correction is capped at half a key so a bad fit cannot send a touch to another key, lift it if keys ever get smaller than a fingertip
        return (x - ox.coerceIn(-0.5, 0.5) * refW).toFloat() to (y - oy.coerceIn(-0.5, 0.5) * refH).toFloat()
    }

    fun encode(c: DoubleArray?) = c?.joinToString(",") ?: ""

    fun decode(s: String): DoubleArray? = s.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 6 || it.size == 12 }?.toDoubleArray()
}
