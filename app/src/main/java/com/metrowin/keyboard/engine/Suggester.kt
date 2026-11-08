package com.metrowin.keyboard.engine

import java.util.Locale

/** Options the suggester reads at each call, so a settings change applies at once. */
data class SuggestOptions(
    val autocorrect: Boolean = true,
    val nextWord: Boolean = true,
    val phrases: Boolean = true,
    val blockOffensive: Boolean = true,
    val usePersonal: Boolean = true,
    /** Rank corrections by where the finger landed, the words around and what the user undid before (needs a [Corrector]). */
    val smart: Boolean = true,
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
    private val seed: NextWordSeed = NextWordSeed.EMPTY,
    private val corrector: Corrector? = null,
) {
    private fun blocked(w: String, o: SuggestOptions) = o.blockOffensive && blocklist.contains(w)

    /** Is [word] something the user would not want "corrected": a dictionary word or one they have typed before. */
    fun isKnown(word: String): Boolean {
        val w = word.lowercase(locale)
        return dictionary.contains(w) || user.count(w) > 0 || w.any { it.isDigit() }
    }

    /** [touch]: for each letter of [typed], how likely each key near the tap was meant (null where not known). */
    fun forTyped(typed: String, previous: String?, o: SuggestOptions, touch: List<Touch?>? = null): WordSuggestions {
        val lower = typed.lowercase(locale)
        // "l'amicho": the word is what follows the apostrophe; "l'" stays as it is and is put back on each suggestion.
        val cut = typed.indexOfFirst { Text.isApostrophe(it) }
        if (cut > 0 && cut < typed.length - 1 && !dictionary.contains(lower)) {
            val head = typed.substring(0, cut + 1)
            val r = forTyped(typed.substring(cut + 1), previous, o, touch?.drop(cut + 1))
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
        var ambiguous = false
        if (!known && lower.length >= 2) {
            val maxD = if (lower.length <= 4) 1 else 2
            if (o.smart && corrector != null) {
                // Each neighbour is priced by how likely the slip was (the finger, the keyboard map) against how likely the word is
                // (its frequency, the word before it): the best one is not the nearest by spelling but the likeliest overall.
                val ranked = dictionary.similar(lower, maxD, 12, skip)
                    .map { c -> Candidate(c.word, rescore(typed, c, previous, o, touch, corrector), c.distance) }
                    .sortedByDescending { it.score }
                for (c in ranked) scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, c.score)
                fix = ranked.firstOrNull()
                // Two readings about as likely as each other: the user is shown both, nothing is changed on their behalf.
                ambiguous = ranked.size > 1 && ranked[0].score - ranked[1].score < TIE
                if (cut < 0) split(lower, o)?.let { (pair, s) -> scores[pair] = s }
            } else {
                val near = dictionary.similar(lower, maxD, 6, skip)
                for (c in near) scores[c.word] = maxOf(scores[c.word] ?: Double.NEGATIVE_INFINITY, c.score)
                fix = near.firstOrNull()?.let { best ->
                    var s = best.score
                    if (o.usePersonal && previous != null) s += user.followerCount(previous, best.word).coerceAtMost(3)
                    Candidate(best.word, s, best.distance)
                }
            }
        }

        val ordered = scores.entries.sortedByDescending { it.value }.map { it.key }.filter { it != lower }
        val auto = if (o.autocorrect && !known && fix != null && !ambiguous && lower.length >= 2 && isSafeFix(lower, fix) && !(o.usePersonal && user.isRejected(lower, fix.word))) fix.word else null
        val list = ArrayList<String>()
        if (auto != null) list += auto
        for (w in ordered) if (w != auto && list.size < 6) list += w
        return WordSuggestions(typed, list.map { Text.matchCase(typed, it, locale) }, auto?.let { Text.matchCase(typed, it, locale) }, known)
    }

    private fun rescore(typed: String, c: Candidate, previous: String?, o: SuggestOptions, touch: List<Touch?>?, corrector: Corrector): Double {
        var s = Math.log1p(dictionary.frequency(c.word).toDouble()) - corrector.cost(typed, c.word, touch)
        if (c.distance == 0) s += ACCENT_BONUS
        if (previous != null) {
            if (o.usePersonal) user.followerCount(previous, c.word).let { if (it > 0) s += 2.5 + Math.log1p(it.toDouble()) }
            seed.count(previous, c.word).let { if (it > 0) s += 0.8 * Math.log1p(it.toDouble()) }
        }
        return s
    }

    /** "thankyou" -> "thank you": a missing space, offered but never applied by itself. */
    private fun split(lower: String, o: SuggestOptions): Pair<String, Double>? {
        if (lower.length < 5) return null
        var best: Pair<String, Double>? = null
        for (i in 2..lower.length - 2) {
            val l = lower.substring(0, i); val r = lower.substring(i)
            if (!dictionary.contains(l) || !dictionary.contains(r) || blocked(l, o) || blocked(r, o)) continue
            val s = minOf(Math.log1p(dictionary.frequency(l).toDouble()), Math.log1p(dictionary.frequency(r).toDouble()))
            // A short part ("il", "to") must be a very common word, or half the dictionary would split.
            if (s < (if (minOf(i, lower.length - i) <= 2) 11.0 else 6.0)) continue
            if (best == null || s > best.second) best = "$l $r" to s
        }
        return best?.let { it.first to it.second - 1.0 }
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
        // What the user has typed comes first, and a habit of three words beats one of two.
        if (previous != null && o.usePersonal) {
            if (before != null) for ((w, n) in user.followers("$before $previous", 8)) if (!blocked(w, o)) out[w] = 200.0 + Math.log1p(n.toDouble())
            for ((w, n) in user.followers(previous, 8)) if (!blocked(w, o)) out.putIfAbsent(w, 100.0 + Math.log1p(n.toDouble()))
        }
        // Then what people usually write after that word, a little in favour of words that are common anyway.
        for ((w, n) in seed.after(previous)) if (!blocked(w, o)) out.putIfAbsent(w, Math.log1p(n.toDouble()) + 0.3 * Math.log1p(dictionary.frequency(w).toDouble()))
        // Nothing known: the most common words of the language are a fair guess, so the bar is never empty.
        if (out.size < limit) for (c in dictionary.top(limit * 2) { blocked(it, o) }) out.putIfAbsent(c.word, c.score * 0.1)
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

    private companion object {
        const val TIE = 0.7             // closer than this and two corrections are too alike to pick one for the user
        const val ACCENT_BONUS = 4.0    // the same word with its accents is nearly always what was meant
    }
}
