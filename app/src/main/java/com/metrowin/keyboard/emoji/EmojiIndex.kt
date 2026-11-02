package com.metrowin.keyboard.emoji

import android.content.Context
import com.metrowin.keyboard.engine.Text
import java.io.BufferedReader

/**
 * Finds emoji by what they are called: ":dog", "gatto felice". Each emoji has a name and some tags in each language (from the
 * assets); a word typed matches the start of any word of them, accents ignored, and every word typed must match. Pure Kotlin
 * (the phone's font decides which results can be drawn; see [EmojiData.drawable]).
 */
class EmojiIndex(entries: List<Pair<String, List<String>>>) {
    private class Entry(val emoji: String, val terms: List<List<String>>)

    private val entries = entries.map { (emoji, terms) -> Entry(emoji, terms.map(::words)) }

    /** Emoji that match [query], best first (a whole word beats the start of one, the name beats a tag, then the order of Unicode). */
    fun search(query: String, limit: Int = 60): List<String> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, Entry>>()
        for (e in entries) {
            var total = 0
            for (t in tokens) {
                var best = 0
                e.terms.forEachIndexed { k, term ->
                    for (w in term) {
                        val s = if (w == t) 3 else if (w.startsWith(t)) 2 else 0
                        if (s > 0) best = maxOf(best, s + if (k == 0) 1 else 0)
                    }
                }
                if (best == 0) { total = 0; break }
                total += best
            }
            if (total > 0) hits += total to e
        }
        // sortedByDescending is stable, so equal scores stay in the order of the file.
        return hits.sortedByDescending { it.first }.take(limit).map { it.second.emoji }
    }

    companion object {
        private fun words(s: String) = Text.fold(s).split(' ', '-', '\'', '’', ',', '.').filter { it.isNotEmpty() }

        /** Lines "emoji<TAB>name|tag|tag". */
        fun parse(reader: BufferedReader): List<Pair<String, List<String>>> = reader.useLines { lines ->
            lines.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size == 2 && f[0].isNotEmpty()) f[0] to f[1].split('|') else null
            }.toList()
        }

        /** The words of several languages together, one entry per emoji. */
        fun merge(lists: List<List<Pair<String, List<String>>>>): EmojiIndex {
            val all = LinkedHashMap<String, ArrayList<String>>()
            for (l in lists) for ((emoji, terms) in l) all.getOrPut(emoji) { ArrayList() }.addAll(terms)
            return EmojiIndex(all.map { it.key to it.value })
        }

        private val cache = HashMap<List<String>, EmojiIndex>()

        /** The index for [language] and English (people search in English too). A language without a file gets English only. */
        @Synchronized fun load(context: Context, language: String): EmojiIndex {
            val langs = listOf(language, "en").distinct()
            return cache.getOrPut(langs) {
                merge(langs.mapNotNull { l -> runCatching { context.assets.open("emoji/$l.tsv").bufferedReader().use(::parse) }.getOrNull() })
            }
        }
    }
}
