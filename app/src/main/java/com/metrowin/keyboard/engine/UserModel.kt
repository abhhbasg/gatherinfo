package com.metrowin.keyboard.engine

import java.io.BufferedReader
import java.io.Writer

/**
 * What the keyboard has learned from this user, kept only on the phone: how often each word was typed, and which word
 * followed which. It is the whole "machine learning" of the keyboard: counts, with the oldest and rarest dropped when the
 * model grows past its limits. One model per language.
 */
class UserModel(private val maxWords: Int = 20_000, private val maxPairs: Int = 60_000) {
    private val unigrams = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var pairCount = 0

    @Volatile var dirty = false
        private set

    @Synchronized fun count(word: String): Int = unigrams[word] ?: 0

    @Synchronized fun learn(previous: String?, word: String) = learn(null, previous, word)

    /** Also remembers the three words in a row: they are kept like a pair whose first part is "[before] [previous]". */
    @Synchronized fun learn(before: String?, previous: String?, word: String) {
        if (word.isEmpty()) return
        unigrams[word] = (unigrams[word] ?: 0) + 1
        if (!previous.isNullOrEmpty()) {
            addPair(previous, word)
            if (!before.isNullOrEmpty()) addPair("$before $previous", word)
        }
        dirty = true
        if (unigrams.size > maxWords * 5 / 4 || pairCount > maxPairs * 5 / 4) prune()
    }

    private fun addPair(previous: String, word: String) {
        val next = bigrams.getOrPut(previous) { HashMap() }
        val c = next[word]
        if (c == null) pairCount++
        next[word] = (c ?: 0) + 1
    }

    /** Words that followed [previous] (most often first). */
    @Synchronized fun followers(previous: String, limit: Int): List<Pair<String, Int>> =
        bigrams[previous]?.entries?.sortedByDescending { it.value }?.take(limit)?.map { it.key to it.value } ?: emptyList()

    @Synchronized fun followerCount(previous: String, word: String) = bigrams[previous]?.get(word) ?: 0

    /** Learned words that start with [prefix], most used first. */
    @Synchronized fun complete(prefix: String, limit: Int): List<Pair<String, Int>> =
        unigrams.entries.filter { it.key.startsWith(prefix) && it.key != prefix }.sortedByDescending { it.value }.take(limit).map { it.key to it.value }

    /** Forgets one word (the user rejected it). */
    @Synchronized fun forget(word: String) {
        if (unigrams.remove(word) != null) dirty = true
        bigrams.remove(word)?.let { pairCount -= it.size }
        for (m in bigrams.values) if (m.remove(word) != null) pairCount--
    }

    @Synchronized fun clear() {
        unigrams.clear(); bigrams.clear(); pairCount = 0; dirty = true
    }

    @Synchronized fun isEmpty() = unigrams.isEmpty()

    /** Halves every count and drops what falls to zero: recent habits win, one-off words disappear. */
    private fun prune() {
        val u = unigrams.entries.iterator()
        while (u.hasNext()) { val e = u.next(); e.setValue(e.value / 2); if (e.value == 0) u.remove() }
        pairCount = 0
        val b = bigrams.entries.iterator()
        while (b.hasNext()) {
            val m = b.next()
            val it = m.value.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value / 2); if (e.value == 0) it.remove() }
            if (m.value.isEmpty()) b.remove() else pairCount += m.value.size
        }
        while (unigrams.size > maxWords) {
            unigrams.entries.minByOrNull { it.value }?.let { unigrams.remove(it.key) } ?: break
        }
    }

    /** Text format: "U\tword\tcount" and "B\tprevious\tword\tcount", one per line. */
    @Synchronized fun write(out: Writer) {
        for ((w, c) in unigrams) out.append("U\t").append(w).append('\t').append(c.toString()).append('\n')
        for ((p, m) in bigrams) for ((w, c) in m) out.append("B\t").append(p).append('\t').append(w).append('\t').append(c.toString()).append('\n')
        dirty = false
    }

    @Synchronized fun read(reader: BufferedReader) {
        unigrams.clear(); bigrams.clear(); pairCount = 0
        reader.forEachLine { line ->
            val f = line.split('\t')
            when {
                f.size == 3 && f[0] == "U" -> f[2].toIntOrNull()?.let { unigrams[f[1]] = it }
                f.size == 4 && f[0] == "B" -> f[3].toIntOrNull()?.let { bigrams.getOrPut(f[1]) { HashMap() }[f[2]] = it; pairCount++ }
            }
        }
        dirty = false
    }
}

/** Words the keyboard never suggests, never corrects to and never learns: a list per language plus the user's own. */
class Blocklist {
    private val words = HashSet<String>()

    fun add(list: Collection<String>) { list.forEach { val w = it.trim().lowercase(); if (w.isNotEmpty() && !w.startsWith("#")) words += w } }
    fun contains(word: String) = words.contains(word.lowercase()) || words.contains(Text.fold(word))
    fun clear() = words.clear()
    val size get() = words.size

    companion object {
        fun parse(text: String): List<String> = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
