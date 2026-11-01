package com.metrowin.keyboard.engine

import kotlin.math.hypot
import kotlin.math.ln

/** A point of a swipe in the coordinates of the keyboard view. */
data class SwipePoint(val x: Float, val y: Float)

/**
 * Finds the words a finger traced over the letters. For every word that starts and ends near where the swipe starts and ends,
 * it checks that the swipe went through the letters in order, then compares the shape of the swipe with the ideal path through
 * the centres of those letters. Common words and words the user types win ties.
 */
class SwipeDecoder(private val dictionary: WordDictionary, private val user: UserModel?, private val blocked: (String) -> Boolean) {

    /** [centers] maps each letter of the layout to the centre of its key; [keyWidth] is the width of a key in the same units. */
    fun decode(path: List<SwipePoint>, centers: Map<Char, SwipePoint>, keyWidth: Float, limit: Int = 4): List<String> {
        if (path.size < 2 || centers.isEmpty() || dictionary.size == 0) return emptyList()
        val pathLen = length(path)
        if (pathLen < keyWidth * 0.8f) return emptyList()
        val sampled = resample(path, SAMPLES)
        val starts = nearKeys(path.first(), centers, keyWidth * 1.1f)
        val ends = nearKeys(path.last(), centers, keyWidth * 1.1f)
        val tolerance = keyWidth * 1.15f
        val scored = ArrayList<Pair<String, Double>>()
        for (a in starts) for (b in ends) {
            for (i in dictionary.withEnds(a, b)) {
                val word = dictionary.wordAt(i)
                if (word.length < 2 || blocked(word)) continue
                val ideal = idealPath(word, centers) ?: continue
                val idealLen = length(ideal)
                if (idealLen < 1f && word.length > 1 && word.toSet().size > 1) continue
                if (idealLen > pathLen * 1.6f + keyWidth || idealLen < pathLen * 0.35f - keyWidth) continue
                if (!passesThrough(word, path, centers, tolerance)) continue
                // The ends matter more than the average: "hello" and "help" differ only in where the finger stopped.
                val ends = (dist(path.first(), ideal.first()) + dist(path.last(), ideal.last())) / keyWidth * 0.5
                val shape = shapeDistance(sampled, resample(ideal, SAMPLES)) / keyWidth + ends
                val lengthPenalty = kotlin.math.abs(idealLen - pathLen) / keyWidth * 0.08
                val prior = ln(1.0 + dictionary.freqAt(i)) * 0.12 + (user?.count(word)?.let { ln(1.0 + it) * 0.25 } ?: 0.0)
                scored += word to (shape + lengthPenalty - prior)
            }
        }
        return scored.sortedBy { it.second }.map { it.first }.distinct().take(limit)
    }

    private fun nearKeys(p: SwipePoint, centers: Map<Char, SwipePoint>, radius: Float): List<Char> {
        val ranked = centers.entries.map { it.key to dist(p, it.value) }.sortedBy { it.second }
        val close = ranked.filter { it.second <= radius }.map { it.first }
        return if (close.isEmpty()) listOf(ranked.first().first) else close
    }

    private fun idealPath(word: String, centers: Map<Char, SwipePoint>): List<SwipePoint>? {
        val pts = ArrayList<SwipePoint>(word.length)
        var last: Char? = null
        for (ch in word) {
            val c = ch.lowercaseChar()
            val p = centers[c] ?: centers[Text.fold(c.toString()).firstOrNull() ?: c] ?: return null
            // A doubled letter is the same stop on the path.
            if (c != last) pts += p
            last = c
        }
        if (pts.size == 1) pts += pts[0]
        return pts
    }

    /** The letters of [word], in order, each have a point of the swipe within [tolerance] of its key. */
    private fun passesThrough(word: String, path: List<SwipePoint>, centers: Map<Char, SwipePoint>, tolerance: Float): Boolean {
        var from = 0
        var last: Char? = null
        for (ch in word) {
            val c = ch.lowercaseChar()
            if (c == last) continue
            last = c
            val target = centers[c] ?: centers[Text.fold(c.toString()).firstOrNull() ?: c] ?: return false
            var found = -1
            for (i in from until path.size) if (dist(path[i], target) <= tolerance) { found = i; break }
            if (found < 0) return false
            from = found
        }
        return true
    }

    private fun shapeDistance(a: List<SwipePoint>, b: List<SwipePoint>): Double {
        var sum = 0.0
        for (i in a.indices) sum += dist(a[i], b[i])
        return sum / a.size
    }

    companion object {
        const val SAMPLES = 24

        fun dist(a: SwipePoint, b: SwipePoint) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

        fun length(p: List<SwipePoint>): Float {
            var l = 0f
            for (i in 1 until p.size) l += dist(p[i - 1], p[i])
            return l
        }

        /** [n] points at equal distances along the polyline. */
        fun resample(p: List<SwipePoint>, n: Int): List<SwipePoint> {
            val total = length(p)
            if (total == 0f) return List(n) { p[0] }
            val step = total / (n - 1)
            val out = ArrayList<SwipePoint>(n)
            out += p[0]
            var acc = 0f
            var i = 1
            var prev = p[0]
            while (i < p.size && out.size < n) {
                val seg = dist(prev, p[i])
                if (seg > 0f && acc + seg >= step) {
                    val t = (step - acc) / seg
                    val q = SwipePoint(prev.x + (p[i].x - prev.x) * t, prev.y + (p[i].y - prev.y) * t)
                    out += q; prev = q; acc = 0f
                } else { acc += seg; prev = p[i]; i++ }
            }
            while (out.size < n) out += p.last()
            return out
        }
    }
}
