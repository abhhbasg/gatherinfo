package com.metrowin.keyboard

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.metrowin.keyboard.engine.SwipePoint
import com.metrowin.keyboard.ime.MetroWinIME
import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import com.metrowin.keyboard.ui.Slot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** An input method wired to a real text box instead of the system's input pipeline. */
class TestIME : MetroWinIME() {
    var ic: InputConnection? = null
    var info: EditorInfo? = null
    override fun getCurrentInputConnection(): InputConnection? = ic
    override fun getCurrentInputEditorInfo(): EditorInfo? = info
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImeTest {
    private lateinit var app: KeyboardApp
    private lateinit var ime: TestIME
    private lateinit var box: EditText

    private fun idle() = ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT, pkg: String = "com.example", imeOptions: Int = 0) {
        val info = EditorInfo().apply { this.inputType = inputType; packageName = pkg; this.imeOptions = imeOptions }
        box = EditText(app)
        box.inputType = inputType
        ime.ic = box.onCreateInputConnection(EditorInfo().apply { this.inputType = inputType })
        ime.info = info
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        idle()
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.prefs.sp.edit().clear().putString(K.LANGUAGES, "it,en").putString(K.CURRENT_LANGUAGE, "it").commit()
        ime = Robolectric.buildService(TestIME::class.java).create().get()
        ime.worker = java.util.concurrent.Executor { it.run() }
        ime.onCreateInputView()
        start()
    }

    /** Types like the keyboard view does: it hands over the letter already in capitals when shift is on. Time does not move between keys. */
    private fun type(s: String) {
        for (c in s) {
            if (c == ' ') ime.onFunction(Key(KeyType.SPACE), false)
            else ime.onText(Key(KeyType.CHAR, c.toString()), if (ime.shiftState != 0) c.uppercase() else c.toString())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun backspace() { ime.onFunction(Key(KeyType.BACKSPACE), false); idle() }
    private val text get() = box.text.toString()

    @Test fun typoIsCorrectedWhenTheWordEnds() {
        type("ciaoo ")
        assertEquals("ciao ", text)
    }

    @Test fun backspaceRightAfterACorrectionGivesBackWhatWasTyped() {
        type("ciaoo ")
        backspace()
        assertEquals("ciaoo", text)
        // And the word is editable again: one more backspace removes a letter, not the whole word.
        backspace()
        assertEquals("ciao", text)
    }

    @Test fun heardTextIsWrittenInTheFieldAsItComes() {
        val v = ime.voiceCallbacks
        v.onPartial("ciao co"); idle()
        assertEquals("ciao co", text.lowercase())
        v.onPartial("ciao come"); idle()
        assertEquals("ciao come", text.lowercase())
        v.onResult("ciao come stai"); idle()
        assertEquals("ciao come stai ", text.lowercase())
        // The next phrase goes on after it.
        v.onPartial("bene"); v.onResult("bene"); idle()
        assertEquals("ciao come stai bene ", text.lowercase())
    }

    @Test fun aKeyKeepsWhatWasHeardAndTypesAfterIt() {
        ime.voiceCallbacks.onPartial("ciao"); idle()
        ime.onPress(Key(KeyType.CHAR, "x"))
        type("x")
        assertEquals("ciaox", text.lowercase())
    }

    @Test fun knownWordsAreLeftAlone() {
        type("casa ")
        assertEquals("casa ", text)
    }

    @Test fun accentIsAddedWhenOnlyTheAccentIsMissing() {
        type("perche ")
        assertEquals("perché ", text)
    }

    @Test fun doubleSpaceTypesAFullStop() {
        type("casa  ")
        assertEquals("casa. ", text)
    }

    @Test fun doubleSpaceCanBeSwitchedOff() {
        app.prefs.putBoolean(K.DOUBLE_SPACE, false)
        type("casa  ")
        assertEquals("casa  ", text)
    }

    @Test fun autocorrectCanBeSwitchedOff() {
        app.prefs.putBoolean(K.AUTOCORRECT, false); idle()
        type("ciaoo ")
        assertEquals("ciaoo ", text)
    }

    @Test fun passwordFieldsAreTypedAsTheyAreAndNothingIsLearned() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        type("ciaoo ")
        assertEquals("ciaoo ", text)
        assertNull(app.cachedEngine("it")?.user?.count("ciaoo")?.takeIf { it > 0 })
    }

    @Test fun thePickedSuggestionReplacesTheWordAndAddsASpace() {
        type("per")
        ime.onSlot(Slot("persona"))
        assertEquals("persona ", text)
    }

    @Test fun punctuationRemovesTheSpaceAddedAfterASuggestion() {
        type("per")
        ime.onSlot(Slot("persona"))
        ime.onText(Key(KeyType.CHAR, ","), ","); idle()
        assertEquals("persona,", text)
    }

    @Test fun whatIsTypedIsLearnedAndStopsBeingCorrected() {
        type("xqzjk ")   // not a word; far from everything, so typed as it is
        assertEquals("xqzjk ", text)
        assertTrue(app.engine("it").user.count("xqzjk") > 0)
    }

    @Test fun nextWordFollowsWhatWasWrittenOften() {
        repeat(3) { type("ciao casa ") }
        idle()
        val eng = app.engine("it")
        assertEquals("casa", eng.suggester.nextWords("ciao", null, 3, com.metrowin.keyboard.engine.SuggestOptions()).first())
    }

    @Test fun capitalAtTheStartOfASentence() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(1, ime.shiftState)
        type("c")
        assertEquals("C", text)
        assertEquals(0, ime.shiftState)
    }

    @Test fun launcherSearchBoxGetsNoCorrectionAndNoLearning() {
        start(pkg = "com.metrowin.launcher")
        type("ciaoo ")
        assertEquals("ciaoo ", text)
        assertEquals(0, app.engine("it").user.count("ciaoo"))
    }

    @Test fun apostropheBreaksWordsInItalianButNotInEnglish() {
        type("l'")
        assertEquals("l'", text)
        // Italian: "l'" is not a word to correct, and the next word starts fresh.
        type("ora")
        assertEquals("l'ora", text)
    }

    @Test fun languageSwitchChangesLayoutAndKeepsTyping() {
        ime.onFunction(Key(KeyType.LANGUAGE), false); idle()
        assertEquals("en", app.prefs.currentLanguage)
        type("the ")
        assertEquals("the ", text)
    }

    @Test fun swipeOverTheLettersWritesTheWord() {
        val kb = ime.keyboardView!!
        kb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        kb.layout(0, 0, 1080, kb.measuredHeight)
        val centers = kb.letterCenters()
        val pts = ArrayList<SwipePoint>()
        val word = "grazie"
        for (i in 1 until word.length) {
            val a = centers.getValue(word[i - 1]); val b = centers.getValue(word[i])
            for (s in 0 until 10) pts += SwipePoint(a.x + (b.x - a.x) * s / 10f, a.y + (b.y - a.y) * s / 10f)
        }
        pts += centers.getValue(word.last())
        ime.onSwipe(pts); idle()
        assertEquals("grazie ", text)
    }

    @Test fun numberFieldsGetTheNumberPad() {
        start(InputType.TYPE_CLASS_NUMBER)
        val kb = ime.keyboardView!!
        kb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        kb.layout(0, 0, 1080, kb.measuredHeight)
        assertTrue(kb.letterCenters().isEmpty())
    }

    @Test fun enterRunsTheEditorAction() {
        start(imeOptions = EditorInfo.IME_ACTION_SEARCH)
        var action = -1
        box.setOnEditorActionListener { _, a, _ -> action = a; true }
        type("casa")
        ime.onFunction(Key(KeyType.ENTER), false); idle()
        assertEquals(EditorInfo.IME_ACTION_SEARCH, action)
    }
}
