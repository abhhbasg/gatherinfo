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
    /** Keys that look like part of the frame rather than part of the keys: shift, backspace, ?123. */
    val special: Boolean = false,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val width get() = right - left
    val height get() = bottom - top

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

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
        if (showNumberRow) rows += digits.map { d -> Key(KeyType.CHAR, d, popups = symbolPopups[d].orEmpty()) }
        layout.rows.forEachIndexed { index, letters ->
            val keys = ArrayList<Key>()
            val isLast = index == layout.rows.lastIndex && layout.rows.size >= 3
            if (isLast) keys += Key(KeyType.SHIFT, weight = 1.5f, special = true)
            letters.forEachIndexed { col, l ->
                // With no number row, the first row carries the digits as hints and as the first long-press choice.
                val digit = if (!showNumberRow && index == 0 && col < 10) digits[col] else null
                val popups = buildList {
                    if (digit != null) add(digit)
                    addAll(layout.longPress[l].orEmpty())
                }
                keys += Key(KeyType.CHAR, l, popups = popups, hint = if (showHints) digit else null)
            }
            if (isLast) keys += Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
            rows += keys
        }
        rows += bottomRow("?123", KeyType.SYMBOLS, multiLanguage)
        return KeyboardPage(rows)
    }

    fun symbols(page: Page, showNumberRow: Boolean): KeyboardPage {
        val rows = ArrayList<List<Key>>()
        fun chars(s: String) = s.split(' ').map { c -> Key(KeyType.CHAR, c, popups = symbolPopups[c].orEmpty()) }
        if (page == Page.SYMBOLS_1) {
            rows += chars("1 2 3 4 5 6 7 8 9 0")
            rows += chars("@ # $ % & - + ( )")
            rows += listOf(Key(KeyType.SYMBOLS_PAGE, "=\\<", 1.5f, special = true)) + chars("* \" ' : ; ! ?") + Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
        } else {
            rows += chars("~ ` | • √ π ÷ × ¶ ∆")
            rows += chars("£ ¢ € ¥ ^ ° = { }")
            rows += listOf(Key(KeyType.SYMBOLS_PAGE, "?123", 1.5f, special = true)) + chars("\\ © ® ™ [ ] <") + Key(KeyType.BACKSPACE, weight = 1.5f, special = true)
        }
        rows += bottomRow("ABC", KeyType.SYMBOLS, false)
        return KeyboardPage(rows)
    }

    /** The pad for number and phone fields: big digits, the signs a phone number uses, no letters. */
    fun numbers(phone: Boolean): KeyboardPage {
        fun chars(s: String) = s.split(' ').map { c -> Key(KeyType.CHAR, c, popups = symbolPopups[c].orEmpty()) }
        val rows = listOf(
            chars("1 2 3"), chars("4 5 6"), chars("7 8 9"),
            chars(if (phone) "+ 0 #" else ". 0 -") ,
            listOf(Key(KeyType.CHAR, if (phone) "*" else ",", 1f), Key(KeyType.SPACE, weight = 1f), Key(KeyType.BACKSPACE, weight = 1f, special = true), Key(KeyType.ENTER, weight = 1f, special = true)),
        )
        return KeyboardPage(rows)
    }

    private fun bottomRow(toggleLabel: String, toggleType: KeyType, multiLanguage: Boolean): List<Key> = buildList {
        add(Key(toggleType, toggleLabel, 1.5f, special = true))
        add(Key(KeyType.EMOJI, "☺", 1f, special = true))
        if (multiLanguage) add(Key(KeyType.LANGUAGE, "", 1f, special = true))
        add(Key(KeyType.CHAR, ",", 0.8f, popups = symbolPopups[","].orEmpty()))
        add(Key(KeyType.SPACE, "", if (multiLanguage) 3.2f else 4.2f))
        add(Key(KeyType.CHAR, ".", 0.8f, popups = symbolPopups["."].orEmpty()))
        add(Key(KeyType.ENTER, "", 1.5f, special = true))
    }
}
