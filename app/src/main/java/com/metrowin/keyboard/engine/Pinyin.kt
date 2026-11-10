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

    /** Every syllable of the list ("a", "zhuang"…): the readings of the single characters. */
    val syllables: Set<String> by lazy { (0 until size).filter { words[it].length == 1 }.mapTo(HashSet()) { keys[it] } }

    /** How common a character is, on any of its readings. */
    private val charCounts: Map<Char, Int> by lazy {
        val m = HashMap<Char, Int>(9000)
        for (i in 0 until size) if (words[i].length == 1) m.merge(words[i][0], freqs[i]) { a, b -> maxOf(a, b) }
        m
    }
    fun charFreq(c: Char): Int = charCounts[c] ?: 0

    /** The words of two characters or more, sorted by their text, to find the words that go on from one ("中国" → "中国人"). */
    private val byText: IntArray by lazy { (0 until size).filter { words[it].length >= 2 }.sortedBy { words[it] }.toIntArray() }

    /** The words that begin with the text [prefix] and are longer, at most [extra] characters longer. */
    fun continuing(prefix: String, extra: Int): List<Int> {
        val order = byText
        var lo = 0; var hi = order.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (words[order[mid]] < prefix) lo = mid + 1 else hi = mid }
        val out = ArrayList<Int>()
        var i = lo
        while (i < order.size && words[order[i]].startsWith(prefix)) {
            val w = words[order[i]]
            if (w.length > prefix.length && w.length <= prefix.length + extra) out += order[i]
            i++
        }
        return out
    }

    /** The entries by their reading with the initials that sound alike made the same (see [fuzzyKey]); built on first use. */
    private val fuzzy: Map<String, IntArray> by lazy {
        val m = HashMap<String, MutableList<Int>>(size)
        for (i in 0 until size) { val f = fuzzyKey(keys[i]); if (f != keys[i]) m.getOrPut(f) { ArrayList(2) } += i }
        // A key that changes nothing is also a fuzzy key of the ones that change ("zi" for "zhi").
        for (i in 0 until size) m[keys[i]]?.let { if (keys[i] == fuzzyKey(keys[i])) it += i }
        m.mapValues { it.value.toIntArray() }
    }

    /** The entries whose reading sounds like [key] with the alike initials made the same. */
    fun fuzzy(key: String): IntArray = fuzzy[fuzzyKey(key)] ?: NONE

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

        /**
         * The initials people from many places say alike, made the same: zh/z, ch/c, sh/s and n/l. Letter by letter this is safe,
         * because no syllable ends in z, c or s (so an "h" after them belongs to the same initial) and none ends in l.
         */
        fun fuzzyKey(key: String): String {
            val sb = StringBuilder(key.length)
            var i = 0
            while (i < key.length) {
                val c = key[i]
                when {
                    (c == 'z' || c == 'c' || c == 's') && i + 1 < key.length && key[i + 1] == 'h' -> { sb.append(c); i += 2 }
                    c == 'l' -> { sb.append('n'); i++ }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        /** Lines of "word<TAB>pin'yin<TAB>count". */
        fun parse(reader: BufferedReader): PinyinDictionary {
            class Row(val key: String, val word: String, val freq: Int, val mark: Int)
            val rows = ArrayList<Row>(170000)
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
 * Which character tends to follow which (see tools/build_pinyin.py): for a pair "ab", ten times ln(P(b after a) / P(b)). It is what
 * makes "nihaoma" 你好吗 and not 你号码: 你号 is a pair nobody writes, 好吗 a common one.
 */
class PinyinPairs private constructor(private val pairs: LongArray, private val scores: ByteArray) {
    val size get() = pairs.size

    private fun code(a: Char, b: Char) = (a.code.toLong() shl 16) or b.code.toLong()

    /** The score of [b] after [a], or null when the pair is not known. */
    fun score(a: Char, b: Char): Int? {
        val i = pairs.binarySearch(code(a, b))
        return if (i >= 0) scores[i].toInt() else null
    }

    /** The characters known to follow [a], with their scores. */
    fun followers(a: Char): List<Pair<Char, Int>> {
        val from = (-(pairs.binarySearch(code(a, '\u0000')) + 1)).coerceAtLeast(0)
        val out = ArrayList<Pair<Char, Int>>()
        var i = from
        while (i < pairs.size && (pairs[i] ushr 16) == a.code.toLong()) { out += (pairs[i] and 0xFFFF).toInt().toChar() to scores[i].toInt(); i++ }
        return out
    }

    companion object {
        val EMPTY = PinyinPairs(LongArray(0), ByteArray(0))

        /** Lines of "ab<TAB>score". */
        fun parse(reader: BufferedReader): PinyinPairs {
            val list = ArrayList<Pair<Long, Byte>>(130000)
            reader.forEachLine { line ->
                if (line.length < 4 || line[2] != '\t') return@forEachLine
                val s = line.substring(3).trim().toIntOrNull() ?: return@forEachLine
                list += ((line[0].code.toLong() shl 16) or line[1].code.toLong()) to s.coerceIn(-128, 127).toByte()
            }
            list.sortBy { it.first }
            return PinyinPairs(LongArray(list.size) { list[it].first }, ByteArray(list.size) { list[it].second })
        }
    }
}

/**
 * What the user chose, on the phone only: how often each word was picked (it then comes first), the phrases they made out of
 * several picks, which become words of their own, and which word they wrote after which, for the words offered after a choice.
 * Written as the same lines as the word list; a word that followed another is a line "→<TAB>before<TAB>after<TAB>count".
 */
class PinyinUser {
    class Entry(val word: String, val spelled: String, var count: Int) { val mark = PinyinDictionary.markOf(spelled) }

    private val byKey = HashMap<String, MutableList<Entry>>()
    private val follows = HashMap<String, HashMap<String, Int>>()
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

    /** [after] was written right after [before]. */
    fun learnFollow(before: String, after: String) {
        if (before.isEmpty() || after.isEmpty() || before.length > 8 || after.length > 8) return
        synchronized(this) {
            val m = follows.getOrPut(before) { HashMap(2) }
            m[after] = minOf((m[after] ?: 0) + 1, MAX_COUNT)
            dirty = true
        }
    }

    /** What the user wrote after [before], most often first. */
    fun followers(before: String): List<String> = synchronized(this) {
        follows[before]?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()
    }

    fun clear() = synchronized(this) { byKey.clear(); follows.clear(); dirty = true }

    fun read(reader: BufferedReader) = synchronized(this) {
        reader.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 4 && p[0] == FOLLOW) {
                val n = p[3].toIntOrNull() ?: return@forEachLine
                if (p[1].isNotEmpty() && p[2].isNotEmpty()) follows.getOrPut(p[1]) { HashMap(2) }[p[2]] = n.coerceIn(1, MAX_COUNT)
                return@forEachLine
            }
            val n = p.getOrNull(2)?.toIntOrNull() ?: return@forEachLine
            if (p[0].isEmpty() || PinyinDictionary.markOf(p[1]) < 0) return@forEachLine
            byKey.getOrPut(p[1].replace("'", "")) { ArrayList(1) } += Entry(p[0], p[1], n.coerceIn(1, MAX_COUNT))
        }
    }

    /** The most used [KEEP] entries and follows: what was picked once long ago makes room. */
    fun write(w: Writer) = synchronized(this) {
        byKey.values.flatten().sortedByDescending { it.count }.take(KEEP).forEach { w.append(it.word).append('\t').append(it.spelled).append('\t').append(it.count.toString()).append('\n') }
        follows.flatMap { (a, m) -> m.map { Triple(a, it.key, it.value) } }.sortedByDescending { it.third }.take(KEEP).forEach {
            w.append(FOLLOW).append('\t').append(it.first).append('\t').append(it.second).append('\t').append(it.third.toString()).append('\n')
        }
        dirty = false
    }

    private companion object { const val MAX_COUNT = 50; const val KEEP = 4000; const val FOLLOW = "→" }
}

/**
 * Microsoft Shuangpin (微软双拼): every syllable is two keys, the initial and the final. zh, ch and sh are V, I and U; a syllable
 * with no initial starts with O ("ai" is OL); the finals are on the keys of the Microsoft table, "ing" on the semicolon.
 */
object Shuangpin {
    /** The keys as Pinyin: [pinyin] has the syllables split by apostrophes; a last key alone is the beginning of a syllable. */
    class Decoded(val pinyin: String, private val pinyinEnds: IntArray, private val keyEnds: IntArray) {
        /** How many keys stand for the first [consumed] characters of [pinyin]. */
        fun keysFor(consumed: Int): Int {
            var k = 0
            for (u in pinyinEnds.indices) if (pinyinEnds[u] <= consumed) k = keyEnds[u]
            return k
        }
    }

    private val INITIAL_KEYS = mapOf("zh" to 'v', "ch" to 'i', "sh" to 'u')
    private const val CONSONANTS = "bpmfdtnlgkhjqxrzcsyw"

    private fun finalKey(f: String): Char? = when (f) {
        "a", "o", "e", "i", "u" -> f[0]
        "iu" -> 'q'; "ia", "ua" -> 'w'; "er", "uan", "van" -> 'r'; "ue", "ve" -> 't'; "v", "uai" -> 'y'; "uo" -> 'o'; "un", "vn" -> 'p'
        "ong", "iong" -> 's'; "iang", "uang" -> 'd'; "en" -> 'f'; "eng" -> 'g'; "ang" -> 'h'; "an" -> 'j'; "ao" -> 'k'; "ai" -> 'l'
        "ei" -> 'z'; "ie" -> 'x'; "iao" -> 'c'; "ui" -> 'v'; "ou" -> 'b'; "ian" -> 'm'; "in" -> 'n'; "ing" -> ';'
        else -> null
    }

    /** The two keys of [syllable], or null when the scheme has none for it (interjections such as "hm"). */
    fun encode(syllable: String): String? {
        val initial = INITIAL_KEYS.keys.firstOrNull { syllable.startsWith(it) } ?: syllable.take(1).takeIf { it.isNotEmpty() && it[0] in CONSONANTS }
        return if (initial == null) {
            finalKey(syllable)?.let { "o$it" }
        } else {
            val k = INITIAL_KEYS[initial] ?: initial[0]
            val rest = syllable.substring(initial.length)
            if (rest.isEmpty()) null else finalKey(rest)?.let { "$k$it" }
        }
    }

    private var table: Map<String, String> = emptyMap()
    private var tableFor: Set<String>? = null

    /** Two keys → syllable, for the syllables of [syllables]. "ve" can also be typed with V (lüe: LV), as the scheme allows. */
    @Synchronized
    fun table(syllables: Set<String>): Map<String, String> {
        if (tableFor === syllables) return table
        val m = HashMap<String, String>()
        // The one clash of the scheme is LO: "luo" (落, 罗) and the rare "lo" (咯). The longer syllable is put first and keeps it.
        for (s in syllables.sortedByDescending { it.length }) {
            val code = encode(s) ?: continue
            m.putIfAbsent(code, s)
            if (s.endsWith("ve")) m.putIfAbsent(code.dropLast(1) + "v", s)
        }
        table = m; tableFor = syllables
        return m
    }

    /** The beginning of a syllable for a key typed alone: its initial. */
    private fun initialOf(k: Char): String = when (k) {
        'v' -> "zh"; 'i' -> "ch"; 'u' -> "sh"; 'a', 'e' -> k.toString()
        else -> if (k in CONSONANTS) k.toString() else ""
    }

    fun decode(keys: String, syllables: Set<String>): Decoded {
        val t = table(syllables)
        val sb = StringBuilder()
        val pinyinEnds = ArrayList<Int>(); val keyEnds = ArrayList<Int>()
        var i = 0
        while (i < keys.length) {
            val pair = if (i + 1 < keys.length) keys.substring(i, i + 2) else null
            val unit = pair?.let { t[it] }
            val text: String
            val used: Int
            when {
                unit != null -> { text = unit; used = 2 }
                pair == null -> { text = initialOf(keys[i]); used = 1 }
                // Two keys that are no syllable stay two letters, so the keys after them keep their pairs.
                else -> { text = pair.filter { it in 'a'..'z' }; used = 2 }
            }
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(text)
            }
            i += used
            pinyinEnds += sb.length; keyEnds += i
        }
        return Decoded(sb.toString(), pinyinEnds.toIntArray(), keyEnds.toIntArray())
    }
}

/**
 * Turns the Pinyin typed so far into what the bar offers: the likeliest sentence (a row of words, each weighed by how common it
 * is and how well its first character follows the last one before it), the words read exactly so, the words that begin so
 * ("zhongg" offers 中国), the words whose syllables begin with these letters ("nh" offers 你好) and, last, the words for the
 * first syllables only, the rest staying to be chosen next. After a choice it also offers what usually comes next.
 *
 * The input is lower-case letters with "v" for "ü"; an apostrophe splits syllables where the user wants them split ("xi'an").
 */
class PinyinEngine(
    private val dict: PinyinDictionary,
    val user: PinyinUser = PinyinUser(),
    private val pairs: PinyinPairs = PinyinPairs.EMPTY,
) {
    private class Hit(val word: String, val key: String, val mark: Int, val score: Double) {
        val spelled get() = PinyinDictionary.spell(key, mark)
    }

    private class Path(val text: String, val spelled: String, val last: Char, val words: Int, val score: Double)

    val ready get() = dict.size > 0

    /** zh/z, ch/c, sh/s and n/l are taken as the same (see [PinyinDictionary.fuzzyKey]). */
    @Volatile var fuzzy = false

    /** The syllables of the word list, for the Shuangpin keys. */
    val syllables: Set<String> get() = dict.syllables

    private fun score(freq: Int, picked: Int) = ln(freq + 1.0) + PICK_WEIGHT * minOf(picked, PICK_CAP)

    /** Whether a reading split as [mark] agrees with the separators the user typed inside it ([cuts] are positions in the letters). */
    private fun agrees(mark: Int, from: Int, len: Int, cuts: BooleanArray): Boolean {
        for (p in 1 until len) if (cuts[from + p] && (p > 31 || mark and (1 shl p) == 0)) return false
        return true
    }

    /** The words read as the letters [from]..[to], best first. */
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
        if (fuzzy) for (i in dict.fuzzy(key)) {
            if (dict.key(i) == key || out.any { it.word == dict.word(i) }) continue
            out += Hit(dict.word(i), dict.key(i), dict.mark(i), score(dict.freq(i), user.count(dict.key(i), dict.word(i), dict.mark(i))) - FUZZY_PENALTY)
        }
        if (mine.isNotEmpty() || fuzzy) out.sortByDescending { it.score }
        return out
    }

    private val prefixMemo = object : LinkedHashMap<String, List<Int>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > 64
    }

    /** The commonest words whose reading begins with [prefix] and goes on, at most one more syllable or so. */
    private fun topStartingWith(prefix: String, n: Int): List<Int> = synchronized(prefixMemo) {
        prefixMemo.getOrPut("$prefix/$n") {
            dict.startingWith(prefix).filter { dict.key(it).length > prefix.length && dict.key(it).length <= prefix.length + 6 }
                .sortedByDescending { dict.freq(it) }.take(n)
        }
    }

    /** How well [b] follows [a]: the pair's score, a little less than nothing for a pair nobody writes. */
    private fun pairBonus(a: Char, b: Char): Double {
        if (a == '\u0000' || pairs.size == 0) return 0.0
        val s = pairs.score(a, b) ?: return UNKNOWN_PAIR
        return PAIR_WEIGHT * s / 10.0
    }

    /**
     * The likeliest readings of the letters as a row of words, best first: a beam search over where the words end, each word
     * weighing as the logarithm of its probability plus how well it follows the word before. The last letters may be the beginning
     * of a word ("wojintianhengaox" reads to the end with 高兴). When nothing reads to the end, the longest beginning that does.
     */
    private fun sentences(t: String, cuts: BooleanArray, count: Int): List<Pair<Path, Int>> {
        val n = t.length
        val beams = arrayOfNulls<MutableList<Path>>(n + 1)
        beams[0] = mutableListOf(Path("", "", '\u0000', 0, 0.0))
        val maxKey = maxOf(dict.maxKey, 1)
        val ends = ArrayList<Path>()
        for (i in 0 until n) {
            val here = beams[i] ?: continue
            here.sortByDescending { it.score }
            val kept = here.take(BEAM)
            for (j in i + 1..minOf(n, i + maxKey)) {
                val hits = exact(t, i, j, cuts)
                if (hits.isEmpty()) continue
                val target = beams[j] ?: ArrayList<Path>().also { beams[j] = it }
                for (p in kept) for (h in hits.take(PER_SPAN)) {
                    target += Path(p.text + h.word, if (p.spelled.isEmpty()) h.spelled else p.spelled + "'" + h.spelled, h.word.last(),
                        p.words + 1, p.score + h.score - dict.lnTotal + pairBonus(p.last, h.word[0]))
                }
                if (target.size > BEAM * 4) { target.sortByDescending { it.score }; while (target.size > BEAM * 2) target.removeAt(target.size - 1) }
            }
            // The last letters as the beginning of a word.
            if (n - i in 1..6 && !cuts.sliceArray(i + 1 until n).any { it }) {
                for (k in topStartingWith(t.substring(i), 3)) {
                    if (!agrees(dict.mark(k), i, n - i, cuts)) continue
                    for (p in kept) ends += Path(p.text + dict.word(k), if (p.spelled.isEmpty()) dict.spelled(k) else p.spelled + "'" + dict.spelled(k),
                        dict.word(k).last(), p.words + 1, p.score + score(dict.freq(k), 0) - dict.lnTotal + pairBonus(p.last, dict.word(k)[0]) - TAIL_PENALTY)
                }
            }
        }
        val complete = (beams[n].orEmpty() + ends).sortedByDescending { it.score }.distinctBy { it.text }
        if (complete.isNotEmpty()) return complete.take(count).map { it to n }
        val end = (n - 1 downTo 1).firstOrNull { !beams[it].isNullOrEmpty() } ?: return emptyList()
        return listOf(beams[end]!!.maxBy { it.score } to end)
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
        val read = sentences(t, cuts, 2)
        val best = read.firstOrNull()
        // The sentence goes first when it reads more of the letters than a single word, or reads them likelier.
        val sentenceFirst = best != null && best.first.words >= 2 &&
            (whole.isEmpty() || (best.second == n && best.first.score > whole[0].score - dict.lnTotal + SENTENCE_MARGIN))
        if (sentenceFirst) add(best!!.first.text, best.first.spelled, best.second)
        for (h in whole) add(h.word, h.spelled, n)
        // The second reading, when it is close.
        read.getOrNull(1)?.let { (p, end) -> if (end == n && p.words >= 2 && best != null && best.first.score - p.score < 3.0) add(p.text, p.spelled, end) }
        if (!sentenceFirst && best != null && best.first.words >= 2 && best.second == n) add(best.first.text, best.first.spelled, n)

        // Words that begin with what was typed.
        val longer = ArrayList<Hit>()
        for (i in topStartingWith(t, if (whole.isEmpty()) 40 else 12)) {
            if (!agrees(dict.mark(i), 0, n, cuts)) continue
            longer += Hit(dict.word(i), dict.key(i), dict.mark(i), score(dict.freq(i), user.count(dict.key(i), dict.word(i), dict.mark(i))))
        }
        for (e in user.startingWith(t)) if (agrees(e.mark, 0, n, cuts)) longer += Hit(e.word, e.spelled.replace("'", ""), e.mark, score(USER_FREQ, e.count))
        longer.sortByDescending { it.score }
        for (h in longer.take(if (whole.isEmpty()) 30 else 8)) add(h.word, h.spelled, n)

        // Words whose syllables begin with these letters.
        if (n in 2..6 && cuts.none { it }) {
            dict.byInitials(t).sortedByDescending { dict.freq(it) }.take(12).forEach { add(dict.word(it), dict.spelled(it), n) }
        }

        // Words for the first letters only, the longest reading first.
        for (j in minOf(n - 1, maxOf(dict.maxKey, 1)) downTo 1) {
            if (out.size >= limit) break
            for (h in exact(t, 0, j, cuts)) add(h.word, h.spelled, j)
        }
        return out
    }

    /**
     * What usually comes after [before], the text just written: what the user wrote after it before, the words that go on from it
     * (中国 → 人, 人民) and the characters that commonly follow its last one.
     */
    fun associations(before: String, limit: Int = 24): List<String> {
        if (!ready || before.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        user.followers(before).take(8).forEach { out += it }
        val tail = before.takeLast(4)
        for (len in tail.length downTo 1) {
            val stem = tail.takeLast(len)
            dict.continuing(stem, 3).sortedByDescending { dict.freq(it) }.take(if (len > 1) 8 else 6).forEach { out += dict.word(it).substring(stem.length) }
            if (out.size >= limit / 2) break
        }
        val last = before.last()
        pairs.followers(last).map { (b, s) -> b to s / 10.0 + ln(dict.charFreq(b) + 1.0) }.sortedByDescending { it.second }.take(10).forEach { out += it.first.toString() }
        return out.filter { it.isNotEmpty() }.take(limit)
    }

    private companion object {
        /** Each time a word was picked counts as this much on the logarithm of its count: two or three picks put it first. */
        const val PICK_WEIGHT = 4.0
        const val PICK_CAP = 6
        /** How common a phrase the user made is taken to be before they pick it again. */
        const val USER_FREQ = 400
        const val FUZZY_PENALTY = 1.5
        const val PAIR_WEIGHT = 0.7
        const val UNKNOWN_PAIR = -1.2
        const val TAIL_PENALTY = 1.0
        const val SENTENCE_MARGIN = 0.0
        const val BEAM = 6
        const val PER_SPAN = 5
    }
}
