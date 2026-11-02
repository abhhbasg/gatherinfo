package com.metrowin.keyboard.engine

import java.io.BufferedReader

/**
 * Which words usually follow which, shipped with the app so the next-word bar helps from the first day. Read only; what the user
 * types is learned in [UserModel] and wins over this. File lines: "previous<TAB>word<TAB>count<TAB>word<TAB>count...", "^" is the
 * start of a sentence.
 */
class NextWordSeed(private val followers: Map<String, List<Pair<String, Int>>>) {
    /** The words that follow [previous] (null: the start of a sentence), most common first. */
    fun after(previous: String?): List<Pair<String, Int>> = followers[previous ?: START].orEmpty()

    companion object {
        const val START = "^"
        val EMPTY = NextWordSeed(emptyMap())

        fun parse(reader: BufferedReader): NextWordSeed {
            val map = HashMap<String, List<Pair<String, Int>>>()
            reader.forEachLine { line ->
                val f = line.split('\t')
                if (f.size < 3) return@forEachLine
                val pairs = (1 until f.size - 1 step 2).mapNotNull { i -> f[i + 1].toIntOrNull()?.let { f[i] to it } }
                if (pairs.isNotEmpty()) map[f[0]] = pairs
            }
            return NextWordSeed(map)
        }
    }
}
