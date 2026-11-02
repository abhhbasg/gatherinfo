package com.metrowin.keyboard.engine

import java.util.Locale

/** Options the suggester reads at each call, so a settings change applies at once. */
data class SuggestOptions(
    val autocorrect: Boolean = true,
    val nextWord: Boolean = true,
    val phrases: Boolean = true,
    val blockOffensive: Boolean = true,
    val usePersonal: Boolean = true,
)

/** What the bar shows while a word is being typed. [autocorrect] is the word that replaces [typed] when the user ends it. */
data class WordSuggestions(val typed: String, val candidates: List<String>, val autocorrect: String?, val typedIsKnown: Boolean)

/**
 * Turns a dictionary, the user's own model and a blocklist into suggestions: completions of the word being typed, typo
 * corrections, the likely next word, and a short phrase that continues the sentence. Everything on the phone.
 */
class Suggester(
    private val dictionary: WordDictionary,
    private val user: UserModel,
    private val blocklist: Blocklist,
    private val locale: Locale,
) {
    private fun blocked(w: String, o: SuggestOptions) = o.blockOffensive && blocklist.contains(w)

    /** Is [word] something the user would not want "corrected": a dictionary word or one they have typed before. */
    fun isKnown(word: String): Boolean {
        val w = word.lowercase(locale)
        return dictionary.contains(w) || user.count(w) > 0 || w.any { it.isDigit() }
    }

    fun forTyped(typed: String, previous: String?, o: SuggestOptions): WordSuggestions {
        val lower = typed.lowercase(locale)
        // "l'amicho": the word is what follows the apostrophe; "l'" stays as it is and is put back on each suggestion.
        val cut = typed.indexOfFirst { Text.isApostrophe(it) }
        if (cut > 0 && cut < typed.length - 1 && !dictionary.contains(lower)) {
            val head = typed.substring(0, cut + 1)
            val r = forTyped(typed.substring(cut + 1), previous, o)
            return WordSuggestions(typed, r.candidates.map { head + it }, r.autocorrect?.let { head + it }, r.typedIsKnown)
        }
        val known = isKnown(typed)
        val skip: (String) -> Boolean = { blocked(it, o) }
        val scores = LinkedHashMap<String, Double>()

        // Completions from the dictionary, lifted by what the user has typed and by the word that came before.
        for (c in dictionary.complete(lower, 12, skip)) scores[c.word] = c.score
        if (o.usePersonal) {
            for ((w, n) in user.complete(lower, 6)) if (!blocked(w, o)) scores[w] = (scores[w] ?: 0.0) + 2.0 + Math.log1p(n.toDouble())
            if (previous != null) for (w in scores.keys.toList()) {
                val n = user.followerCount(previous, w)
                if (n > 0) scores[w] = scores[w]!! + 2.5 + Math.log1p(n.toDouble())
            }
        }

        // The same word with an apostrophe ("cose" -> "cos'è"): offered even when the typed word is a real one.
        if (cut < 0) for (c in dictionary.withApostrophe(lower)) if (!blocked(c.word, o)) scores[c.word] = maxOf(scores[c.word] ?: 0.0, c.score + 1.0)

        // Typos: only when the word is not known, so a valid word is never "corrected" to a more common one.
        var fix: Candidate? = null
        if (!known && lower.length >= 2) {
            val maxD = if (lower.length <= 4) 1 else 2
            val near = dictionary.similar(lower, maxD, 6, skip)
            for (c in near) scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, c.score)
            fix = near.firstOrNull()?.let { best ->
                var s = best.score
                if (o.usePersonal && previous != null) s += user.followerCount(previous, best.word).coerceAtMost(3)
                Candidate(best.word, s, best.distance)
            }
        }

        val ordered = scores.entries.sortedByDescending { it.value }.map { it.key }.filter { it != lower }
        val auto = if (o.autocorrect && !known && fix != null && lower.length >= 2 && isSafeFix(lower, fix)) fix.word else null
        val list = ArrayList<String>()
        if (auto != null) list += auto
        for (w in ordered) if (w != auto && list.size < 6) list += w
        return WordSuggestions(typed, list.map { Text.matchCase(typed, it, locale) }, auto?.let { Text.matchCase(typed, it, locale) }, known)
    }

    /** A fix must be a real step up: one edit on a short word, an accent only, or a clearly more common neighbour. */
    private fun isSafeFix(typed: String, fix: Candidate): Boolean {
        if (typed.any { it.isDigit() }) return false
        if (fix.distance == 0) return true
        if (typed.length <= 2) return false
        return fix.distance <= (if (typed.length <= 4) 1 else 2)
    }

    /** Words likely to come after [previous] (and [before], the word before that), best first. */
    fun nextWords(previous: String?, before: String?, limit: Int, o: SuggestOptions): List<String> {
        if (!o.nextWord) return emptyList()
        val out = LinkedHashMap<String, Double>()
        if (previous != null && o.usePersonal) {
            for ((w, n) in user.followers(previous, 8)) if (!blocked(w, o)) out[w] = 10.0 + Math.log1p(n.toDouble())
        }
        if (out.size < limit && previous == null) {
            // Start of a sentence and nothing learned yet: the most common words of the language are a fair guess.
            for (c in dictionary.top(limit * 2) { blocked(it, o) }) out.putIfAbsent(c.word, c.score)
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    /** A short phrase (2 or 3 words) that continues from [previous], built from the user's own habits, or null. */
    fun phrase(previous: String?, o: SuggestOptions): String? {
        if (!o.phrases || !o.usePersonal || previous == null) return null
        val words = ArrayList<String>()
        var cur = previous
        val used = HashSet<String>().apply { add(previous) }
        repeat(3) {
            val top = user.followers(cur!!, 1).firstOrNull() ?: return@repeat
            // Only follow habits that are strong enough to be worth a tap.
            if (top.second < 2 || top.first in used || blocked(top.first, o)) return@repeat
            words += top.first; used += top.first; cur = top.first
        }
        return if (words.size >= 2) words.joinToString(" ") else null
    }
}
