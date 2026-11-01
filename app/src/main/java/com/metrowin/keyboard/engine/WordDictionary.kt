package com.metrowin.keyboard.engine

import java.io.BufferedReader
import java.util.Locale

/** A suggestion: the word to show/insert and a score (higher is better). */
data class Candidate(val word: String, val score: Double, val distance: Int = 0)

/**
 * An immutable word list with frequencies, built to answer three questions quickly even with a few hundred thousand words:
 * "does it exist", "what starts with this" (ignoring accents) and "what looks like this" (typos).
 */
class WordDictionary(words: List<String>, freqs: List<Int>) {
    val size: Int = words.size
    private val word: Array<String>
    private val freq: IntArray
    private val folded: Array<String>
    private val order: IntArray            // indices sorted by folded form
    private val exact = HashMap<String, Int>(size * 2)
    private val byFirst = HashMap<Char, IntArray>()

    init {
        // Sort by folded form once; keep the arrays aligned with that order.
        val idx = (0 until size).sortedWith(compareBy({ Text.fold(words[it]) }, { words[it] }))
        word = Array(size) { words[idx[it]] }
        freq = IntArray(size) { freqs[idx[it]] }
        folded = Array(size) { Text.fold(word[it]) }
        order = IntArray(size) { it }
        for (i in 0 until size) exact[word[i]] = i
        val lists = HashMap<Char, ArrayList<Int>>()
        for (i in 0 until size) if (word[i].isNotEmpty()) lists.getOrPut(word[i][0].lowercaseChar()) { ArrayList() }.add(i)
        for ((c, l) in lists) byFirst[c] = l.toIntArray()
    }

    fun contains(w: String) = exact.containsKey(w) || exact.containsKey(w.lowercase(Locale.ROOT))

    fun frequency(w: String): Int = (exact[w] ?: exact[w.lowercase(Locale.ROOT)])?.let { freq[it] } ?: 0

    /** Words that start with [prefix] (accents ignored), best first. */
    fun complete(prefix: String, limit: Int, skip: (String) -> Boolean = { false }): List<Candidate> {
        if (prefix.isEmpty()) return emptyList()
        val p = Text.fold(prefix)
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (folded[mid] < p) lo = mid + 1 else hi = mid
        }
        val lower = prefix.lowercase(Locale.ROOT)
        val top = ArrayList<Candidate>()
        var i = lo
        while (i < size && folded[i].startsWith(p)) {
            val w = word[i]
            if (!skip(w)) {
                // A tiny bonus for matching the accents the user typed, a small penalty for long completions.
                val exactPrefix = if (w.startsWith(lower)) 0.5 else 0.0
                val score = Math.log1p(freq[i].toDouble()) + exactPrefix - 0.05 * (w.length - prefix.length)
                insertTop(top, Candidate(w, score), limit)
            }
            i++
        }
        return top
    }

    /** The [limit] most frequent words. */
    fun top(limit: Int, skip: (String) -> Boolean = { false }): List<Candidate> {
        val top = ArrayList<Candidate>()
        for (i in 0 until size) if (!skip(word[i])) insertTop(top, Candidate(word[i], Math.log1p(freq[i].toDouble())), limit)
        return top
    }

    /** Words within [maxDistance] edits of [typed] (on the accent-less forms), best first. */
    fun similar(typed: String, maxDistance: Int, limit: Int, skip: (String) -> Boolean = { false }): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val t = Text.fold(typed)
        val top = ArrayList<Candidate>()
        val first = t[0]
        // Most typos keep the first letter; for the rest a second pass on the second letter is cheap enough.
        val pool = byFirst[first] ?: IntArray(0)
        scan(pool, t, typed, maxDistance, limit, top, skip)
        if (top.size < limit && t.length > 3) {
            for (c in firstLetterNeighbours(t)) byFirst[c]?.let { scan(it, t, typed, maxDistance.coerceAtMost(1), limit, top, skip) }
        }
        return top
    }

    private fun scan(pool: IntArray, t: String, typed: String, maxDistance: Int, limit: Int, top: ArrayList<Candidate>, skip: (String) -> Boolean) {
        for (i in pool) {
            val f = folded[i]
            if (kotlin.math.abs(f.length - t.length) > maxDistance) continue
            val d = EditDistance.within(t, f, maxDistance)
            if (d > maxDistance) continue
            val w = word[i]
            if (w == typed || skip(w)) continue
            val accentOnly = d == 0
            val score = Math.log1p(freq[i].toDouble()) - 3.0 * d + (if (accentOnly) 6.0 else 0.0)
            insertTop(top, Candidate(w, score, d), limit)
        }
    }

    /** Words whose second letter is the first typed: "sdd" typed instead of "dds" is rare, so only the transposition case. */
    private fun firstLetterNeighbours(t: String): List<Char> = if (t.length > 1) listOf(t[1]) else emptyList()

    /** All words that start with [first] and end with [last] (lower case), for the swipe decoder. */
    fun withEnds(first: Char, last: Char): List<Int> {
        val pool = byFirst[first.lowercaseChar()] ?: return emptyList()
        val l = last.lowercaseChar()
        return pool.filter { word[it].last().lowercaseChar() == l }
    }

    fun wordAt(i: Int) = word[i]
    fun freqAt(i: Int) = freq[i]

    private fun insertTop(top: ArrayList<Candidate>, c: Candidate, limit: Int) {
        if (top.any { it.word == c.word }) return
        if (top.size == limit && c.score <= top.last().score) return
        var pos = top.size
        while (pos > 0 && top[pos - 1].score < c.score) pos--
        top.add(pos, c)
        if (top.size > limit) top.removeAt(top.size - 1)
    }

    companion object {
        val EMPTY = WordDictionary(emptyList(), emptyList())

        /**
         * Reads a word list in any of the usual shapes: one word per line; "word<TAB>count" or "word count" (frequency lists);
         * Hunspell ".dic" ("word/FLAGS", first line is a count). Without counts, the order of the file is taken as the
         * frequency order. Lines that are not a single word are ignored.
         */
        fun parse(reader: BufferedReader, locale: Locale, maxWords: Int = 400_000): WordDictionary {
            val seen = HashMap<String, Int>()
            val ranked = ArrayList<String>()
            var anyCount = false
            var first = true
            reader.lineSequence().forEach { raw ->
                if (seen.size >= maxWords) return@forEach
                var line = raw.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                if (first) { first = false; if (line.all { it.isDigit() }) return@forEach }
                var count = -1
                val tab = line.indexOf('\t')
                val space = line.indexOf(' ')
                val cut = if (tab >= 0) tab else space
                if (cut > 0) {
                    val tail = line.substring(cut + 1).trim()
                    val n = tail.toIntOrNull() ?: tail.toDoubleOrNull()?.toInt()
                    if (n != null) { count = n; line = line.substring(0, cut) } else return@forEach
                }
                val slash = line.indexOf('/')
                if (slash > 0) line = line.substring(0, slash)
                if (line.length > 40 || line.any { it.isWhitespace() || it.isDigit() }) return@forEach
                val w = line.lowercase(locale)
                if (count >= 0) anyCount = true
                if (!seen.containsKey(w)) { seen[w] = if (count >= 0) count else -1; ranked += w }
            }
            val n = ranked.size
            val freqs = ranked.mapIndexed { rank, w ->
                val c = seen[w] ?: -1
                if (anyCount) (if (c >= 0) c else 1).coerceAtLeast(1) else (1_000_000.0 / (rank + 10)).toInt().coerceAtLeast(1)
            }
            return WordDictionary(ranked, freqs).also { check(it.size == n) }
        }
    }
}
