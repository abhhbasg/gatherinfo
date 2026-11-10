package com.metrowin.keyboard.engine

import java.io.BufferedReader
import java.io.Writer
import kotlin.math.ln

/**
 * What the bar offers for the Pinyin typed so far. [pinyin] is the reading with its syllables split ("ni'hao"); [consumed] is how
 * many characters of what was typed the text stands for, so the rest stays in the field to be chosen next.
 */
class PinyinCandidate(val text: String, val pinyin: String, val consumed: Int) {
    override fun toString() = "$text($pinyin,$consumed)"
}

/**
 * The Simplified Chinese word list: every word and character with its reading and how common it is (see tools/build_pinyin.py).
 * Kept as arrays sorted by the reading without separators, so "xian" finds both 先 and 西安 with one binary search and a prefix
 * ("zhon") finds a range.
 */
class PinyinDictionary private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val freqs: IntArray,
    /** Bit p is set when a syllable ends after the first p letters of the key: "xi'an" is 0b100. */
    private val marks: IntArray,
    private val initials: Map<String, IntArray>,
) {
    val size get() = keys.size
    val maxKey: Int = keys.maxOfOrNull { it.length } ?: 0
    /** ln of the sum of all counts: what turns a count into the probability of a word. */
    val lnTotal: Double = ln(freqs.sumOf { it.toLong() }.coerceAtLeast(1L).toDouble())

    fun key(i: Int) = keys[i]
    fun word(i: Int) = words[i]
    fun freq(i: Int) = freqs[i]
    fun mark(i: Int) = marks[i]
    fun spelled(i: Int) = spell(keys[i], marks[i])

    /** Index of the first entry whose key is not before [key]. */
    private fun lowerBound(key: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] < key) lo = mid + 1 else hi = mid }
        return lo
    }

    /** The entries read exactly [key], most common first. */
    fun exact(key: String): IntRange {
        val from = lowerBound(key)
        var to = from
        while (to < keys.size && keys[to] == key) to++
        return from until to
    }

    /** The entries whose reading starts with [prefix] (the exact ones included). */
    fun startingWith(prefix: String): IntRange {
        val from = lowerBound(prefix)
        var to = from
        while (to < keys.size && keys[to].startsWith(prefix)) to++
        return from until to
    }

    /** The words whose syllables start with these letters: "nh" finds 你好. */
    fun byInitials(letters: String): IntArray = initials[letters] ?: NONE

    companion object {
        private val NONE = IntArray(0)
        val EMPTY = PinyinDictionary(emptyArray(), emptyArray(), IntArray(0), IntArray(0), emptyMap())

        fun spell(key: String, mark: Int): String {
            if (mark == 0) return key
            val sb = StringBuilder(key.length + 4)
            for (p in key.indices) { if (p > 0 && mark and (1 shl p) != 0) sb.append('\''); sb.append(key[p]) }
            return sb.toString()
        }

        /** The mark of a reading written with separators, or -1 if it is too long to hold. */
        fun markOf(spelled: String): Int {
            var mark = 0; var p = 0
            for (c in spelled) if (c == '\'') { if (p in 1..30) mark = mark or (1 shl p) } else p++
            return if (p > 31) -1 else mark
        }

        /** Lines of "word<TAB>pin'yin<TAB>count". */
        fun parse(reader: BufferedReader): PinyinDictionary {
            class Row(val key: String, val word: String, val freq: Int, val mark: Int)
            val rows = ArrayList<Row>(70000)
            reader.forEachLine { line ->
                val a = line.indexOf('\t'); val b = if (a > 0) line.indexOf('\t', a + 1) else -1
                if (b < 0) return@forEachLine
                val spelled = line.substring(a + 1, b)
                val mark = markOf(spelled)
                val freq = line.substring(b + 1).trim().toIntOrNull() ?: return@forEachLine
                if (mark < 0 || spelled.isEmpty() || spelled.any { it != '\'' && it !in 'a'..'z' }) return@forEachLine
                rows += Row(spelled.replace("'", ""), line.substring(0, a), freq, mark)
            }
            rows.sortWith(compareBy<Row> { it.key }.thenByDescending { it.freq })
            val initials = HashMap<String, MutableList<Int>>()
            rows.forEachIndexed { i, r ->
                if (r.mark == 0) return@forEachIndexed
                val sb = StringBuilder(6)
                for (p in r.key.indices) if (p == 0 || r.mark and (1 shl p) != 0) sb.append(r.key[p])
                initials.getOrPut(sb.toString()) { ArrayList(2) } += i
            }
            return PinyinDictionary(
                Array(rows.size) { rows[it].key }, Array(rows.size) { rows[it].word },
                IntArray(rows.size) { rows[it].freq }, IntArray(rows.size) { rows[it].mark },
                initials.mapValues { it.value.toIntArray() },
            )
        }
    }
}

/**
 * What the user chose, on the phone only: how often each word was picked (it then comes first) and the phrases they made out of
 * several picks, which become words of their own. Written as the same lines as the word list.
 */
class PinyinUser {
    class Entry(val word: String, val spelled: String, var count: Int) { val mark = PinyinDictionary.markOf(spelled) }

    private val byKey = HashMap<String, MutableList<Entry>>()
    @Volatile var dirty = false
        private set

    val size get() = synchronized(this) { byKey.values.sumOf { it.size } }

    fun forKey(key: String): List<Entry> = synchronized(this) { byKey[key]?.toList() ?: emptyList() }

    fun count(key: String, word: String, mark: Int): Int = synchronized(this) { byKey[key]?.firstOrNull { it.word == word && it.mark == mark }?.count ?: 0 }

    fun startingWith(prefix: String): List<Entry> = synchronized(this) {
        if (byKey.isEmpty()) emptyList() else byKey.entries.filter { it.key.length > prefix.length && it.key.startsWith(prefix) }.flatMap { it.value }
    }

    fun learn(spelled: String, word: String) {
        if (word.isEmpty() || PinyinDictionary.markOf(spelled) < 0) return
        synchronized(this) {
            val list = byKey.getOrPut(spelled.replace("'", "")) { ArrayList(1) }
            val e = list.firstOrNull { it.word == word && it.spelled == spelled }
            if (e != null) e.count = minOf(e.count + 1, MAX_COUNT) else list += Entry(word, spelled, 1)
            dirty = true
        }
    }

    fun clear() = synchronized(this) { byKey.clear(); dirty = true }

    fun read(reader: BufferedReader) = synchronized(this) {
        reader.forEachLine { line ->
            val p = line.split('\t')
            val n = p.getOrNull(2)?.toIntOrNull() ?: return@forEachLine
            if (p[0].isEmpty() || PinyinDictionary.markOf(p[1]) < 0) return@forEachLine
            byKey.getOrPut(p[1].replace("'", "")) { ArrayList(1) } += Entry(p[0], p[1], n.coerceIn(1, MAX_COUNT))
        }
    }

    /** The most used [KEEP] entries: what was picked once long ago makes room. */
    fun write(w: Writer) = synchronized(this) {
        byKey.values.flatten().sortedByDescending { it.count }.take(KEEP).forEach { w.append(it.word).append('\t').append(it.spelled).append('\t').append(it.count.toString()).append('\n') }
        dirty = false
    }

    private companion object { const val MAX_COUNT = 50; const val KEEP = 4000 }
}

/**
 * Turns the Pinyin typed so far into what the bar offers: the words read exactly so, a whole phrase made of the likeliest words
 * when no single word fits, the words that begin so ("zhongg" offers 中国), the words whose syllables begin with these letters
 * ("nh" offers 你好) and, last, the words for the first syllables only, the rest staying to be chosen next.
 *
 * The input is lower-case letters with "v" for "ü"; an apostrophe splits syllables where the user wants them split ("xi'an").
 */
class PinyinEngine(private val dict: PinyinDictionary, val user: PinyinUser = PinyinUser()) {
    private class Hit(val word: String, val key: String, val mark: Int, val score: Double) {
        val spelled get() = PinyinDictionary.spell(key, mark)
    }

    val ready get() = dict.size > 0

    private fun score(freq: Int, picked: Int) = ln(freq + 1.0) + PICK_WEIGHT * minOf(picked, PICK_CAP)

    /** Whether a reading split as [mark] agrees with the separators the user typed inside it ([cuts] are positions in the letters). */
    private fun agrees(mark: Int, from: Int, len: Int, cuts: BooleanArray): Boolean {
        for (p in 1 until len) if (cuts[from + p] && (p > 31 || mark and (1 shl p) == 0)) return false
        return true
    }

    /** The words read exactly as the letters [from]..[to], best first. */
    private fun exact(t: String, from: Int, to: Int, cuts: BooleanArray): List<Hit> {
        val key = t.substring(from, to)
        val out = ArrayList<Hit>()
        val mine = user.forKey(key)
        for (i in dict.exact(key)) {
            if (!agrees(dict.mark(i), from, to - from, cuts)) continue
            val picked = if (mine.isEmpty()) 0 else mine.firstOrNull { it.word == dict.word(i) && it.mark == dict.mark(i) }?.count ?: 0
            out += Hit(dict.word(i), key, dict.mark(i), score(dict.freq(i), picked))
        }
        for (e in mine) {
            if (!agrees(e.mark, from, to - from, cuts) || out.any { it.word == e.word && it.mark == e.mark }) continue
            out += Hit(e.word, key, e.mark, score(USER_FREQ, e.count))
        }
        if (mine.isNotEmpty()) out.sortByDescending { it.score }
        return out
    }

    fun candidates(input: String, limit: Int = 100): List<PinyinCandidate> {
        if (!ready) return emptyList()
        // The letters, where the user split them, and how much of the input each count of letters stands for.
        val sb = StringBuilder(input.length)
        val cuts = BooleanArray(input.length + 2)
        val rawEnd = IntArray(input.length + 1)
        for ((i, c) in input.withIndex()) {
            if (c in 'a'..'z') { sb.append(c); rawEnd[sb.length] = i + 1 }
            else if (c == '\'') { if (sb.isNotEmpty()) { cuts[sb.length] = true; rawEnd[sb.length] = i + 1 } }
            else return emptyList()
        }
        val t = sb.toString()
        val n = t.length
        if (n == 0) return emptyList()

        val out = ArrayList<PinyinCandidate>()
        val seen = HashSet<String>()
        fun add(text: String, spelled: String, letters: Int) {
            if (out.size < limit && seen.add(text)) out += PinyinCandidate(text, spelled, rawEnd[letters])
        }

        val whole = if (n <= dict.maxKey || user.size > 0) exact(t, 0, n, cuts) else emptyList()
        val phrase = phrase(t, cuts)
        // A phrase that stops before the end (the last letters are not a syllable yet) still comes first: it is the longest reading.
        if (phrase != null && whole.isEmpty()) add(phrase.first, phrase.second, phrase.third)
        for (h in whole) add(h.word, h.spelled, n)

        // Words that begin with what was typed.
        val longer = ArrayList<Hit>()
        for (i in dict.startingWith(t)) {
            if (dict.key(i).length == n || !agrees(dict.mark(i), 0, n, cuts)) continue
            longer += Hit(dict.word(i), dict.key(i), dict.mark(i), score(dict.freq(i), user.count(dict.key(i), dict.word(i), dict.mark(i))))
        }
        for (e in user.startingWith(t)) if (agrees(e.mark, 0, n, cuts)) longer += Hit(e.word, e.spelled.replace("'", ""), e.mark, score(USER_FREQ, e.count))
        longer.sortByDescending { it.score }
        for (h in longer.take(if (whole.isEmpty()) 30 else 8)) add(h.word, h.spelled, n)

        // Words whose syllables begin with these letters.
        if (n in 2..6) {
            val idx = dict.byInitials(t)
            idx.sortedByDescending { dict.freq(it) }.take(12).forEach { add(dict.word(it), dict.spelled(it), n) }
        }

        // Words for the first letters only, the longest reading first.
        for (j in minOf(n - 1, maxOf(dict.maxKey, 1)) downTo 1) {
            if (out.size >= limit) break
            for (h in exact(t, 0, j, cuts)) add(h.word, h.spelled, j)
        }
        return out
    }

    /**
     * The likeliest way to read the letters as a row of words (each word weighs as the logarithm of how common it is, so fewer and
     * commoner words win). Returns the text, its reading and how many letters it covers: all of them when they can be read to the end,
     * otherwise the longest beginning that can. Null when that is a single word (the list of words already has it) or nothing.
     */
    private fun phrase(t: String, cuts: BooleanArray): Triple<String, String, Int>? {
        val n = t.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val words = IntArray(n + 1)
        val hit = arrayOfNulls<Hit>(n + 1)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            for (j in i + 1..minOf(n, i + maxOf(dict.maxKey, 1))) {
                val h = exact(t, i, j, cuts).firstOrNull() ?: continue
                val s = best[i] + h.score - dict.lnTotal
                if (s > best[j]) { best[j] = s; from[j] = i; hit[j] = h; words[j] = words[i] + 1 }
            }
        }
        var end = n
        while (end > 0 && best[end] == Double.NEGATIVE_INFINITY) end--
        if (end == 0 || words[end] < 2) return null
        val parts = ArrayList<Hit>()
        var j = end
        while (j > 0) { parts += hit[j]!!; j = from[j] }
        parts.reverse()
        return Triple(parts.joinToString("") { it.word }, parts.joinToString("'") { it.spelled }, end)
    }

    private companion object {
        /** Each time a word was picked counts as this much on the logarithm of its count: two or three picks put it first. */
        const val PICK_WEIGHT = 4.0
        const val PICK_CAP = 6
        /** How common a phrase the user made is taken to be before they pick it again. */
        const val USER_FREQ = 400
    }
}
