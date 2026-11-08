package com.metrowin.keyboard

import com.metrowin.keyboard.calibration.Bias
import com.metrowin.keyboard.calibration.Calibrator
import com.metrowin.keyboard.calibration.Metrics
import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CalibratorTest {
    private val rows = listOf("qwertyuiop", "asdfghjkl_", "zxcvbnm___")
    private val w = 1000; private val h = 300

    /** Three rows of 90 x 80 px keys with 10 px gaps. */
    private val keys: List<Key> = rows.flatMapIndexed { r, row ->
        row.mapIndexedNotNull { i, c ->
            if (c == '_') null else Key(KeyType.CHAR, c.toString()).apply { left = 10f + i * 100f; right = left + 90f; top = 10f + r * 90f; bottom = top + 80f }
        }
    }
    private val space = Key(KeyType.SPACE).apply { left = 200f; right = 800f; top = 280f; bottom = 295f }
    private val all = keys + space

    private val sentences = List(6) { "the quick brown fox jumps over a lazy dog" }

    private fun nearest(x: Float, y: Float) = all.minByOrNull { it.distanceSq(x, y) }!!

    /** Types every sentence with a finger that lands [dy] key heights low and [noise] key sizes off, retrying a missed key as a user would. */
    private fun type(c: Calibrator, dy: Float, noise: Float, seed: Long = 1, dyOf: (Key) -> Float = { dy }) {
        val rnd = Random(seed)
        var guard = 0
        while (!c.done && guard++ < 5000) {
            val ch = c.sentence[c.position]
            val target = if (ch == ' ') space else keys.firstOrNull { it.label == ch.toString() } ?: continue
            val x = target.centerX + rnd.nextGaussian().toFloat() * noise * 90f
            val y = target.centerY + dyOf(target) * 80f + rnd.nextGaussian().toFloat() * noise * 80f
            c.tap(x, y, all, w, h, nearest(x, y))
        }
    }

    @Test fun learnsAFingerThatLandsLow() {
        val c = Calibrator(sentences)
        type(c, dy = 0.3f, noise = 0.12f)
        val r = c.fit()!!
        assertNotNull(r.bias)
        assertTrue("misses ${r.before} -> ${r.after}", r.after < r.before)
        // A touch at the bottom of the "g" key that used to count for the row below is moved back up.
        val g = keys.first { it.label == "g" }
        val (_, y) = Bias.correct(g.centerX, g.bottom + 4f, w, h, g.width, g.height, r.bias)
        assertTrue(y < g.bottom + 4f - 10f)
    }

    @Test fun learnsAnOffsetOfAFewMillimetresThatNeverMissesAKey() {
        val c = Calibrator(sentences)
        type(c, dy = 0.1f, noise = 0.03f)     // 8 px low, well inside the key: no miss to count
        assertEquals(0, c.misses)
        val r = c.fit()!!
        assertNotNull(r.bias)
        assertTrue("${r.errBeforePx} -> ${r.errAfterPx}", r.errAfterPx < r.errBeforePx * 0.6)
    }

    @Test fun learnsTheCurveOfAThumb() {
        val c = Calibrator(sentences)
        // Nothing in the middle of the keyboard, a third of a key low at both edges.
        type(c, dy = 0f, noise = 0.05f, dyOf = { k -> val u = (k.centerX / w - 0.5f) * 2; 0.35f * u * u })
        val r = c.fit()!!
        assertEquals(12, r.bias!!.size)
        assertTrue("${r.errBeforePx} -> ${r.errAfterPx}", r.errAfterPx < r.errBeforePx * 0.75)
    }

    @Test fun anAccentIsAskedForByHoldingItsKey() {
        val e = Key(KeyType.CHAR, "e", popups = listOf("è", "é", "e")).apply { left = 0f; right = 90f; top = 0f; bottom = 80f }
        val c = Calibrator(listOf("è"))
        c.tap(40f, 40f, listOf(e, space), w, h, e, "e")       // a plain tap is a sample but not the accent
        assertEquals(0, c.position); assertEquals(0, c.misses)
        assertEquals("e" to "è", c.holdHint())
        c.tap(40f, 40f, listOf(e, space), w, h, e, "è")
        assertTrue(c.done)
        assertEquals(2, c.sampleCount)
    }

    @Test fun moreSentencesResumeTheTestKeepingTheTaps() {
        val a = keys.first { it.label == "a" }
        val c = Calibrator(listOf("a"))
        c.tap(a.centerX, a.centerY, all, w, h, a)
        assertTrue(c.done)
        c.more(listOf("a"))
        assertTrue(!c.done); assertEquals(1, c.sampleCount)
    }

    @Test fun leavesAnAccurateFingerAlone() {
        val c = Calibrator(sentences)
        type(c, dy = 0f, noise = 0.05f)
        assertNull(c.fit()!!.bias)
    }

    @Test fun symbolsPageDoesNotConsumeTheSentence() {
        val c = Calibrator(listOf("ab"))
        val one = Key(KeyType.CHAR, "1").apply { left = 0f; right = 90f; top = 0f; bottom = 80f }
        c.tap(10f, 10f, listOf(one, space), w, h, one)
        assertEquals(0, c.position); assertEquals(0, c.taps)
    }

    @Test fun needsEnoughTaps() {
        assertNull(Calibrator(sentences).fit())
    }

    @Test fun sentenceWaitsForTheRightKeyAndKeepsTheMiss() {
        val c = Calibrator(listOf("ab"))
        val a = keys.first { it.label == "a" }; val s = keys.first { it.label == "s" }
        c.tap(s.centerX, s.centerY, all, w, h, s)
        assertEquals(0, c.position); assertEquals(1, c.misses); assertEquals(1, c.taps)
        c.tap(a.centerX, a.centerY, all, w, h, a)
        assertEquals(1, c.position)
    }

    @Test fun adviceGrowsHeightForVerticalMissesAndNeverShrinks() {
        val c = Calibrator(sentences)
        type(c, dy = 0f, noise = 0.45f)   // sloppy taps: many misses that no offset can fix
        val r = c.fit()!!
        val m = Metrics(100, 3, 90)
        val next = Calibrator.advise(m, r)
        assertTrue(next.heightPct >= m.heightPct && next.gapDp <= m.gapDp && next.widthPct >= m.widthPct)
        assertTrue(next != m)
    }

    @Test fun biasRoundTripsThroughAString() {
        val b = doubleArrayOf(0.1, -0.2, 0.3, 0.0, 0.5, -0.5)
        assertTrue(b.contentEquals(Bias.decode(Bias.encode(b))!!))
        assertNull(Bias.decode("")); assertNull(Bias.decode("1,2"))
    }
}
