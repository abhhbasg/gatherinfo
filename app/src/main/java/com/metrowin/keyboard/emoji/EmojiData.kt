package com.metrowin.keyboard.emoji

import android.graphics.Paint
import java.util.Locale

enum class EmojiCategory(val tab: String, val columns: Int, val big: Boolean) {
    RECENT("🕘", 8, true), SMILEYS("😀", 8, true), PEOPLE("👋", 8, true), NATURE("🐻", 8, true), FOOD("🍔", 8, true),
    ACTIVITIES("⚽", 8, true), TRAVEL("🚗", 8, true), OBJECTS("💡", 8, true), SYMBOLS("❤", 8, true), FLAGS("🏳", 6, true),
    CLASSIC(":)", 3, false), KAOMOJI("ツ", 2, false),
}

/**
 * The emoji, built from Unicode ranges and kept to the ones the phone can draw (an emoji the font lacks would show as an empty
 * box). The classic ones are the text emoticons and the kaomoji, which work in every app and on every phone.
 */
object EmojiData {
    private fun ranges(vararg r: IntRange): List<Int> = r.flatMap { it.toList() }

    private val smileys = ranges(0x1F600..0x1F64F, 0x1F910..0x1F92F, 0x1F970..0x1F97A, 0x1FAE0..0x1FAE8, 0x1F9D0..0x1F9D0)
    private val people = ranges(0x1F440..0x1F487, 0x1F4AA..0x1F4AA, 0x1F64B..0x1F64F, 0x1F90C..0x1F90F, 0x1F918..0x1F91F, 0x1F930..0x1F939, 0x1F9B0..0x1F9BF, 0x1F9D1..0x1F9DF, 0x1F46A..0x1F46F, 0x1FAF0..0x1FAF8)
    private val nature = ranges(0x1F400..0x1F43E, 0x1F980..0x1F9AE, 0x1F330..0x1F335, 0x1F337..0x1F344, 0x1F300..0x1F30C, 0x1F31D..0x1F320, 0x1F525..0x1F525, 0x1F4A7..0x1F4A7)
    private val food = ranges(0x1F345..0x1F37F, 0x1F950..0x1F96B, 0x1F32D..0x1F32F, 0x1F9C0..0x1F9CB, 0x1FAD0..0x1FADB)
    private val activities = ranges(0x1F383..0x1F3CA, 0x1F3CF..0x1F3D3, 0x1F94A..0x1F94F, 0x26BD..0x26BE, 0x1F9E9..0x1F9FF, 0x1F3AE..0x1F3B0)
    private val travel = ranges(0x1F680..0x1F6A4, 0x1F6A5..0x1F6C5, 0x1F3E0..0x1F3F0, 0x1F5FB..0x1F5FF, 0x1F6D0..0x1F6D7, 0x1F6DD..0x1F6DF, 0x2708..0x2708, 0x26F5..0x26F5)
    private val objects = ranges(0x1F4A1..0x1F4A9, 0x1F4B0..0x1F4FC, 0x1F50A..0x1F52E, 0x1F3A7..0x1F3A9, 0x1F9F0..0x1F9F8, 0x1F5D1..0x1F5D3)
    private val symbols = ranges(0x1F493..0x1F49F, 0x1F4AF..0x1F4AF, 0x2705..0x2705, 0x274C..0x274C, 0x2B50..0x2B50, 0x2757..0x2757, 0x2753..0x2753,
        0x1F534..0x1F53D, 0x1F4A2..0x1F4A5, 0x1F4AB..0x1F4AB, 0x1F6AB..0x1F6AB, 0x1F51E..0x1F51E, 0x267B..0x267B, 0x1F3B5..0x1F3B6, 0x2795..0x2797)
    private val heart = "\u2764\uFE0F"

    val classic = listOf(
        ":)", ":-)", ":(", ":-(", ";)", ";-)", ":D", ":-D", ":P", ":-P", ":O", ":-O", ":/", ":-/", ":|", ":-|", ":'(", ":*",
        "XD", "xD", ":3", ">:(", "B)", "8)", "O:)", "<3", "</3", "^_^", "-_-", "o_O", "O_o", "T_T", ">_<", "^^'", "=)", "=D", "=P", "=/",
        "\\o/", "o/", "\\o", ":v", ":^)", ":]", ":[", "(:", "):", "(;", ";(", ":>", ":<", "D:", "DX", "OwO", "UwU", ">:)", "}:)",
    )

    val kaomoji = listOf(
        "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "┬─┬ ノ( ゜-゜ノ)", "ಠ_ಠ", "( ͡° ͜ʖ ͡°)", "(づ｡◕‿‿◕｡)づ", "ʕ•ᴥ•ʔ", "(ง •̀_•́)ง", "(•_•)", "(⌐■_■)",
        "(＾▽＾)", "(*^‿^*)", "(≧▽≦)", "(｡•́︿•̀｡)", "(ノಠ益ಠ)ノ彡┻━┻", "(•‿•)", "ヽ(°〇°)ﾉ", "(^_−)☆", "(☞ﾟヮﾟ)☞", "☜(ﾟヮﾟ☜)",
        "(ᵔᴥᵔ)", "(◕‿◕✿)", "٩(◕‿◕)۶", "┏(＾0＾)┛", "(ʘ‿ʘ)", "(ಥ﹏ಥ)", "(T▽T)", "(；一_一)", "(￣▽￣)ゞ", "m(_ _)m",
    )

    private val flagCountries by lazy { Locale.getISOCountries().toList() }

    private var cache: Map<EmojiCategory, List<String>>? = null

    /** The categories that can be drawn on this phone; [recent] fills the first one. */
    @Synchronized fun categories(recent: List<String>): Map<EmojiCategory, List<String>> {
        val built = cache ?: build().also { cache = it }
        return LinkedHashMap<EmojiCategory, List<String>>().apply {
            put(EmojiCategory.RECENT, recent)
            putAll(built)
        }
    }

    private fun build(): Map<EmojiCategory, List<String>> {
        val paint = Paint()
        fun make(points: List<Int>) = points.distinct().map { String(Character.toChars(it)) }.filter { paint.hasGlyph(it) }
        val out = LinkedHashMap<EmojiCategory, List<String>>()
        out[EmojiCategory.SMILEYS] = make(smileys)
        out[EmojiCategory.PEOPLE] = make(people)
        out[EmojiCategory.NATURE] = make(nature)
        out[EmojiCategory.FOOD] = make(food)
        out[EmojiCategory.ACTIVITIES] = make(activities)
        out[EmojiCategory.TRAVEL] = make(travel)
        out[EmojiCategory.OBJECTS] = make(objects)
        out[EmojiCategory.SYMBOLS] = listOf(heart).filter { paint.hasGlyph(it) } + make(symbols)
        out[EmojiCategory.FLAGS] = flagCountries.map { cc -> String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A'))) }.filter { paint.hasGlyph(it) }
        out[EmojiCategory.CLASSIC] = classic
        out[EmojiCategory.KAOMOJI] = kaomoji
        return out.filterValues { it.isNotEmpty() }
    }

    private val tones = listOf(0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF).map { String(Character.toChars(it)) }

    /** The skin-tone variants of [emoji] that this phone can draw, or an empty list when it has none. */
    fun skinTones(emoji: String): List<String> {
        if (emoji.codePointCount(0, emoji.length) != 1) return emptyList()
        val paint = Paint()
        val variants = tones.map { emoji + it }.filter { paint.hasGlyph(it) }
        return if (variants.size == tones.size) listOf(emoji) + variants else emptyList()
    }
}
