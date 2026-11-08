package com.metrowin.keyboard.layout

import java.util.Locale

/**
 * One language of the keyboard: the letter rows, what a long press offers on each letter, and how the language treats the
 * apostrophe. Read from a small text file (".mwl") so anyone can add a language without touching the app:
 *
 * ```
 * id=it
 * name=Italiano
 * locale=it_IT
 * dict=it                 (optional: the dictionary to use; default is id)
 * apostrophe=break        (join: "don't" is one word; break: "l'albero" is two)
 * row=q w e r t y u i o p
 * row=a s d f g h j k l
 * row=z x c v b n m
 * long.a=à á â ä
 * ```
 */
data class LanguageLayout(
    val id: String,
    val name: String,
    val locale: Locale,
    val dictionaryId: String,
    val joinApostrophe: Boolean,
    val rows: List<List<String>>,
    val longPress: Map<String, List<String>>,
    val builtIn: Boolean = true,
) {
    /** Every letter key of the layout, lower case. */
    val letters: Set<String> get() = rows.flatten().toSet()

    companion object {
        fun parse(text: String, builtIn: Boolean): LanguageLayout? {
            val map = LinkedHashMap<String, String>()
            val rows = ArrayList<List<String>>()
            val long = LinkedHashMap<String, List<String>>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when {
                    key == "row" -> value.split(' ', '\t').filter { it.isNotEmpty() }.let { if (it.isNotEmpty()) rows += it }
                    key.startsWith("long.") -> long[key.removePrefix("long.")] = value.split(' ', '\t').filter { it.isNotEmpty() }
                    else -> map[key] = value
                }
            }
            val id = map["id"]?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } } ?: return null
            if (rows.isEmpty() || rows.size > 4 || rows.any { it.size > 14 }) return null
            val locale = map["locale"]?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: Locale.forLanguageTag(id)
            return LanguageLayout(
                id = id,
                name = map["name"] ?: id,
                locale = locale,
                dictionaryId = map["dict"] ?: id,
                joinApostrophe = map["apostrophe"] != "break",
                rows = rows,
                longPress = long,
                builtIn = builtIn,
            )
        }
    }
}
