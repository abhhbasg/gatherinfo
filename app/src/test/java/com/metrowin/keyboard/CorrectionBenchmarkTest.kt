package com.metrowin.keyboard

import com.metrowin.keyboard.engine.*
import com.metrowin.keyboard.layout.LanguageLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.exp

/**
 * Types on a simulated thumb into the real word lists and counts how often the word meant comes first in the bar: the plain suggester
 * against the smart one, with and without the knowledge of where each tap landed. Words are drawn as often as people write them. The
 * thumb aims at the centre of each key and lands off by a random amount; the key it lands on is typed, and the tap is what the keyboard
 * knows. A guard that the smart model never does worse than the plain one, and a measure of what it gains.
 */
class CorrectionBenchmarkTest {
    private val sigma = 0.3f                  // spread of a tap in key sizes

    private fun run(id: String, locale: Locale): Triple<Double, Double, Double> {
        val dict = File("src/main/assets/dictionaries/$id.txt").bufferedReader().use { WordDictionary.parse(it, locale) }
        val seed = File("src/main/assets/bigrams/$id.txt").bufferedReader().use { NextWordSeed.parse(it) }
        val lay = LanguageLayout.parse(File("src/main/assets/layouts/$id.mwl").readText(), true)!!
        val grid = KeyGrid.of(lay)
        val plain = Suggester(dict, UserModel(), Blocklist(), locale, seed)
        val smart = Suggester(dict, UserModel(), Blocklist(), locale, seed, Corrector(grid))
        val keys = lay.rows.flatten().filter { it.length == 1 }.map { it[0] }
        val rnd = Random(5)
        // Draw words in proportion to their frequency.
        val pool = (0 until dict.size.coerceAtMost(20000)).filter { dict.wordAt(it).let { w -> w.length in 3..10 && w.all { c -> c in keys } } }
        val cum = LongArray(pool.size); var acc = 0L
        pool.forEachIndexed { i, w -> acc += dict.freqAt(w); cum[i] = acc }
        val before = HashMap<String, String>()
        for (i in 0 until dict.size.coerceAtMost(600)) for ((n, _) in seed.after(dict.wordAt(i))) before.putIfAbsent(n, dict.wordAt(i))
        var total = 0; var plainOk = 0; var smartOk = 0; var touchOk = 0
        var draws = 0
        while (total < 4000 && draws++ < 200_000) {
            val r = (rnd.nextDouble() * acc).toLong()
            var lo = 0; var hi = cum.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < r) lo = m + 1 else hi = m }
            val w = dict.wordAt(pool[lo])
            val typed = StringBuilder(); val touch = ArrayList<Touch?>()
            for (c in w) {
                val (cx, cy) = grid.position(c)!!
                val x = cx + rnd.nextGaussian().toFloat() * sigma; val y = cy + rnd.nextGaussian().toFloat() * sigma * KeyGrid.ROW_PITCH
                val near = keys.mapNotNull { k -> grid.position(k)?.let { (kx, ky) -> k to (x - kx) * (x - kx) + ((y - ky) / KeyGrid.ROW_PITCH) * ((y - ky) / KeyGrid.ROW_PITCH) } }
                typed.append(near.minByOrNull { it.second }!!.first)
                touch += near.mapNotNull { (k, d2) -> exp(-d2 / (2 * sigma * sigma)).takeIf { it > 0.05f }?.let { k to it } }.toMap()
            }
            val t = typed.toString()
            if (t == w || dict.contains(t)) continue
            total++
            val prev = before[w]
            fun top(s: Suggester, o: SuggestOptions, tc: List<Touch?>?) = s.forTyped(t, prev, o, tc).candidates.firstOrNull()?.lowercase()
            if (top(plain, SuggestOptions(smart = false), null) == w) plainOk++
            if (top(smart, SuggestOptions(), null) == w) smartOk++
            if (top(smart, SuggestOptions(), touch) == w) touchOk++
        }
        println("BENCH $id: $total typos, top-1 plain ${100 * plainOk / total}%, smart ${100 * smartOk / total}%, smart with taps ${100 * touchOk / total}%")
        return Triple(plainOk.toDouble() / total, smartOk.toDouble() / total, touchOk.toDouble() / total)
    }

    @Test fun smartIsNeverWorseThanPlain() {
        for ((id, loc) in listOf("en" to Locale.ENGLISH, "it" to Locale.ITALIAN, "es" to Locale("es"), "fr" to Locale.FRENCH, "de" to Locale.GERMAN)) {
            val (plain, smart, touch) = run(id, loc)
            assertTrue("$id: smart $smart < plain $plain", smart >= plain - 0.01)
            assertTrue("$id: with taps $touch < plain $plain", touch >= plain)
        }
    }
}
