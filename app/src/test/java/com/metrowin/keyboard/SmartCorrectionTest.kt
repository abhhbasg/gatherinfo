package com.metrowin.keyboard

import com.metrowin.keyboard.engine.*
import com.metrowin.keyboard.layout.LanguageLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.util.Locale

class SmartCorrectionTest {
    private val qwerty = LanguageLayout.parse("id=en\nname=English\nlocale=en_US\nrow=q w e r t y u i o p\nrow=a s d f g h j k l\nrow=z x c v b n m\n", true)!!
    private val corrector = Corrector(KeyGrid.of(qwerty))
    private val opts = SuggestOptions()

    private fun dict(text: String) = WordDictionary.parse(StringReader(text.trimIndent()).buffered(), Locale.ENGLISH)

    private fun suggester(words: String, seed: NextWordSeed = NextWordSeed.EMPTY, user: UserModel = UserModel()) =
        Suggester(dict(words), user, Blocklist(), Locale.ENGLISH, seed, Corrector(KeyGrid.of(qwerty)))

    @Test fun aNeighbouringKeyIsACheaperSlipThanAFarOne() {
        assertTrue(corrector.cost("hsllo", "hallo") < corrector.cost("hsllo", "hpllo"))
        // A letter left out, a swap and a missing accent each cost less than a slip across the keyboard.
        assertTrue(corrector.cost("wrd", "word") < 3.5)
        assertTrue(corrector.cost("form", "from") < corrector.cost("form", "fork") + 1.0)
        assertTrue(corrector.cost("cafe", "café") < 0.5)
    }

    @Test fun theFingerOutweighsTheKeyboardMap() {
        // The tap on the 's' of "hsllo" landed almost on the 'e': hello is the likelier reading although the map says 'a' is as near.
        val touch: List<Touch?> = listOf(null, mapOf('s' to 0.5f, 'e' to 0.9f, 'a' to 0.06f), null, null, null)
        assertTrue(corrector.cost("hsllo", "hello", touch) < corrector.cost("hsllo", "hallo", touch))
        // Without the tap the nearer key on the map wins.
        assertTrue(corrector.cost("hsllo", "hallo") < corrector.cost("hsllo", "hello"))
    }

    @Test fun theWordBeforeDecidesBetweenEquallyLikelyFixes() {
        val words = "hope 500\nhome 500\nhole 100"
        val plain = suggester(words).forTyped("hoe", null, opts)
        assertNull("two equally likely fixes are not applied", plain.autocorrect)
        assertTrue(plain.candidates.containsAll(listOf("hope", "home")))
        val seed = NextWordSeed(mapOf("my" to listOf("home" to 900)))
        val withContext = suggester(words, seed).forTyped("hoe", "my", opts)
        assertEquals("home", withContext.autocorrect)
    }

    @Test fun aCorrectionTheUserUndidIsNotMadeAgain() {
        val user = UserModel()
        val seed = NextWordSeed(mapOf("my" to listOf("home" to 900)))
        val s = suggester("hope 500\nhome 500", seed, user)
        assertEquals("home", s.forTyped("hoe", "my", opts).autocorrect)
        user.reject("hoe", "home")
        assertNull(s.forTyped("hoe", "my", opts).autocorrect)
        // It survives the file.
        val out = java.io.StringWriter(); user.write(out)
        val again = UserModel().also { it.read(out.toString().reader().buffered()) }
        assertTrue(again.isRejected("hoe", "home"))
    }

    @Test fun smartCorrectionCanBeSwitchedOff() {
        val words = "hope 500\nhome 500"
        val off = suggester(words).forTyped("hoe", null, SuggestOptions(smart = false))
        assertNotNull("the plain suggester picks one by spelling and frequency", off.autocorrect)
    }

    @Test fun aMissingSpaceIsOfferedButNeverApplied() {
        val s = suggester("thank 5000\nyou 90000\nthankful 300")
        val r = s.forTyped("thankyou", null, opts)
        assertTrue(r.candidates.contains("thank you"))
        assertNotEquals("thank you", r.autocorrect)
    }

    @Test fun aKnownWordIsLeftAlone() {
        val r = suggester("hope 500\nhome 500").forTyped("hope", null, opts)
        assertNull(r.autocorrect)
        assertTrue(r.typedIsKnown)
    }
}
