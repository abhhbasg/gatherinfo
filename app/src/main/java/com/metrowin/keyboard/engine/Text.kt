package com.metrowin.keyboard.engine

import java.text.Normalizer
import java.util.Locale

/** Small text helpers shared by the dictionary, the suggester and the input logic. Pure, so they are tested. */
object Text {
    private val marks = Regex("\\p{M}+")

    /** [s] without accents and lower-cased: "Perché" -> "perche". Letters that are not decomposable (ß, ø) stay as they are. */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")

    /** True for the characters that make up a word. The apostrophe is handled by the caller (it depends on the language). */
    fun isWordChar(c: Char): Boolean = Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /** Applies the capitalisation of [typed] to [word]: "Ciao" + "ciao" -> "Ciao", "CIAO" + "ciao" -> "CIAO". */
    fun matchCase(typed: String, word: String, locale: Locale = Locale.ROOT): String {
        if (typed.isEmpty() || word.isEmpty()) return word
        val letters = typed.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return word.uppercase(locale)
        if (typed[0].isUpperCase()) return word.replaceFirstChar { it.titlecase(locale) }
        return word
    }

    /** The last word of [before] (letters and, when [joinApostrophe], inner apostrophes), or "" when it ends with a non-word character. */
    fun trailingWord(before: CharSequence, joinApostrophe: Boolean): String {
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            if (isWordChar(c) || (joinApostrophe && isApostrophe(c) && i > 1 && isWordChar(before[i - 2]))) i-- else break
        }
        return before.substring(i)
    }

    /** The words before the cursor, last first, skipping [skip] trailing characters of a word being typed. Max [count]. */
    fun previousWords(before: CharSequence, joinApostrophe: Boolean, count: Int = 2): List<String> {
        val out = ArrayList<String>(count)
        var end = before.length
        // Words are only linked across plain spaces: a full stop, a comma or a new line ends the context.
        while (out.size < count) {
            var i = end
            while (i > 0 && before[i - 1] == ' ') i--
            if (i == end && end != before.length) break
            val wordEnd = i
            while (i > 0) {
                val c = before[i - 1]
                if (isWordChar(c) || (joinApostrophe && isApostrophe(c) && i > 1 && isWordChar(before[i - 2]))) i-- else break
            }
            if (i == wordEnd) break
            out += before.substring(i, wordEnd).lowercase(Locale.ROOT)
            end = i
            if (end == 0) break
            if (before[end - 1] != ' ') break
        }
        return out
    }
}

/** Damerau-Levenshtein distance (with adjacent transpositions) that gives up above [limit]. */
object EditDistance {
    fun within(a: String, b: String, limit: Int): Int {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > limit) return limit + 1
        if (n == 0) return if (m <= limit) m else limit + 1
        if (m == 0) return if (n <= limit) n else limit + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m].coerceAtMost(limit + 1)
    }
}
