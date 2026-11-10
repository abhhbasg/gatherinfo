package com.metrowin.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.metrowin.keyboard.K
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.clipboard.ClipItem
import com.metrowin.keyboard.emoji.EmojiData
import com.metrowin.keyboard.emoji.EmojiIndex
import com.metrowin.keyboard.engine.LanguageEngine
import com.metrowin.keyboard.engine.PinyinCandidate
import com.metrowin.keyboard.engine.SuggestOptions
import com.metrowin.keyboard.engine.SwipePoint
import com.metrowin.keyboard.engine.Text
import com.metrowin.keyboard.engine.WordSuggestions
import com.metrowin.keyboard.feedback.Feedback
import com.metrowin.keyboard.gif.GifItem
import com.metrowin.keyboard.gif.GifRepository
import com.metrowin.keyboard.integration.LauncherLink
import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import com.metrowin.keyboard.layout.KeyboardBuilder
import com.metrowin.keyboard.layout.LanguageLayout
import com.metrowin.keyboard.layout.Page
import com.metrowin.keyboard.service.AdaptiveColor
import com.metrowin.keyboard.settings.HelperActivity
import com.metrowin.keyboard.settings.SettingsActivity
import com.metrowin.keyboard.theme.KeyboardColors
import com.metrowin.keyboard.theme.KeyboardTheme
import com.metrowin.keyboard.translate.Translator
import com.metrowin.keyboard.tutorial.Tutorial
import com.metrowin.keyboard.ui.ClipboardPanel
import com.metrowin.keyboard.ui.TranslatePanel
import com.metrowin.keyboard.ui.TutorialPanel
import com.metrowin.keyboard.ui.EmojiPanel
import com.metrowin.keyboard.ui.EmojiResults
import com.metrowin.keyboard.ui.GifPanel
import com.metrowin.keyboard.ui.Icon
import com.metrowin.keyboard.ui.ImageLoader
import com.metrowin.keyboard.ui.KeyboardListener
import com.metrowin.keyboard.ui.KeyboardView
import com.metrowin.keyboard.ui.Slot
import com.metrowin.keyboard.ui.SuggestionBar
import com.metrowin.keyboard.ui.Tool
import com.metrowin.keyboard.voice.VoiceInput
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The MetroWin keyboard. This class owns the text logic: what is being typed (the composing word), when a word ends, what is
 * corrected, learned and suggested, and it connects the keys, the bar and the panels to the app being typed in.
 */
open class MetroWinIME : InputMethodService(), KeyboardListener, SuggestionBar.Listener {
    private enum class Field { TEXT, PASSWORD, EMAIL, URI, NUMBER, PHONE, NONE }
    private enum class Panel { NONE, EMOJI, CLIPBOARD, GIF, TRANSLATE, TUTORIAL, CANDIDATES }

    private class Correction(val typed: String, val corrected: String, val delimiter: String)
    private class SwipeResult(val word: String, val inserted: String)

    private lateinit var app: KeyboardApp
    private lateinit var feedback: Feedback
    private lateinit var images: ImageLoader
    private lateinit var gifRepo: GifRepository
    private lateinit var voice: VoiceInput
    private lateinit var translator: Translator
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()

    /** Where suggestions and swipes are computed. Tests replace it with one that runs at once. */
    internal var worker: java.util.concurrent.Executor = pool
    private val prefs get() = app.prefs

    // views
    private var root: LinearLayout? = null
    private var bar: SuggestionBar? = null
    private var strip: FrameLayout? = null
    private var frame: FrameLayout? = null
    private var keyboard: KeyboardView? = null
    private var emoji: EmojiPanel? = null
    private var emojiResults: EmojiResults? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var gifPanel: GifPanel? = null
    private var translatePanel: TranslatePanel? = null
    private var tutorialPanel: TutorialPanel? = null
    private var clipBadge = false
    private var lastEdit = 0L
    private var navInset = 0
    private var colors: KeyboardColors? = null

    // state
    private var engine: LanguageEngine? = null
    private var page = Page.LETTERS
    private var panel = Panel.NONE
    private var shift = 0
    private var lastShiftTap = 0L
    private var field = Field.NONE
    private var predictionOn = false
    private var learningOn = false
    private var inLauncher = false
    private var gifSearching = false
    private val gifQuery = StringBuilder()
    private var emojiSearching = false
    private val emojiQuery = StringBuilder()
    private val composing = StringBuilder()

    // Where each letter of the word being typed was tapped, for the correction (see Corrector). Kept as a log of the last letters typed
    // that is checked against the word before use, so a word that was not typed key by key (a restored one) simply has no touch data.
    private class Tapped(val ch: Char, val touch: Map<Char, Float>?)
    private val tapped = ArrayList<Tapped>()
    private var pendingTouch: Map<Char, Float>? = null
    private var prevWord: String? = null
    private var prevWord2: String? = null
    private var latest: WordSuggestions? = null
    private var suggestSeq = 0
    private var lastCorrection: Correction? = null
    private var autoSpace = false
    private var lastSwipe: SwipeResult? = null
    private var lastSpaceTime = 0L
    private var bgBitmap: Bitmap? = null
    private var bgStamp = -1L
    private var refreshPending = false

    // Pinyin: what the bar offers for the letters being typed, and what was chosen so far out of them.
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private var pinyinFor = ""
    private val pinyinPicks = ArrayList<PinyinCandidate>()
    /** The last Chinese word written: what comes after it is offered while nothing is typed. */
    private var pinyinLast: String? = null
    private var associations: List<String> = emptyList()
    private var candidatePanel: com.metrowin.keyboard.ui.CandidatePanel? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == K.EMOJI_RECENT) return@OnSharedPreferenceChangeListener
        if (key == K.CUSTOM_BLOCKED) app.reloadEngines()
        scheduleRefresh()
    }
    private val adaptiveListener: () -> Unit = { if (prefs.adaptiveColor) scheduleRefresh() }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(copied = true) }

    private fun screenHeight(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    internal val keyboardView get() = keyboard
    internal fun candidatePanelForTest() = candidatePanel
    internal val suggestionBar get() = bar
    internal val shiftState get() = shift

    // --- lifecycle --------------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        app = KeyboardApp.of(this)
        feedback = Feedback(this, prefs)
        images = ImageLoader(this)
        gifRepo = GifRepository(this, prefs)
        voice = VoiceInput(this, prefs, voiceCallbacks)
        translator = Translator(prefs)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        AdaptiveColor.add(adaptiveListener)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        AdaptiveColor.remove(adaptiveListener)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        voice.stop()
        app.saveAll()
        feedback.release(); translator.shutdown(); gifRepo.shutdown(); images.shutdown(); pool.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val ctx = this
        val kb = KeyboardView(ctx).also { it.listener = this }
        val bar = SuggestionBar(ctx).also { it.listener = this }
        val emoji = EmojiPanel(ctx).also { it.listener = emojiListener }
        emojiResults = EmojiResults(ctx).also { it.onPick = { e -> emojiListener.onEmoji(e) } }
        val clip = ClipboardPanel(ctx, app.clipboard, images).also { it.listener = clipListener2 }
        val gif = GifPanel(ctx, gifRepo, images).also { it.listener = gifListener }
        val translate = TranslatePanel(ctx, translator, prefs).also { it.listener = translateListener }
        val tutorial = TutorialPanel(ctx).also { it.onBack = { showPanel(Panel.NONE) }; it.onSeen = { prefs.tutorialSeen = Tutorial.VERSION } }
        val candidates = com.metrowin.keyboard.ui.CandidatePanel(ctx).also { cp ->
            cp.onClose = { showPanel(Panel.NONE) }
            cp.onPick = { i -> currentInputConnection?.let { ic -> pinyinCandidates.getOrNull(i)?.let { pickPinyin(ic, it) } } }
        }
        val frame = FrameLayout(ctx)
        frame.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (p in listOf(emoji, clip, gif, translate, tutorial, candidates)) {
            p.visibility = View.GONE
            frame.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val strip = FrameLayout(ctx).apply { visibility = View.GONE }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 100).toInt()))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Android 15 draws the keyboard window edge to edge, behind the navigation bar (or behind the launcher's own bar drawn there).
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Also the caption bar: with the three buttons in landscape the system reports the strip where it draws its keyboard buttons
            // (the hide arrow and the language globe) there, not as a navigation bar, and it would cover the bottom row of keys.
            navInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val loc = IntArray(2); root.getLocationOnScreen(loc); AdaptiveColor.keyboardTop = loc[1]
            // Pad only when the window really reaches the bottom edge of the screen, so a phone that already puts it above the bar is not padded twice.
            val reachesBottom = loc[1] + root.height >= screenHeight() - 2
            val wanted = (if (reachesBottom) navInset else 0) + (resources.displayMetrics.density * prefs.bottomPadding).toInt()
            if (root.paddingBottom != wanted) root.setPadding(0, 0, 0, wanted)
        }
        this.root = root; this.bar = bar; this.strip = strip; this.frame = frame; this.keyboard = kb
        this.emoji = emoji; this.clipboardPanel = clip; this.gifPanel = gif; this.translatePanel = translate; this.tutorialPanel = tutorial; this.candidatePanel = candidates
        applyAll()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing.clear(); latest = null; lastCorrection = null; autoSpace = false; lastSwipe = null
        pinyinPicks.clear(); pinyinCandidates = emptyList(); pinyinLast = null
        classify(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        classify(info)
        AdaptiveColor.keyboardShown = true
        gifSearching = false; gifQuery.setLength(0)
        showPanel(Panel.NONE)
        page = if (field == Field.NUMBER || field == Field.PHONE) Page.NUMBERS else Page.LETTERS
        applyAll()
        bar?.closeTools()
        refreshContext()
        updateShiftFromCursor()
        updateSuggestions()
        captureClip(copied = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        AdaptiveColor.keyboardShown = false
        finishComposingQuietly()
        voice.stop()
        emoji?.dismissPopup()
        app.saveAll()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        finishComposingQuietly()
    }

    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val tag = subtype?.languageTag?.takeIf { it.isNotBlank() } ?: return
        val layout = app.layouts.forLanguage(tag)
        if (layout.id != prefs.currentLanguage && app.layouts.get(tag.substringBefore('-').lowercase()) != null) setLanguage(layout)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Updates that were sent before our own last edit arrive late: they must not end the word being typed.
        val ours = SystemClock.uptimeMillis() - lastEdit < 250
        if (composing.isNotEmpty() && !ours && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // The cursor left the word being typed: it is a finished word now.
            composing.clear(); currentInputConnection?.finishComposingText(); latest = null
            pinyinPicks.clear(); pinyinCandidates = emptyList()
        }
        if (composing.isEmpty()) {
            if (!ours) pinyinLast = null
            main.removeCallbacks(contextRefresh)
            main.postDelayed(contextRefresh, 60)
        }
    }

    private val contextRefresh = Runnable { refreshContext(); updateSuggestions() }

    private fun finishComposingQuietly() {
        if (composing.isNotEmpty()) { composing.clear(); currentInputConnection?.finishComposingText() }
        pinyinPicks.clear(); pinyinCandidates = emptyList()
    }

    // --- field and settings -----------------------------------------------------------------------------------------

    private fun classify(info: EditorInfo?) {
        val t = info?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        field = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) Field.PASSWORD else Field.NUMBER
            InputType.TYPE_CLASS_PHONE -> Field.PHONE
            InputType.TYPE_CLASS_DATETIME -> Field.NUMBER
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> Field.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Field.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> Field.URI
                else -> Field.TEXT
            }
            else -> Field.NONE
        }
        inLauncher = LauncherLink.isLauncher(info?.packageName)
        val noSuggestions = t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        // In the launcher's search box a word is not a sentence: no completion, correction or learning there.
        predictionOn = prefs.suggestions && field == Field.TEXT && !noSuggestions && !inLauncher
        val noLearning = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        learningOn = prefs.learn && field == Field.TEXT && !noLearning && !inLauncher
    }

    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        main.postDelayed({ refreshPending = false; if (root != null) applyAll() }, 40)
    }

    private fun activeLayouts(): List<LanguageLayout> =
        prefs.languages.mapNotNull { app.layouts.get(it) }.ifEmpty { listOf(app.layouts.forLanguage(Locale.getDefault().language)) }

    private fun currentLayout(): LanguageLayout {
        val active = activeLayouts()
        val saved = prefs.currentLanguage
        return active.firstOrNull { it.id == saved } ?: app.layouts.get(saved ?: "") ?: active.first()
    }

    private fun setLanguage(layout: LanguageLayout) {
        finishWord(autocorrect = false)
        prefs.currentLanguage = layout.id
        // Pinyin is typed in small letters: a capital would be a Latin letter, not the start of a syllable.
        if (layout.pinyin && shift == 1) setShift(0)
        engine = null
        loadEngine(layout)
        applyKeyboardPage()
        updateSuggestions()
    }

    private fun loadEngine(layout: LanguageLayout) {
        app.cachedEngine(layout.id)?.let { engine = it; return }
        worker.execute {
            val e = app.engine(layout.id)
            main.post { if (currentLayout().id == layout.id) { engine = e; updateSuggestions() } }
        }
    }

    private fun options() = SuggestOptions(
        autocorrect = prefs.autocorrect, nextWord = prefs.nextWord, phrases = prefs.phrases,
        blockOffensive = prefs.blockOffensive, usePersonal = learningOn || prefs.learn, smart = prefs.smartCorrect,
    )

    private fun backgroundBitmap(): Bitmap? {
        if (!prefs.imageBackground) return null
        val f = File(filesDir, "background.jpg")
        if (!f.exists()) return null
        if (f.lastModified() == bgStamp && bgBitmap != null) return bgBitmap
        bgStamp = f.lastModified()
        bgBitmap = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        return bgBitmap
    }

    /** Applies every setting to the views. Cheap enough to run on any change. */
    private fun applyAll() {
        val kb = keyboard ?: return
        val picture = if (prefs.usePictureAccent && prefs.imageBackground) prefs.pictureAccent else 0
        val c = KeyboardTheme.resolve(this, prefs, KeyboardTheme.isSystemDark(this), if (prefs.adaptiveColor) AdaptiveColor.color else null, picture)
        colors = c
        kb.setColors(c); bar?.setColors(c); candidatePanel?.setColors(c); emoji?.setColors(c); emojiResults?.setColors(c); clipboardPanel?.setColors(c); gifPanel?.setColors(c); translatePanel?.setColors(c); tutorialPanel?.setColors(c)
        root?.setBackgroundColor(c.background)
        kb.setBackgroundImage(backgroundBitmap(), prefs.imageDim)
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        kb.setMetrics(if (land) prefs.keyHeightLand else prefs.keyHeight, prefs.keyGap, prefs.keyboardWidth, prefs.keyboardAlign)
        kb.setBias(prefs.calibBias(land)); kb.setTouchSigma(prefs.calibSigma)
        kb.setBehavior(prefs.longPressSymbols, prefs.longPressMs, prefs.swipe && field == Field.TEXT && !currentLayout().pinyin, prefs.spaceCursor, prefs.keyHints, prefs.keyPreview)
        bar?.setButtons(prefs.barButtons)
        bar?.setQuickTool(Tool.from(prefs.quickTool)); bar?.setBadge(clipBadge && Tool.from(prefs.quickTool) == Tool.CLIPBOARD)
        clipboardPanel?.setImagesEnabled(prefs.clipboardImages)
        feedback.prepare()
        val layout = currentLayout()
        if (engine?.layout?.id != layout.id) { engine = null; loadEngine(layout) }
        applyKeyboardPage()
        // Options that were switched off while the bar showed something.
        if (!prefs.suggestions) bar?.setSlots(listOf(null, null, null))
    }

    private fun applyKeyboardPage() {
        val kb = keyboard ?: return
        val layout = currentLayout()
        val several = activeLayouts().size > 1
        // The globe can be hidden to give the space bar its room; then holding the space bar changes language.
        // shouldOfferSwitchingToNextInputMethod is from Android 9; on 8.x only our own languages can be switched.
        val multi = (several || (Build.VERSION.SDK_INT >= 28 && shouldOfferSwitchingToNextInputMethod())) && prefs.languageKey
        val p = when (page) {
            Page.LETTERS -> KeyboardBuilder.letters(layout, prefs.numberRow, prefs.keyHints, multi)
            Page.SYMBOLS_1, Page.SYMBOLS_2 -> KeyboardBuilder.symbols(page, prefs.numberRow, layout)
            Page.NUMBERS -> KeyboardBuilder.numbers(field == Field.PHONE)
        }
        kb.setPage(p)
        kb.setLanguageName(layout.name)
        kb.setSpaceLanguageSwitch(several && !prefs.languageKey)
        kb.setShift(shift)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        kb.setEnterAction(
            if (noAction) Icon.ENTER else when (action) {
                EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
                EditorInfo.IME_ACTION_SEND -> Icon.SEND
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icon.ARROW_RIGHT
                EditorInfo.IME_ACTION_DONE -> Icon.CHECK
                else -> Icon.ENTER
            }, null,
        )
    }

    // --- text context -----------------------------------------------------------------------------------------------

    /** Reads the two words before the cursor (not the one being typed), for the next-word prediction. */
    private fun refreshContext() {
        val ic = currentInputConnection ?: return
        if (field != Field.TEXT) { prevWord = null; prevWord2 = null; return }
        val before = ic.getTextBeforeCursor(80, 0) ?: return
        val join = currentLayout().joinApostrophe
        val text = if (composing.isNotEmpty() && before.length >= composing.length) before.subSequence(0, before.length - composing.length) else before
        // A word right against the cursor and not being typed is not a context (the user tapped in the middle of text).
        val words = if (composing.isEmpty() && text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') emptyList() else Text.previousWords(text, join, 2)
        prevWord = words.getOrNull(0); prevWord2 = words.getOrNull(1)
    }

    private fun updateShiftFromCursor() {
        if (!prefs.autoCaps || field != Field.TEXT || inLauncher || page != Page.LETTERS || shift == 2 || currentLayout().pinyin) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        setShift(if (caps != 0) 1 else 0)
    }

    private fun setShift(state: Int) { shift = state; keyboard?.setShift(state) }

    // --- keys -------------------------------------------------------------------------------------------------------

    override fun onPress(key: Key) {
        endVoiceText()
        if (!prefs.barButtons && key.type != KeyType.EMOJI) bar?.closeTools()
        feedback.press(key.type)
    }

    override fun onTouch(likelihood: Map<Char, Float>) { pendingTouch = likelihood }

    override fun onText(key: Key, text: String) {
        lastSwipe = null
        typeText(text)
    }

    override fun onPopupText(text: String) {
        lastSwipe = null
        typeText(text)
    }

    override fun onShiftLongPress() { setShift(if (shift == 2) 0 else 2) }

    override fun onFunction(key: Key, repeat: Boolean) {
        when (key.type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                setShift(when { shift == 2 -> 0; shift == 1 && now - lastShiftTap < 350 -> 2; shift == 1 -> 0; else -> 1 })
                lastShiftTap = now
            }
            KeyType.BACKSPACE -> { lastSwipe = null; backspace(repeat) }
            KeyType.ENTER -> { lastSwipe = null; enter() }
            KeyType.SPACE -> { lastSwipe = null; space() }
            KeyType.SYMBOLS -> { finishWord(false); page = if (page == Page.LETTERS) Page.SYMBOLS_1 else Page.LETTERS; applyKeyboardPage() }
            KeyType.SYMBOLS_PAGE -> { page = if (page == Page.SYMBOLS_1) Page.SYMBOLS_2 else Page.SYMBOLS_1; applyKeyboardPage() }
            KeyType.EMOJI -> { finishWord(false); showPanel(Panel.EMOJI) }
            KeyType.LANGUAGE -> switchLanguage()
            KeyType.CHAR -> typeText(key.label)
        }
    }

    private fun switchLanguage() {
        val all = activeLayouts()
        if (all.size <= 1) { if (Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false); return }
        val i = all.indexOfFirst { it.id == currentLayout().id }
        setLanguage(all[(i + 1) % all.size])
    }

    override fun onCursorMove(steps: Int) {
        finishWord(false)
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    // --- typing -----------------------------------------------------------------------------------------------------

    private fun typeText(text: String) {
        try { typeTextNow(text) } finally { pendingTouch = null }
    }

    private fun typeTextNow(text: String) {
        lastEdit = SystemClock.uptimeMillis()
        if (gifSearching) { typeIntoGifSearch(text); return }
        if (emojiSearching) { typeIntoEmojiSearch(text); return }
        val ic = currentInputConnection ?: return
        lastCorrection = null
        val layout = currentLayout()
        if (page == Page.LETTERS && pinyinOn()) { typePinyin(ic, text); return }
        val isWord = predictionOn && page == Page.LETTERS &&
            (text.all { Text.isWordChar(it) } || (text.length == 1 && Text.isApostrophe(text[0]) && layout.joinApostrophe && composing.isNotEmpty()))
        if (isWord) {
            for ((i, ch) in text.withIndex()) tapped += Tapped(ch.lowercaseChar(), if (i == 0) pendingTouch else null)
            if (tapped.size > 64) tapped.subList(0, 32).clear()
            composing.append(text)
            ic.setComposingText(composing, 1)
            autoSpace = false
            if (shift == 1) setShift(0)
            updateSuggestions()
        } else {
            val apostropheBreak = text.length == 1 && Text.isApostrophe(text[0])
            finishWord(autocorrect = !apostropheBreak, delimiter = text)
            if (autoSpace && text.length == 1 && text[0] in ".,!?;:)]}") ic.deleteSurroundingText(1, 0)
            autoSpace = false
            ic.commitText(text, 1)
            if (shift == 1) setShift(0)
            if (text.length == 1 && text[0] in ".!?") updateShiftSoon()
            refreshContext(); updateSuggestions()
        }
    }

    private fun updateShiftSoon() = main.postDelayed({ updateShiftFromCursor() }, 30)

    private fun space() {
        lastEdit = SystemClock.uptimeMillis()
        if (gifSearching) { typeIntoGifSearch(" "); return }
        if (emojiSearching) { typeIntoEmojiSearch(" "); return }
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (pinyinOn()) {
            // Space takes the first word the bar offers; with nothing being typed it is a space.
            if (composing.isNotEmpty()) commitPinyinBest(ic) else { ic.commitText(" ", 1); pinyinLast = null }
            autoSpace = false
            refreshContext(); updateSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            finishWord(autocorrect = true, delimiter = " ")
            ic.commitText(" ", 1)
            lastSpaceTime = now
        } else {
            val before = ic.getTextBeforeCursor(3, 0)
            if (prefs.doubleSpace && field == Field.TEXT && now - lastSpaceTime < 600 && before != null && before.length >= 2 &&
                before.last() == ' ' && before[before.length - 2].let { it.isLetterOrDigit() || it == ')' || it == '"' }
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0
            } else {
                ic.commitText(" ", 1)
                lastSpaceTime = now
            }
        }
        autoSpace = false
        refreshContext(); updateShiftFromCursor(); updateSuggestions()
    }

    private fun enter() {
        lastEdit = SystemClock.uptimeMillis()
        if (gifSearching) { gifPanel?.run(gifQuery.toString().ifBlank { null }); exitGifSearch(); return }
        if (emojiSearching) { showPanel(Panel.EMOJI); return }
        val ic = currentInputConnection ?: return
        // Enter on Pinyin being typed keeps the letters as they are (a Latin word in a Chinese sentence) and goes no further.
        if (pinyinOn() && composing.isNotEmpty()) { commitPinyinRaw(ic); refreshContext(); updateSuggestions(); return }
        finishWord(autocorrect = true, delimiter = "\n")
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        autoSpace = false
        refreshContext(); updateShiftFromCursor(); updateSuggestions()
    }

    private fun backspace(repeat: Boolean) {
        lastEdit = SystemClock.uptimeMillis()
        if (gifSearching) { if (gifQuery.isNotEmpty()) gifQuery.deleteCharAt(gifQuery.length - 1); showGifQuery(); return }
        if (emojiSearching) {
            if (emojiQuery.isEmpty()) showPanel(Panel.EMOJI) else { emojiQuery.deleteCharAt(emojiQuery.length - 1); showEmojiQuery() }
            return
        }
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            tapped.removeLastOrNull()
            if (composing.isEmpty()) { ic.commitText("", 1); pinyinPicks.clear() } else if (pinyinOn()) showComposing(ic) else ic.setComposingText(composing, 1)
            updateSuggestions()
            return
        }
        val lc = lastCorrection
        if (!repeat && lc != null) {
            // The first backspace after an automatic correction gives back what the user typed.
            val expected = lc.corrected + lc.delimiter
            val before = ic.getTextBeforeCursor(expected.length, 0)?.toString()
            if (before == expected) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expected.length, 0)
                composing.append(lc.typed)
                ic.setComposingText(composing, 1)
                ic.endBatchEdit()
                lastCorrection = null
                if (learningOn) engine?.user?.let { it.learn(null, lc.typed.lowercase()); it.reject(lc.typed.lowercase(), lc.corrected.lowercase()) }
                updateSuggestions()
                return
            }
        }
        lastCorrection = null
        autoSpace = false
        pinyinLast = null
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        main.postDelayed({ refreshContext(); updateSuggestions() }, 30)
        updateShiftSoon()
    }

    // --- words ------------------------------------------------------------------------------------------------------

    /** Ends the word being typed: applies the automatic correction if wanted, learns it, and commits it. */
    private fun finishWord(autocorrect: Boolean, delimiter: String = "") {
        if (composing.isEmpty()) return
        val ic = currentInputConnection
        if (pinyinOn()) { commitPinyinRaw(ic); return }
        val typed = composing.toString()
        var final = typed
        val s = latest
        if (autocorrect && prefs.autocorrect && predictionOn && s != null && s.typed == typed && s.autocorrect != null) final = s.autocorrect
        composing.setLength(0)
        ic?.commitText(final, 1)
        lastCorrection = if (final != typed) Correction(typed, final, delimiter) else null
        learn(final)
        latest = null
    }

    private fun learn(word: String) {
        val w = word.lowercase()
        if (learningOn && w.length >= 2 && w.none { it.isDigit() } && engine?.blocklist?.contains(w) != true) engine?.user?.learn(prevWord2, prevWord, w)
        prevWord2 = prevWord; prevWord = w
    }

    private fun capitalise(w: String) = if (shift != 0) w.replaceFirstChar { it.titlecase(currentLayout().locale) } else w

    private fun updateSuggestions() {
        val b = bar ?: return
        val eng = engine
        if (gifSearching || emojiSearching) return
        // A panel (GIFs, clipboard, translation...) has the place of the keys: the bar must not go on offering the words of what was
        // just typed above it, as if the keys were still there.
        if (panel != Panel.NONE && panel != Panel.CANDIDATES) { latest = null; suggestSeq++; b.setSlots(listOf(null, null, null)); return }
        if (pinyinOn()) { updatePinyin(b); return }
        // ":dog": the emoji for the word after a colon take the bar (the colon itself is already in the text, the word is being composed).
        val wanted = emojiWanted()
        if (wanted != null) {
            suggestSeq++
            val found = EmojiData.drawable(emojiIndex().search(wanted, 40))
            if (found.isNotEmpty()) { latest = null; b.setSlots(found.map { Slot(it, emoji = true) }); return }
        }
        if (eng == null || !predictionOn) { b.setSlots(listOf(null, null, null)); return }
        val typed = composing.toString()
        val seq = ++suggestSeq
        val opts = options()
        val p1 = prevWord; val p2 = prevWord2
        val touch = touchOf(typed)
        worker.execute {
            if (seq != suggestSeq) return@execute
            if (typed.isNotEmpty()) {
                val r = eng.suggester.forTyped(typed, p1, opts, touch)
                main.post { if (seq == suggestSeq) { latest = r; showWordSlots(r) } }
            } else {
                val next = eng.suggester.nextWords(p1, p2, 3, opts)
                val phrase = eng.suggester.phrase(p1, opts)
                main.post { if (seq == suggestSeq) { latest = null; showNextSlots(next, phrase) } }
            }
        }
    }

    /** The taps of the letters of [typed], if the word was typed key by key; null otherwise. */
    private fun touchOf(typed: String): List<Map<Char, Float>?>? {
        val n = typed.length
        if (n == 0 || tapped.size < n) return null
        val tail = tapped.subList(tapped.size - n, tapped.size)
        if (tail.indices.any { tail[it].ch != typed[it].lowercaseChar() }) return null
        return tail.map { it.touch }
    }

    private fun showWordSlots(r: WordSuggestions) {
        val others = r.candidates.filter { it != r.autocorrect && !it.equals(r.typed, true) }
        val slots: List<Slot?> = if (r.autocorrect != null) {
            listOf(Slot(r.typed, literal = true), Slot(r.autocorrect, best = true), others.getOrNull(0)?.let { Slot(it) })
        } else {
            listOf(others.getOrNull(0)?.let { Slot(it) }, Slot(r.typed, literal = !r.typedIsKnown, best = r.typedIsKnown), others.getOrNull(1)?.let { Slot(it) })
        }
        bar?.setSlots(slots)
    }

    private fun showNextSlots(next: List<String>, phrase: String?) {
        val n = next.map { capitalise(it) }
        bar?.setSlots(listOf(n.getOrNull(1)?.let { Slot(it) }, n.getOrNull(0)?.let { Slot(it, best = true) }, phrase?.let { Slot(capitalise(it), phrase = true) } ?: n.getOrNull(2)?.let { Slot(it) }))
    }

    override fun onSlot(slot: Slot) {
        endVoiceText()
        val ic = currentInputConnection ?: return
        if (slot.emoji) {
            // The colon and the word typed after it become the emoji.
            ic.beginBatchEdit()
            composing.setLength(0)
            ic.commitText("", 1)
            ic.deleteSurroundingText(1, 0)
            ic.commitText(slot.text, 1)
            ic.endBatchEdit()
            rememberEmoji(slot.text)
            latest = null; lastCorrection = null; autoSpace = false; lastSwipe = null
            refreshContext(); updateShiftFromCursor(); updateSuggestions()
            return
        }
        if (pinyinOn() && composing.isNotEmpty()) {
            pinyinCandidates.firstOrNull { it.text == slot.text }?.let { pickPinyin(ic, it) }
            return
        }
        if (pinyinOn() && slot.text in associations) { pickAssociation(ic, slot.text); return }
        val swipe = lastSwipe
        if (swipe != null && !slot.literal) {
            // Replace the word just drawn with another reading of the same swipe.
            val back = swipe.inserted.length
            ic.deleteSurroundingText(back, 0)
            val text = if (swipe.inserted.first().isUpperCase()) slot.text.replaceFirstChar { it.titlecase(currentLayout().locale) } else slot.text
            ic.commitText("$text ", 1)
            lastSwipe = SwipeResult(slot.text, "$text ")
            learn(slot.text)
            return
        }
        val hadComposing = composing.isNotEmpty()
        if (hadComposing) { composing.setLength(0) }
        // The word takes the capitals of what was typed; a phrase is added as it is.
        val typed = latest?.typed
        val text = if (typed != null && hadComposing && !slot.phrase) Text.matchCase(typed, slot.text, currentLayout().locale) else slot.text
        ic.commitText("$text ", 1)
        autoSpace = true
        lastCorrection = null
        text.split(' ').filter { it.isNotEmpty() }.forEach { learn(it) }
        latest = null
        refreshContext(); updateShiftFromCursor(); updateSuggestions()
    }

    // --- Pinyin -----------------------------------------------------------------------------------------------------

    /** The layout is a Pinyin one and the field takes text: letters are held as Pinyin and the bar offers the Chinese words. */
    private fun pinyinOn() = field == Field.TEXT && currentLayout().pinyin

    private fun shuangpin() = currentLayout().shuangpin

    /** The keys typed as Pinyin: the same letters, or for Shuangpin the syllables they stand for. */
    private fun decoded(keys: String): com.metrowin.keyboard.engine.Shuangpin.Decoded? {
        val eng = engine?.pinyin ?: return null
        return if (shuangpin()) com.metrowin.keyboard.engine.Shuangpin.decode(keys, eng.syllables) else null
    }

    /** What the field shows while Pinyin is typed: the letters, or for Shuangpin the Pinyin of the keys ("nihk" shows "ni'hao"). */
    private fun showComposing(ic: android.view.inputmethod.InputConnection) {
        val shown = decoded(composing.toString())?.pinyin?.ifEmpty { null } ?: composing
        ic.setComposingText(shown, 1)
    }

    /** The words for the keys [keys], with [PinyinCandidate.consumed] counted in keys. */
    private fun pinyinCandidatesFor(keys: String, limit: Int = 100): List<PinyinCandidate> {
        val eng = engine?.pinyin ?: return emptyList()
        eng.fuzzy = prefs.pinyinFuzzy
        val d = decoded(keys) ?: return eng.candidates(keys, limit)
        return eng.candidates(d.pinyin, limit).map { PinyinCandidate(it.text, it.pinyin, d.keysFor(it.consumed).coerceAtLeast(1)) }
    }

    private fun typePinyin(ic: android.view.inputmethod.InputConnection, text: String) {
        val letter = text.length == 1 && (text[0] in 'a'..'z' || (text[0] == ';' && shuangpin()))
        // The apostrophe splits two syllables where the letters alone could be read otherwise ("xi'an").
        val separator = !shuangpin() && text == "'" && composing.isNotEmpty() && composing.last() != '\''
        if (letter || separator) {
            if (composing.length >= PINYIN_MAX) return
            composing.append(text)
            showComposing(ic)
            autoSpace = false
            updateSuggestions()
            return
        }
        // Anything else ends the Pinyin: a sign or a digit takes the first word offered, a capital letter leaves the letters as typed.
        if (composing.isNotEmpty()) { if (text.any { it.isLetter() }) commitPinyinRaw(ic) else commitPinyinBest(ic) }
        ic.commitText(text, 1)
        pinyinLast = null
        if (shift == 1) setShift(0)
        refreshContext(); updateSuggestions()
    }

    private fun updatePinyin(b: SuggestionBar) {
        val eng = engine?.pinyin
        val typed = composing.toString()
        val seq = ++suggestSeq
        latest = null
        if (eng == null) { pinyinCandidates = emptyList(); pinyinFor = ""; b.setSlots(listOf(null, null, null)); return }
        if (typed.isEmpty()) {
            pinyinCandidates = emptyList(); pinyinFor = ""
            // After a choice: what usually comes next.
            val before = pinyinLast
            if (before == null || !prefs.nextWord) { associations = emptyList(); b.setSlots(listOf(null, null, null)); return }
            worker.execute {
                if (seq != suggestSeq) return@execute
                val next = eng.associations(before)
                main.post { if (seq == suggestSeq) { associations = next; b.setSlots(next.map { Slot(it) }, row = true) } }
            }
            return
        }
        worker.execute {
            if (seq != suggestSeq) return@execute
            val found = pinyinCandidatesFor(typed)
            main.post {
                if (seq != suggestSeq) return@post
                pinyinCandidates = found; pinyinFor = typed; associations = emptyList()
                // The first is what space takes; the arrow at the end shows all of them.
                b.setSlots(found.mapIndexed { i, c -> Slot(c.text, best = i == 0) }, row = true, expandable = found.size > 3)
                if (panel == Panel.CANDIDATES) candidatePanel?.show(found.map { it.text })
            }
        }
    }

    /** Leaves the letters in the text as they were typed. */
    private fun commitPinyinRaw(ic: android.view.inputmethod.InputConnection?) {
        val raw = composing.toString()
        composing.setLength(0); pinyinPicks.clear(); pinyinCandidates = emptyList(); pinyinFor = ""; pinyinLast = null
        ic?.commitText(raw, 1)
        if (panel == Panel.CANDIDATES) showPanel(Panel.NONE)
    }

    /** Takes the first word offered, and again for the keys it leaves, until all of them are words. */
    private fun commitPinyinBest(ic: android.view.inputmethod.InputConnection) {
        val out = StringBuilder()
        var rest = composing.toString()
        var turns = 0
        while (rest.isNotEmpty() && turns++ < PINYIN_MAX) {
            val c = (if (rest == pinyinFor) pinyinCandidates else pinyinCandidatesFor(rest, 1)).firstOrNull() ?: break
            out.append(c.text)
            pinyinPicks += c
            rest = rest.substring(minOf(c.consumed, rest.length))
        }
        // Keys no word is read as stay as they were typed.
        out.append(rest)
        composing.setLength(0)
        ic.commitText(out, 1)
        pinyinDone()
        if (panel == Panel.CANDIDATES) showPanel(Panel.NONE)
    }

    private fun pickPinyin(ic: android.view.inputmethod.InputConnection, c: PinyinCandidate) {
        lastEdit = SystemClock.uptimeMillis()
        val rest = composing.substring(minOf(c.consumed, composing.length))
        composing.setLength(0)
        ic.beginBatchEdit()
        ic.commitText(c.text, 1)
        pinyinPicks += c
        if (learningOn) engine?.pinyin?.user?.learn(c.pinyin, c.text)
        if (rest.isNotEmpty()) { composing.append(rest); showComposing(ic) } else pinyinDone()
        ic.endBatchEdit()
        if (composing.isEmpty() && panel == Panel.CANDIDATES) showPanel(Panel.NONE)
        refreshContext(); updateSuggestions()
    }

    /** A word offered after a choice was tapped: it is written as it is, and what comes after it is offered next. */
    private fun pickAssociation(ic: android.view.inputmethod.InputConnection, text: String) {
        lastEdit = SystemClock.uptimeMillis()
        ic.commitText(text, 1)
        val before = pinyinLast
        if (learningOn && before != null) engine?.pinyin?.user?.learnFollow(before, text)
        pinyinLast = text
        refreshContext(); updateSuggestions()
    }

    /**
     * All the keys became words. What was made of several choices is a phrase of the user's: it is learned as a word of its own;
     * and each word is learned as following the one before, for the words offered after a choice.
     */
    private fun pinyinDone() {
        val user = engine?.pinyin?.user
        if (learningOn && user != null) {
            var before = pinyinLast
            for (p in pinyinPicks) { if (before != null) user.learnFollow(before, p.text); before = p.text }
            if (pinyinPicks.size in 2..4) {
                val word = pinyinPicks.joinToString("") { it.text }
                if (word.length <= 8) user.learn(pinyinPicks.joinToString("'") { it.pinyin }, word)
            }
        }
        pinyinLast = pinyinPicks.lastOrNull()?.text ?: pinyinLast
        pinyinPicks.clear(); pinyinCandidates = emptyList(); pinyinFor = ""
    }

    override fun onExpand() {
        if (!pinyinOn() || pinyinCandidates.isEmpty()) return
        showPanel(Panel.CANDIDATES)
        candidatePanel?.show(pinyinCandidates.map { it.text })
    }

    override fun onEmojiLongPress() { bar?.toggleTools() }

    // --- swipe typing -----------------------------------------------------------------------------------------------

    override fun onSwipe(path: List<SwipePoint>) {
        val eng = engine ?: return
        val kb = keyboard ?: return
        if (field != Field.TEXT || page != Page.LETTERS || currentLayout().pinyin) return
        val centers = kb.letterCenters()
        val keyW = kb.keyWidth()
        val shiftOn = shift != 0
        val pts = path.toList()
        worker.execute {
            val words = eng.swipe.decode(pts, centers, keyW, 4)
            main.post { if (words.isNotEmpty()) commitSwipe(words, shiftOn) }
        }
    }

    private fun commitSwipe(words: List<String>, capital: Boolean) {
        val ic = currentInputConnection ?: return
        finishWord(autocorrect = true)
        val before = ic.getTextBeforeCursor(1, 0)
        // A word drawn straight after another word or a letter starts after a space.
        val lead = if (!before.isNullOrEmpty() && before.last().let { it.isLetterOrDigit() || it in ".,!?;:" }) " " else ""
        val best = if (capital) words[0].replaceFirstChar { it.titlecase(currentLayout().locale) } else words[0]
        val inserted = "$best "
        ic.commitText(lead + inserted, 1)
        if (shift == 1) setShift(0)
        lastSwipe = SwipeResult(words[0], inserted)
        lastCorrection = null
        autoSpace = false
        learn(words[0])
        val alt = words.drop(1)
        bar?.setSlots(listOf(alt.getOrNull(1)?.let { Slot(it) }, Slot(best, best = true), alt.getOrNull(0)?.let { Slot(it) }))
        refreshContext()
    }

    // --- bar tools --------------------------------------------------------------------------------------------------

    override fun onTool(tool: Tool) {
        when (tool) {
            Tool.CLIPBOARD -> { finishWord(false); showPanel(Panel.CLIPBOARD) }
            Tool.GIF -> { finishWord(false); showPanel(Panel.GIF) }
            Tool.EMOJI -> { finishWord(false); showPanel(Panel.EMOJI) }
            Tool.TRANSLATE -> { if (field == Field.PASSWORD) toastPrivate() else { finishWord(false); showPanel(Panel.TRANSLATE) } }
            Tool.HELP -> { finishWord(false); showPanel(Panel.TUTORIAL) }
            Tool.MIC -> toggleVoice()
            Tool.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (tool != Tool.MIC) bar?.closeTools()
    }

    private fun showPanel(p: Panel) {
        exitEmojiSearch()
        panel = p
        if (p != Panel.GIF) { exitGifSearch(); strip?.removeAllViews(); strip?.visibility = View.GONE }
        if (p != Panel.NONE) { bar?.closeTools(); bar?.setStatus(null) }
        keyboard?.visibility = if (p == Panel.NONE) View.VISIBLE else View.INVISIBLE
        translatePanel?.visibility = if (p == Panel.TRANSLATE) View.VISIBLE else View.GONE
        tutorialPanel?.visibility = if (p == Panel.TUTORIAL) View.VISIBLE else View.GONE
        emoji?.visibility = if (p == Panel.EMOJI) View.VISIBLE else View.GONE
        clipboardPanel?.visibility = if (p == Panel.CLIPBOARD) View.VISIBLE else View.GONE
        gifPanel?.visibility = if (p == Panel.GIF) View.VISIBLE else View.GONE
        candidatePanel?.visibility = if (p == Panel.CANDIDATES) View.VISIBLE else View.GONE
        when (p) {
            Panel.EMOJI -> {
                // As tall as the keyboard, never more: the panel takes its place and nothing else.
                val h = keyboard?.measuredHeight ?: 0
                emoji?.let { v -> if (h > 0 && v.layoutParams.height != h) { v.layoutParams = v.layoutParams.apply { height = h } } }
                emoji?.open(prefs.emojiRecent)
            }
            Panel.CLIPBOARD -> { clipBadge = false; bar?.setBadge(false); clipboardPanel?.refresh() }
            Panel.TRANSLATE -> translatePanel?.open()
            Panel.TUTORIAL -> tutorialPanel?.open(Tutorial.startIndex(prefs.tutorialSeen))
            Panel.GIF -> { gifPanel?.setLanguage(currentLayout().locale.language); gifPanel?.run(null) }
            Panel.CANDIDATES, Panel.NONE -> {}
        }
        if (p == Panel.NONE) updateSuggestions()
    }

    // --- emoji ------------------------------------------------------------------------------------------------------

    private val emojiListener = object : EmojiPanel.Listener {
        override fun onEmoji(text: String) {
            currentInputConnection?.commitText(text, 1)
            rememberEmoji(text)
            lastSwipe = null
        }
        override fun onBackspace() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        override fun onBack() = showPanel(Panel.NONE)
        override fun onSearch() = enterEmojiSearch()
    }

    private fun rememberEmoji(text: String) {
        prefs.emojiRecent = (listOf(text) + prefs.emojiRecent.filter { it != text }).take(32)
    }

    private fun emojiIndex() = EmojiIndex.load(this, currentLayout().locale.language)

    /** The word after a colon that is being typed, if the smart emoji search is on and the colon starts a word (not "http:"). */
    private fun emojiWanted(): String? {
        if (!prefs.smartEmoji || !predictionOn || composing.length < 2) return null
        val before = currentInputConnection?.getTextBeforeCursor(composing.length + 2, 0) ?: return null
        val colon = before.length - composing.length - 1
        if (colon < 0 || before[colon] != ':') return null
        return if (colon == 0 || before[colon - 1].isWhitespace()) composing.toString() else null
    }

    // --- emoji search -----------------------------------------------------------------------------------------------

    /** The search is typed with the keyboard itself: the results are a strip above the keys and the bar shows the words typed. */
    private fun enterEmojiSearch() {
        val results = emojiResults ?: return
        showPanel(Panel.NONE)
        emojiSearching = true
        emojiQuery.setLength(0)
        (results.parent as? ViewGroup)?.removeView(results)
        strip?.removeAllViews(); strip?.addView(results, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); strip?.visibility = View.VISIBLE
        showEmojiQuery()
    }

    private fun exitEmojiSearch() {
        if (!emojiSearching) return
        emojiSearching = false
        bar?.setStatus(null)
        strip?.removeAllViews(); strip?.visibility = View.GONE
    }

    private fun typeIntoEmojiSearch(text: String) {
        emojiQuery.append(text)
        showEmojiQuery()
    }

    private fun showEmojiQuery() {
        val q = emojiQuery.toString().trim()
        val found = if (q.isEmpty()) prefs.emojiRecent else EmojiData.drawable(emojiIndex().search(q, 80))
        bar?.setStatus(getString(R.string.emoji_search_prefix) + " " + emojiQuery.toString().ifEmpty { "…" })
        emojiResults?.setResults(found, q.isNotEmpty() && found.isEmpty())
    }

    // --- clipboard --------------------------------------------------------------------------------------------------

    private val clipListener2 = object : ClipboardPanel.Listener {
        override fun onPaste(item: ClipItem) {
            val ic = currentInputConnection ?: return
            if (!item.image) { ic.commitText(item.text.orEmpty(), 1); return }
            val file = app.clipboard.imageFile(item) ?: return
            if (!ContentSender.send(this@MetroWinIME, ic, currentInputEditorInfo, file, ContentSender.mimeOf(file), "image")) {
                Toast.makeText(this@MetroWinIME, R.string.clipboard_no_images, Toast.LENGTH_SHORT).show()
            }
        }
        override fun onAddImage() {
            startActivity(Intent(this@MetroWinIME, HelperActivity::class.java).putExtra(HelperActivity.EXTRA_MODE, HelperActivity.MODE_CLIP_IMAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun onBack() = showPanel(Panel.NONE)
    }

    /** Takes what was just copied into the history (text and pictures), unless the field it came from is private. */
    private fun captureClip(copied: Boolean) {
        if (!prefs.clipboard || field == Field.PASSWORD) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val desc = clip.description
        val uri = item.uri
        if (uri != null && desc.hasMimeType("image/*")) {
            if (!prefs.clipboardImages) return
            val mime = desc.getMimeType(0)
            val ext = mime.substringAfter('/', "img").substringBefore('+')
            worker.execute { runCatching { contentResolver.openInputStream(uri)?.let { app.clipboard.addImage(it, ext) } } }
            return
        }
        val text = runCatching { item.coerceToText(this) }.getOrNull()?.toString().orEmpty()
        if (text.isNotBlank() && app.clipboard.addText(text, copied) && panel != Panel.CLIPBOARD) {
            clipBadge = true
            if (Tool.from(prefs.quickTool) == Tool.CLIPBOARD) bar?.setBadge(true)
        }
    }

    private fun toastPrivate() = Toast.makeText(this, R.string.tr_private_field, Toast.LENGTH_SHORT).show()

    // --- translation ------------------------------------------------------------------------------------------------

    private val translateListener = object : TranslatePanel.Listener {
        override fun onBack() = showPanel(Panel.NONE)

        override fun readField(): Pair<String, Boolean> {
            val ic = currentInputConnection ?: return "" to false
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            if (selected.isNotEmpty()) return selected to true
            val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
            return (before + after) to false
        }

        override fun readClipboard(): String? = app.clipboard.all().firstOrNull { !it.image }?.text

        override fun replace(text: String, wasSelection: Boolean) {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            if (!wasSelection) {
                // The whole field was translated: replace all of it.
                val before = ic.getTextBeforeCursor(4000, 0)?.length ?: 0
                val after = ic.getTextAfterCursor(4000, 0)?.length ?: 0
                ic.deleteSurroundingText(before, after)
            }
            ic.commitText(text, 1)
            ic.endBatchEdit()
            showPanel(Panel.NONE)
        }

        override fun insert(text: String) {
            val ic = currentInputConnection ?: return
            val before = ic.getTextBeforeCursor(1, 0)
            ic.commitText((if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else "") + text, 1)
            showPanel(Panel.NONE)
        }

        override fun copy(text: String) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("translation", text))
            Toast.makeText(this@MetroWinIME, R.string.tr_copied, Toast.LENGTH_SHORT).show()
        }

        override fun openSettings() {
            startActivity(Intent(this@MetroWinIME, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // --- GIF --------------------------------------------------------------------------------------------------------

    private val gifListener = object : GifPanel.Listener {
        override fun onGif(item: GifItem) = sendGif(item)
        override fun onBack() = showPanel(Panel.NONE)
        override fun onOpenSettings() { startActivity(Intent(this@MetroWinIME, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        override fun onSearch() = enterGifSearch()
    }

    private fun sendGif(item: GifItem) {
        val info = currentInputEditorInfo
        val canSend = ContentSender.supports(info, "image/gif")
        if (!canSend) {
            // The app takes no pictures: give the link instead, which most chats turn into the GIF.
            if (prefs.gifLinkFallback && item.fullUrl.startsWith("https://")) { currentInputConnection?.commitText(item.fullUrl + " ", 1); exitGifSearch(); showPanel(Panel.NONE) }
            else Toast.makeText(this, R.string.gif_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        gifRepo.prepare(item) { r ->
            r.onSuccess { f ->
                if (ContentSender.send(this, currentInputConnection, currentInputEditorInfo, f, "image/gif", item.title.ifBlank { "GIF" })) { exitGifSearch(); showPanel(Panel.NONE) }
                else Toast.makeText(this, R.string.gif_not_supported, Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, R.string.gif_error, Toast.LENGTH_SHORT).show() }
        }
    }

    /** The search is typed with the keyboard itself: the panel becomes a strip above the keys and the bar shows the words typed. */
    private fun enterGifSearch() {
        val p = gifPanel ?: return
        gifSearching = true
        gifQuery.setLength(0)
        (p.parent as? ViewGroup)?.removeView(p)
        p.visibility = View.VISIBLE
        p.setStrip(true)
        strip?.removeAllViews(); strip?.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); strip?.visibility = View.VISIBLE
        keyboard?.visibility = View.VISIBLE
        gifPanel?.visibility = View.VISIBLE
        showGifQuery()
    }

    private fun exitGifSearch() {
        if (!gifSearching) return
        gifSearching = false
        bar?.setStatus(null)
        val p = gifPanel ?: return
        (p.parent as? ViewGroup)?.removeView(p)
        p.setStrip(false)
        frame?.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        strip?.visibility = View.GONE
    }

    private val gifSearchRun = Runnable { gifPanel?.run(gifQuery.toString().ifBlank { null }) }

    private fun typeIntoGifSearch(text: String) {
        gifQuery.append(text)
        showGifQuery()
        main.removeCallbacks(gifSearchRun)
        main.postDelayed(gifSearchRun, 600)
    }

    private fun showGifQuery() {
        bar?.setStatus(getString(R.string.gif_search_prefix) + " " + gifQuery.toString().ifEmpty { "…" })
        main.removeCallbacks(gifSearchRun)
        main.postDelayed(gifSearchRun, 600)
    }

    // --- voice ------------------------------------------------------------------------------------------------------

    private fun toggleVoice() {
        if (voice.isActive) voice.stop() else { finishWord(false); voice.start(currentLayout().locale.toLanguageTag()) }
    }

    internal val voiceCallbacks = object : VoiceInput.Callbacks {
        override fun onListening(on: Boolean) {
            if (!on) endVoiceText()
            bar?.setVoiceActive(on)
            bar?.setStatus(if (on) getString(R.string.voice_listening) else null)
        }
        // What is heard is written in the field as it comes, as the text being composed; the bar only says that it listens.
        override fun onPartial(text: String) {
            val ic = currentInputConnection ?: return
            if (!voiceText) startVoiceText(ic, text)
            ic.setComposingText(voiceLead + voiceCase(text), 1)
        }
        override fun onResult(text: String) {
            val ic = currentInputConnection ?: return
            if (!voiceText) startVoiceText(ic, text)
            ic.commitText(voiceLead + voiceCase(text) + " ", 1)
            voiceText = false
            if (shift == 1) setShift(0)
            refreshContext(); updateShiftFromCursor()
            bar?.setStatus(if (voice.isActive) getString(R.string.voice_listening) else null)
        }
        override fun onNeedPermission() {
            Toast.makeText(this@MetroWinIME, R.string.voice_permission, Toast.LENGTH_LONG).show()
            startActivity(Intent(this@MetroWinIME, HelperActivity::class.java).putExtra(HelperActivity.EXTRA_MODE, HelperActivity.MODE_MIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun onUnavailable() { Toast.makeText(this@MetroWinIME, R.string.voice_unavailable, Toast.LENGTH_LONG).show() }
        override fun onError(code: Int) { Toast.makeText(this@MetroWinIME, getString(R.string.voice_error, code), Toast.LENGTH_SHORT).show() }
    }

    private var voiceText = false
    private var voiceLead = ""
    private var voiceCapital = false

    /** Decides, before the first word of a phrase is written, whether it needs a space before it and a capital. */
    private fun startVoiceText(ic: android.view.inputmethod.InputConnection, text: String) {
        val before = ic.getTextBeforeCursor(1, 0)
        voiceLead = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        voiceCapital = shift != 0 || text.firstOrNull()?.isLowerCase() == true && isSentenceStart()
        voiceText = true
    }

    private fun voiceCase(text: String) = if (voiceCapital) text.replaceFirstChar { it.titlecase(currentLayout().locale) } else text

    /** A key, a suggestion or the end of dictation arrives: what was heard so far stays in the field as normal text. */
    private fun endVoiceText() {
        if (!voiceText) return
        voiceText = false
        currentInputConnection?.finishComposingText()
    }

    private fun isSentenceStart(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    private companion object {
        /** The longest run of Pinyin letters held at once: a sentence, not a page. */
        const val PINYIN_MAX = 48
    }
}
