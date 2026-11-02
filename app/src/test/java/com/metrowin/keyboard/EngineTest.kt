package com.metrowin.keyboard

import com.metrowin.keyboard.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.util.Locale

class EngineTest {
    private val itWords = """
        di 900000
        che 800000
        il 700000
        la 690000
        perché 60000
        perfetto 20000
        persona 30000
        ciao 50000
        casa 45000
        cosa 44000
        come 80000
        sono 90000
        hello 10
        hell 5
        help 40
        held 20
        helmet 3
    """.trimIndent()

    private fun dict(text: String = itWords) = WordDictionary.parse(StringReader(text).buffered(), Locale.ITALIAN)

    @Test fun foldRemovesAccents() {
        assertEquals("perche", Text.fold("Perché"))
        assertEquals("naive", Text.fold("naïve"))
    }

    @Test fun editDistanceHandlesTranspositionAndLimit() {
        assertEquals(1, EditDistance.within("ciao", "caio", 2))
        assertEquals(1, EditDistance.within("casa", "cas", 2))
        assertEquals(3, EditDistance.within("abcdef", "uvwxyz", 2))
    }

    @Test fun parsesFrequencyAndHunspellAndPlainLists() {
        val freq = dict("ciao\t10\nmondo 5\n# comment\n")
        assertEquals(2, freq.size)
        assertEquals(10, freq.frequency("ciao"))
        val dic = dict("3\nciao/AB\nmondo/S\ncasa\n")
        assertEquals(3, dic.size)
        assertTrue(dic.contains("mondo"))
        val ranked = dict("primo\nsecondo\nterzo\n")
        assertTrue(ranked.frequency("primo") > ranked.frequency("terzo"))
    }

    @Test fun completesIgnoringAccentsAndRanksByFrequency() {
        val d = dict()
        val c = d.complete("per", 5).map { it.word }
        assertEquals(listOf("perché", "persona", "perfetto").sorted(), c.sorted())
        assertEquals("perché", c.first())
        assertTrue(d.complete("perc", 3).map { it.word }.contains("perché"))
    }

    @Test fun correctsTyposAndAccentOnlyMistakes() {
        val s = Suggester(dict(), UserModel(), Blocklist(), Locale.ITALIAN)
        assertEquals("ciao", s.forTyped("caio", null, SuggestOptions()).autocorrect)
        assertEquals("perché", s.forTyped("perche", null, SuggestOptions()).autocorrect)
        // A known word is never replaced.
        assertNull(s.forTyped("casa", null, SuggestOptions()).autocorrect)
        // And autocorrect can be switched off while the bar still offers the fix.
        val off = s.forTyped("caio", null, SuggestOptions(autocorrect = false))
        assertNull(off.autocorrect)
        assertTrue(off.candidates.contains("ciao"))
    }

    /** The word lists that ship with the app, read from the assets folder (the tests run from app/). */
    private fun shipped(lang: String, locale: Locale): Suggester {
        val d = java.io.File("src/main/assets/dictionaries/$lang.txt").bufferedReader().use { WordDictionary.parse(it, locale) }
        assertTrue("$lang has a real dictionary", d.size > 30_000)
        return Suggester(d, UserModel(), Blocklist(), locale)
    }

    @Test fun shippedDictionariesFixTypos() {
        val o = SuggestOptions()
        fun fix(s: Suggester, typed: String) = s.forTyped(typed, null, o).autocorrect
        val it = shipped("it", Locale.ITALIAN)
        assertEquals("perché", fix(it, "perche"))
        assertEquals("perché", fix(it, "perchè"))
        assertEquals("più", fix(it, "piu"))
        assertEquals("perfetto", fix(it, "perfeto"))
        assertNull(fix(it, "casa"))
        val en = shipped("en", Locale.ENGLISH)
        assertEquals("don't", fix(en, "dont"))
        assertEquals("because", fix(en, "becuase"))
        assertEquals("tomorrow", fix(en, "tommorow"))
        assertEquals("también", fix(shipped("es", Locale("es")), "tambien"))
        assertEquals("maintenant", fix(shipped("fr", Locale.FRENCH), "maintenat"))
        assertEquals("wirklich", fix(shipped("de", Locale.GERMAN), "wirklihc"))
    }

    @Test fun offersTheApostropheForm() {
        val it = shipped("it", Locale.ITALIAN)
        // "cose" is a word, so it is kept, but "cos'è" is offered next to it.
        val r = it.forTyped("cose", null, SuggestOptions())
        assertNull(r.autocorrect)
        assertTrue(r.candidates.toString(), r.candidates.contains("cos'è"))
        // The word after an apostrophe is checked on its own: "l'" stays.
        assertEquals("l'amico", it.forTyped("l'amicp", null, SuggestOptions()).autocorrect)
        assertNull(it.forTyped("l'amico", null, SuggestOptions()).autocorrect)
        assertTrue(shipped("fr", Locale.FRENCH).forTyped("cest", null, SuggestOptions()).let { it.autocorrect == "c'est" || it.candidates.contains("c'est") })
    }

    @Test fun keepsCapitalisation() {
        val s = Suggester(dict(), UserModel(), Blocklist(), Locale.ITALIAN)
        assertEquals("Ciao", s.forTyped("Caio", null, SuggestOptions()).autocorrect)
        assertEquals("CIAO", s.forTyped("CAIO", null, SuggestOptions()).autocorrect)
    }

    @Test fun blockedWordsNeverAppear() {
        val b = Blocklist().apply { add(listOf("cosa")) }
        val s = Suggester(dict(), UserModel(), b, Locale.ITALIAN)
        assertFalse(s.forTyped("cos", null, SuggestOptions()).candidates.contains("cosa"))
        assertTrue(s.forTyped("cos", null, SuggestOptions(blockOffensive = false)).candidates.contains("cosa"))
    }

    @Test fun learnsNextWordAndPhrase() {
        val u = UserModel()
        repeat(3) { u.learn("buon", "giorno"); u.learn("giorno", "a"); u.learn("a", "tutti") }
        val s = Suggester(dict(), u, Blocklist(), Locale.ITALIAN)
        assertEquals("giorno", s.nextWords("buon", null, 3, SuggestOptions()).first())
        assertEquals("giorno a tutti", s.phrase("buon", SuggestOptions()))
        assertNull(s.phrase("buon", SuggestOptions(phrases = false)))
        assertTrue(s.nextWords("buon", null, 3, SuggestOptions(nextWord = false)).isEmpty())
    }

    @Test fun userVocabularyStopsAutocorrect() {
        val u = UserModel()
        val s = Suggester(dict(), u, Blocklist(), Locale.ITALIAN)
        assertNotNull(s.forTyped("caio", null, SuggestOptions()).autocorrect)
        u.learn(null, "caio")
        assertNull(s.forTyped("caio", null, SuggestOptions()).autocorrect)
    }

    @Test fun userModelRoundTripAndForget() {
        val u = UserModel()
        u.learn("a", "b"); u.learn("a", "b"); u.learn(null, "c")
        val text = java.io.StringWriter().also { u.write(it) }.toString()
        val back = UserModel().apply { read(text.reader().buffered()) }
        assertEquals(2, back.followerCount("a", "b"))
        assertEquals(1, back.count("c"))
        back.forget("b")
        assertEquals(0, back.followerCount("a", "b"))
    }

    @Test fun userModelStaysBounded() {
        val u = UserModel(maxWords = 50, maxPairs = 100)
        for (i in 0 until 2000) u.learn("w${i % 7}", "x$i")
        // After pruning, the model is far smaller than the 2000 distinct words that went in.
        assertTrue(u.complete("x", 5000).size < 400)
    }

    @Test fun contextWordsAreLinkedOnlyAcrossSpaces() {
        // Last word first; the full stop ends the context.
        assertEquals(listOf("giorno", "buon"), Text.previousWords("ciao. buon giorno ", false))
        assertEquals(emptyList<String>(), Text.previousWords("buon giorno, ", false))
        assertEquals(emptyList<String>(), Text.previousWords("ciao, ", false))
        assertEquals("ciao", Text.trailingWord("dire ciao", false))
        assertEquals("don't", Text.trailingWord("say don't", true))
        assertEquals("t", Text.trailingWord("say don't", false))
    }

    // --- swipe typing -------------------------------------------------------------------------------------------------

    private val qwerty: Map<Char, SwipePoint> = buildMap {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offsets = listOf(0f, 0.5f, 1.5f)
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, c -> put(c, SwipePoint((i + offsets[r] + 0.5f) * 40f, (r + 0.5f) * 50f)) } }
    }

    private fun trace(word: String): List<SwipePoint> {
        val pts = word.map { qwerty.getValue(it) }
        // Many points along the straight segments, like a real finger.
        val out = ArrayList<SwipePoint>()
        for (i in 1 until pts.size) for (s in 0 until 8) {
            val t = s / 8f
            out += SwipePoint(pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t, pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t)
        }
        out += pts.last()
        return out
    }

    @Test fun swipeFindsTheTracedWord() {
        val d = dict()
        val dec = SwipeDecoder(d, null) { false }
        assertEquals("hello", dec.decode(trace("hello"), qwerty, 40f).first())
        assertEquals("help", dec.decode(trace("help"), qwerty, 40f).first())
        assertEquals("ciao", dec.decode(trace("ciao"), qwerty, 40f).first())
        assertEquals("casa", dec.decode(trace("casa"), qwerty, 40f).first())
    }

    @Test fun swipeIgnoresBlockedWordsAndTinyMovements() {
        val d = dict()
        val dec = SwipeDecoder(d, null) { it == "hello" }
        assertFalse(dec.decode(trace("hello"), qwerty, 40f).contains("hello"))
        assertTrue(SwipeDecoder(d, null) { false }.decode(listOf(SwipePoint(1f, 1f), SwipePoint(2f, 2f)), qwerty, 40f).isEmpty())
    }
}
