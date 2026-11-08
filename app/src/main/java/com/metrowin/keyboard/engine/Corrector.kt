package com.metrowin.keyboard.engine

import com.metrowin.keyboard.layout.LanguageLayout
import kotlin.math.ln
import kotlin.math.hypot

/** How likely each key near a tap was the key meant, 0..1 (the view computes it from where the finger landed). */
typealias Touch = Map<Char, Float>

/**
 * Where the letter keys are, in key widths, so a slip to a neighbouring key can be told from a slip across the keyboard when the tap
 * itself is not known (a pasted word, a swipe). Built from the rows of the layout the way the view lays them out.
 */
class KeyGrid(private val pos: Map<Char, Pair<Float, Float>>) {
    /** Where a letter key is, in key widths from the top-left of the keyboard; null when it is not on it. */
    fun position(c: Char): Pair<Float, Float>? = pos[c]

    /** Distance between two letter keys in key widths; null when either is not on the keyboard. */
    fun distance(a: Char, b: Char): Float? {
        val p = pos[a] ?: return null
        val q = pos[b] ?: return null
        return hypot(p.first - q.first, p.second - q.second)
    }

    companion object {
        val EMPTY = KeyGrid(emptyMap())
        const val ROW_PITCH = 1.3f     // a row is about 1.3 key widths tall

        fun of(layout: LanguageLayout): KeyGrid {
            val rows = layout.rows
            // The shift and backspace keys of the last row weigh 1.5 each, as in KeyboardBuilder.
            val sums = rows.mapIndexed { r, row -> row.size + if (r == rows.lastIndex && rows.size >= 3) 3f else 0f }
            val widest = sums.maxOrNull() ?: return EMPTY
            val map = HashMap<Char, Pair<Float, Float>>()
            rows.forEachIndexed { r, row ->
                val offset = if (r == rows.lastIndex && rows.size >= 3) 1.5f else 0f
                row.forEachIndexed { i, label ->
                    if (label.length == 1) map[label[0].lowercaseChar()] = ((offset + i + 0.5f) / sums[r] * widest) to r * ROW_PITCH
                }
            }
            return KeyGrid(map)
        }
    }
}

/**
 * The error half of the correction model: how likely it is that [word] was meant when [typed] came out. It is a Damerau-Levenshtein
 * distance whose steps have a price each, in the same units as the log of a word's frequency, so the two can be added:
 * a letter swapped for the key next to it is cheap, for a key across the keyboard dear, a missing accent almost free, a doubled or
 * dropped letter and a transposition in between. When the tap of a letter is known ([Touch]) the price of reading it as another key is
 * what the finger says (a tap between two keys makes either reading cheap), not what the keyboard map says.
 */
class Corrector(private val grid: KeyGrid) {
    fun cost(typed: String, word: String, touch: List<Touch?>? = null): Double {
        val a = typed.lowercase(); val b = word.lowercase()
        val n = a.length; val m = b.length
        val fa = CharArray(n) { fold(a[it]) }; val fb = CharArray(m) { fold(b[it]) }
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + dropCost(a, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + ADD
        for (i in 1..n) for (j in 1..m) {
            var best = d[i - 1][j - 1] + if (fa[i - 1] == fb[j - 1]) (if (a[i - 1] == b[j - 1]) 0.0 else ACCENT) else sub(a, i - 1, b[j - 1], touch)
            best = minOf(best, d[i - 1][j] + dropCost(a, i - 1))
            best = minOf(best, d[i][j - 1] + if (j > 1 && fb[j - 1] == fb[j - 2]) ADD_DOUBLE else ADD)
            if (i > 1 && j > 1 && fa[i - 1] == fb[j - 2] && fa[i - 2] == fb[j - 1]) best = minOf(best, d[i - 2][j - 2] + SWAP)
            d[i][j] = best
        }
        return d[n][m]
    }

    private fun fold(c: Char): Char = Text.fold(c.toString()).firstOrNull() ?: c

    private fun dropCost(a: String, i: Int) = if (i > 0 && a[i] == a[i - 1]) DROP_DOUBLE else DROP

    private fun sub(a: String, i: Int, c: Char, touch: List<Touch?>?): Double {
        val t = a[i]
        val tap = touch?.getOrNull(i)
        if (tap != null) {
            val pt = tap[t] ?: 0.03f
            val pc = tap[c] ?: return gridCost(t, c) + OFF_TAP
            return ln((pt / pc).toDouble()).coerceIn(0.0, 4.0) + 0.3
        }
        return gridCost(t, c)
    }

    private fun gridCost(t: Char, c: Char): Double {
        val d = grid.distance(t, c) ?: grid.distance(t, fold(c)) ?: return FAR
        return (0.8 + 0.8 * d).coerceAtMost(FAR)
    }

    private companion object {
        const val ADD = 2.2            // a letter left out
        const val ADD_DOUBLE = 1.4     // a doubled letter left out
        const val DROP = 2.2           // a letter too many
        const val DROP_DOUBLE = 1.2    // a letter typed twice
        const val SWAP = 1.0           // two neighbouring letters swapped
        const val ACCENT = 0.2         // the right letter without its accent
        const val FAR = 3.5            // a key across the keyboard, or one not on it
        const val OFF_TAP = 0.8        // the key meant was not even near the tap
    }
}
