package com.metrowin.keyboard.layout

enum class KeyType { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, SYMBOLS_PAGE, EMOJI, LANGUAGE }

enum class Page { LETTERS, SYMBOLS_1, SYMBOLS_2, NUMBERS }

/**
 * A key of the keyboard. [label] is what a CHAR key types (lower case; the shift state decides the case). Position and size are
 * set by the view when it lays the rows out, which is why they are mutable: a keyboard is laid out once per size, not per frame.
 */
class Key(
    val type: KeyType,
    val label: String = "",
    val weight: Float = 1f,
    val popups: List<String> = emptyList(),
    /** Small text in the corner of the key (the digit under a letter, when the number row is hidden). */
    val hint: String? = null,
    /** Keys that look like part of the frame rather than part of the keys: shift, backspace, &123. */
    val special: Boolean = false,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    /** Where a touch counts as this key: the key itself, and for the first and last key of a row that is set in from the sides
     *  (the second row of letters, as on Windows Phone) also the room up to the side of the keyboard. */
    var touchLeft = 0f
    var touchRight = 0f

    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val width get() = right - left
    val height get() = bottom - top

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun touched(x: Float, y: Float) = x >= touchLeft && x < touchRight && y >= top && y < bottom

    /** Squared distance from a point to the key's rectangle (0 when inside), to pick the closest key for a touch in a gap. */
    fun distanceSq(x: Float, y: Float): Float {
        val dx = if (x < left) left - x else if (x > right) x - right else 0f
        val dy = if (y < top) top - y else if (y > bottom) y - bottom else 0f
        return dx * dx + dy * dy
    }
}

class KeyboardPage(val rows: List<List<Key>>) {
    val keys: List<Key> = rows.flatten()
}

/** Builds the pages of the keyboard for a language and the user's choices. */
object KeyboardBuilder {
    private val digits = "1234567890".map { it.toString() }

    /** The long-press bar of a digit or symbol key: the key's own character first (letting go without sliding types it), then the alternatives. */
    private fun withBase(base: String, alternatives: List<String>): List<String> =
        if (alternatives.isEmpty()) emptyList() else listOf(base) + alternatives.filter { it != base }

    val symbolPopups: Map<String, List<String>> = mapOf(
        "-" to listOf("–", "—", "•", "_"),
        "\"" to listOf("«", "»", "“", "”", "„"),
        "'" to listOf("‘", "’", "‚", "`"),
        "." to listOf("…", ",", "!", "?", ":", ";"),
        "," to listOf(";", ":", "'", "\""),
        "!" to listOf("¡"),
        "?" to listOf("¿"),
        "$" to listOf("€", "£", "¥", "₹", "¢", "₩"),
        "%" to listOf("‰"),
        "+" to listOf("±", "×", "÷"),
        "=" to listOf("≠", "≈", "≤", "≥"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "&" to listOf("§", "¶"),
        "0" to listOf("°", "º"),
        "1" to listOf("¹", "½", "¼"),
        "2" to listOf("²"),
        "3" to listOf("³", "¾"),
        "/" to listOf("\\", "|"),
        "#" to listOf("№", "~"),
        "@" to listOf("©", "®", "™"),
    )

    fun letters(layout: LanguageLayout, showNumberRow: Boolean, showHints: Boolean, multiLanguage: Boolean): KeyboardPage {
        val rows = ArrayList<List<Key>>()
        if (showNumberRow) rows += digits.map { d -> Key(KeyType.CHAR, d, popups = withBase(d, symbolPopups[d].orEmpty())) }
        layout.rows.forEachIndexed { index, letters ->
            val keys = ArrayList<Key>()
            val isLast = index == layout.rows.lastIndex && layout.rows.size >= 3
            if (isLast) keys += Key(KeyType.SHIFT, weight = 1.5f, special = true)
            letters.forEachIndexed { col, l ->
                // With no number row, the first row carries the digits as hints and as the first long-press choice.
                val digit = if (!showNumberRow && index == 0 && col < 10) digits[col] else null
                // The first choice is the first accented form: holding a letter is how an accent is asked for. The plain letter and the digit follow.
                val accents = layout.longPress[l].orEmpty().filter { it != l }
                val popups = if (accents.isEmpty() && digit == null) emptyList() else accents + l + listOfNotNull(digit)
                keys += Key(KeyType.CHAR, l, popups = popups, hint = if (showHints) digit else null)
            }
            if (isLast) keys += Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
            rows += keys
        }
        rows += bottomRow(SYMBOLS_LABEL, multiLanguage, layout)
        return KeyboardPage(rows)
    }

    /** What the key that opens the digits and symbols shows, and the one that comes back to the letters: the Windows Phone labels. */
    const val SYMBOLS_LABEL = "&123"
    const val LETTERS_LABEL = "abcd"

    /**
     * Chinese punctuation for the symbol pages of a Chinese layout: the key types the full-width sign and holding it offers the
     * plain one.
     */
    private val chinese: Map<String, String> = mapOf(
        "(" to "（", ")" to "）", "!" to "！", ";" to "；", ":" to "：", "?" to "？", "\"" to "“", "'" to "‘", "\\" to "、",
        "$" to "¥", "<" to "《", ">" to "》", "[" to "【", "]" to "】", "^" to "…", "_" to "—", "~" to "～",
    )
    private val chinesePopups: Map<String, List<String>> = mapOf("“" to listOf("”", "\""), "‘" to listOf("’", "'"), "《" to listOf("〈", "<"), "》" to listOf("〉", ">"))

    /**
     * The digits and symbols, in the Windows Phone order: the first page has what a sentence needs, the arrow opens the second.
     * [layout] decides the punctuation (Chinese signs for a Chinese layout) and the two keys beside the space bar.
     */
    fun symbols(page: Page, showNumberRow: Boolean, layout: LanguageLayout? = null): KeyboardPage {
        val rows = ArrayList<List<Key>>()
        val cjk = layout?.pinyin == true
        fun key(c: String): Key {
            val shown = if (cjk) chinese[c] ?: c else c
            val more = if (shown != c) chinesePopups[shown] ?: listOf(c) else symbolPopups[c].orEmpty()
            return Key(KeyType.CHAR, shown, popups = withBase(shown, more))
        }
        fun chars(s: String) = s.split(' ').map { key(it) }
        if (page == Page.SYMBOLS_1) {
            rows += chars("1 2 3 4 5 6 7 8 9 0")
            rows += chars("@ # $ % & ( ) - \\")
            rows += listOf(Key(KeyType.SYMBOLS_PAGE, "→", 1.5f, special = true)) + chars("! ; : ' \" ? /") + Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
        } else {
            rows += chars("[ ] { } < > ^ * + =")
            rows += chars("_ | ~ ` ° • € £ ¥")
            rows += listOf(Key(KeyType.SYMBOLS_PAGE, "←", 1.5f, special = true)) + chars("÷ × √ π © ® ™") + Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
        }
        rows += bottomRow(LETTERS_LABEL, false, layout)
        return KeyboardPage(rows)
    }

    /** The pad for number and phone fields: big digits, the signs a phone number uses, no letters. */
    fun numbers(phone: Boolean): KeyboardPage {
        fun chars(s: String) = s.split(' ').map { c -> Key(KeyType.CHAR, c, popups = withBase(c, symbolPopups[c].orEmpty())) }
        val rows = listOf(
            chars("1 2 3"), chars("4 5 6"), chars("7 8 9"),
            chars(if (phone) "+ 0 #" else ". 0 -") ,
            listOf(Key(KeyType.CHAR, if (phone) "*" else ",", 1f), Key(KeyType.SPACE, weight = 1f), Key(KeyType.BACKSPACE, weight = 1f, special = true), Key(KeyType.ENTER, weight = 1f, special = true)),
        )
        return KeyboardPage(rows)
    }

    private fun bottomRow(toggleLabel: String, multiLanguage: Boolean, layout: LanguageLayout?): List<Key> = buildList {
        val comma = layout?.comma ?: ","
        val period = layout?.period ?: "."
        fun punctuation(c: String) = Key(KeyType.CHAR, c, 0.8f, popups = withBase(c, layout?.longPress?.get(c) ?: symbolPopups[c].orEmpty()))
        add(Key(KeyType.SYMBOLS, toggleLabel, 1.5f, special = true))
        add(Key(KeyType.EMOJI, "☺", 1f, special = true))
        if (multiLanguage) add(Key(KeyType.LANGUAGE, layout?.keyLabel.orEmpty(), 1f, special = true))
        add(punctuation(comma))
        add(Key(KeyType.SPACE, "", if (multiLanguage) 3.2f else 4.2f))
        add(punctuation(period))
        add(Key(KeyType.ENTER, "", 1.5f, special = true))
    }
}
