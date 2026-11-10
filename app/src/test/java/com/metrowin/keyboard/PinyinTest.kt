package com.metrowin.keyboard

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.metrowin.keyboard.engine.PinyinDictionary
import com.metrowin.keyboard.engine.PinyinEngine
import com.metrowin.keyboard.engine.PinyinUser
import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import com.metrowin.keyboard.layout.KeyboardBuilder
import com.metrowin.keyboard.layout.Page
import com.metrowin.keyboard.ui.KeyboardView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/** Chinese: the Pinyin engine on the word list the app ships, and the keyboard typing with it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinyinTest {
    private lateinit var app: KeyboardApp
    private lateinit var ime: TestIME
    private lateinit var box: EditText

    private fun idle() = ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        val info = EditorInfo().apply { this.inputType = inputType; packageName = "com.example" }
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
        app.prefs.sp.edit().clear().putString(K.LANGUAGES, "zh,en").putString(K.CURRENT_LANGUAGE, "zh").commit()
        ime = Robolectric.buildService(TestIME::class.java).create().get()
        ime.worker = java.util.concurrent.Executor { it.run() }
        ime.onCreateInputView()
        start()
    }

    private fun type(s: String) {
        for (c in s) {
            if (c == ' ') ime.onFunction(Key(KeyType.SPACE), false)
            else ime.onText(Key(KeyType.CHAR, c.toString()), c.toString())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private val text get() = box.text.toString()
    private fun offered() = ime.suggestionBar!!.slotsForTest().filterNotNull()
    private fun pick(word: String) { ime.onSlot(offered().first { it.text == word }); idle() }

    private fun engine(user: PinyinUser = PinyinUser()) = PinyinEngine(words, user)

    private companion object {
        /** The word list the app ships, read once for all the tests. */
        val words: PinyinDictionary by lazy { java.io.File("src/main/assets/pinyin/zh.txt").bufferedReader().use { PinyinDictionary.parse(it) } }
    }

    // --- the engine -------------------------------------------------------------------------------------------------

    @Test fun aWordIsFoundByItsReading() {
        val e = engine()
        assertEquals("你好", e.candidates("nihao").first().text)
        assertEquals("中国", e.candidates("zhongguo").first().text)
        assertEquals("我们", e.candidates("women").first().text)
        // "v" is the "ü" of the keyboard.
        assertTrue(e.candidates("nv").take(3).any { it.text == "女" })
        assertTrue(e.candidates("lvse").take(3).any { it.text == "绿色" })
    }

    @Test fun everyCandidateSaysHowMuchOfTheLettersItStandsFor() {
        val c = engine().candidates("nihaoma")
        // The first stands for all the letters; the shorter words after it for the first syllables only.
        assertEquals("你好吗", c.first().text)
        assertEquals(7, c.first().consumed)
        assertEquals(5, c.first { it.text == "你好" }.consumed)
        assertEquals(2, c.first { it.text == "你" }.consumed)
    }

    @Test fun lettersThatAreNotASyllableYetOfferTheWordsThatBeginSo() {
        val e = engine()
        assertTrue(e.candidates("zhongg").take(5).any { it.text == "中国" })
        assertTrue(e.candidates("nih").take(5).any { it.text == "你好" })
        // The initials of the syllables are enough.
        assertTrue(e.candidates("zg").take(8).any { it.text == "中国" })
        assertTrue(e.candidates("bj").take(8).any { it.text == "北京" })
    }

    @Test fun theApostropheSplitsSyllables() {
        val e = engine()
        // "xian" is one syllable or two; with the apostrophe only the two-syllable words are left.
        assertTrue(e.candidates("xian").take(12).any { it.text.length == 1 })
        val split = e.candidates("xi'an")
        assertEquals("西安", split.first().text)
        assertEquals(5, split.first().consumed)
        assertTrue(split.filter { it.consumed == 5 }.none { it.text.length == 1 })
    }

    @Test fun aLongSentenceIsReadAsARowOfWords() {
        val c = engine().candidates("wojintianhengaoxing").first()
        assertEquals("我今天很高兴", c.text)
        assertEquals(19, c.consumed)
    }

    @Test fun whatIsNotPinyinOffersNothing() {
        val e = engine()
        assertTrue(e.candidates("").isEmpty())
        assertTrue(e.candidates("Ni").isEmpty())
        assertTrue(PinyinEngine(PinyinDictionary.EMPTY).candidates("nihao").isEmpty())
    }

    @Test fun aWordThatWasChosenComesFirstAndAPhraseIsLearned() {
        val user = PinyinUser()
        val e = engine(user)
        val before = e.candidates("shi").map { it.text }
        val rare = before[6]
        repeat(3) { user.learn("shi", rare) }
        assertEquals(rare, e.candidates("shi").first().text)
        // A phrase the word list does not hold, once learned, is a word of its own; and it survives being saved.
        user.learn("ming'tian'jian'ba", "明天见吧")
        val saved = StringWriter().also { user.write(it) }.toString()
        val again = PinyinUser().apply { read(StringReader(saved).buffered()) }
        assertEquals("明天见吧", engine(again).candidates("mingtianjianba").first().text)
        assertEquals(rare, engine(again).candidates("shi").first().text)
    }

    // --- the keyboard -----------------------------------------------------------------------------------------------

    @Test fun spaceTakesTheFirstWord() {
        type("nihao")
        // The letters are in the field while they are typed, and the bar offers the words.
        assertEquals("nihao", text)
        assertEquals("你好", offered().first().text)
        type(" ")
        assertEquals("你好", text)
        // With nothing being typed, space is a space.
        type(" ")
        assertEquals("你好 ", text)
    }

    @Test fun choosingAWordLeavesTheRestToBeChosen() {
        type("nihaoma")
        pick("你好")
        assertEquals("你好ma", text)
        assertTrue(offered().any { it.text == "吗" })
        pick("吗")
        assertEquals("你好吗", text)
        assertTrue(offered().isEmpty())
    }

    @Test fun aSignTakesTheFirstWordAndIsChinese() {
        val page = KeyboardBuilder.letters(app.layouts.get("zh")!!, false, true, true)
        val bottom = page.rows.last().filter { it.type == KeyType.CHAR }.map { it.label }
        assertEquals(listOf("，", "。"), bottom)
        type("women，")
        assertEquals("我们，", text)
    }

    @Test fun enterKeepsTheLettersAndBackspaceTakesOneAway() {
        type("nihap")
        ime.onFunction(Key(KeyType.BACKSPACE), false); idle()
        assertEquals("niha", text)
        type("o")
        assertEquals("你好", offered().first().text)
        ime.onFunction(Key(KeyType.ENTER), false); idle()
        assertEquals("nihao", text)
        assertTrue(offered().isEmpty())
    }

    @Test fun aCapitalLetterIsALatinLetter() {
        type("ni")
        ime.onText(Key(KeyType.CHAR, "h"), "H"); idle()
        assertEquals("niH", text)
    }

    @Test fun aPasswordIsTypedInLetters() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        type("nihao")
        assertEquals("nihao", text)
        assertTrue(offered().isEmpty())
    }

    @Test fun changingLanguageKeepsWhatWasTypedAndTypesLatin() {
        type("ni")
        ime.onFunction(Key(KeyType.LANGUAGE), false); idle()
        assertEquals("ni", text)
        type(" hello ")
        assertEquals("ni hello ", text)
    }

    @Test fun theSymbolPagesOfChineseHaveChineseSigns() {
        val zh = app.layouts.get("zh")!!
        val labels = KeyboardBuilder.symbols(Page.SYMBOLS_1, false, zh).keys.map { it.label }
        assertTrue(labels.containsAll(listOf("（", "）", "？", "！", "；", "：", "、", "，", "。")))
        // Holding a Chinese sign offers the plain one.
        assertEquals(listOf("？", "?"), KeyboardBuilder.symbols(Page.SYMBOLS_1, false, zh).keys.first { it.label == "？" }.popups)
    }

    // --- the Windows Phone layout -----------------------------------------------------------------------------------

    private fun laidOut(page: com.metrowin.keyboard.layout.KeyboardPage): KeyboardView {
        val kb = KeyboardView(app)
        kb.setPage(page)
        kb.setMetrics(100, 3, 100, "center")
        kb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        kb.layout(0, 0, 1080, kb.measuredHeight)
        return kb
    }

    @Test fun everyLetterKeyIsAsWideAndTheSecondRowIsSetIn() {
        val page = KeyboardBuilder.letters(app.layouts.get("en")!!, false, true, false)
        laidOut(page)
        fun key(l: String) = page.keys.first { it.label == l }
        val w = key("q").width
        for (l in "qwertyuiopasdfghjklzxcvbnm") assertEquals(l.toString(), w, key(l.toString()).width, 0.5f)
        // "a" starts half a key in from "q", as on Windows Phone, and the row is centred.
        assertEquals(key("q").left + (key("w").left - key("q").left) / 2f, key("a").left, 1f)
        assertEquals(key("a").left - 0f, 1080f - key("l").right, 1f)
        // The room beside the row still types its first and last letter.
        assertTrue(key("a").touched(2f, key("a").centerY))
        assertTrue(key("l").touched(1078f, key("l").centerY))
        // Shift and backspace take what the letters of their row leave: a key and a half each.
        val shift = page.keys.first { it.type == KeyType.SHIFT }
        assertEquals(w * 1.5f, shift.width, w * 0.1f)
    }

    @Test fun theKeysAreNamedAsOnWindowsPhone() {
        val en = app.layouts.get("en")!!
        val letters = KeyboardBuilder.letters(en, false, true, true)
        assertEquals("&123", letters.keys.first { it.type == KeyType.SYMBOLS }.label)
        assertEquals("ENU", letters.keys.first { it.type == KeyType.LANGUAGE }.label)
        assertEquals("CHS", KeyboardBuilder.letters(app.layouts.get("zh")!!, false, true, true).keys.first { it.type == KeyType.LANGUAGE }.label)
        val first = KeyboardBuilder.symbols(Page.SYMBOLS_1, false, en)
        assertEquals("abcd", first.keys.first { it.type == KeyType.SYMBOLS }.label)
        assertEquals("@ # $ % & ( ) - \\".split(' '), first.rows[1].map { it.label })
        assertEquals("! ; : ' \" ? /".split(' '), first.rows[2].filter { it.type == KeyType.CHAR }.map { it.label })
        // Nothing is lost between the two pages.
        val all = (first.keys + KeyboardBuilder.symbols(Page.SYMBOLS_2, false, en).keys).map { it.label }
        for (c in "+ = * _ [ ] { } < > ^ | ~ €".split(' ')) assertTrue(c, all.contains(c))
        // A layout without a label shows the three letters of its language.
        val custom = com.metrowin.keyboard.layout.LanguageLayout.parse("id=xx\nlocale=fi_FI\nrow=a b c", false)!!
        assertEquals("FIN", custom.keyLabel)
    }
}
