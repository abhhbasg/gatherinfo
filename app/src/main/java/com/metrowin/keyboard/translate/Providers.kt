package com.metrowin.keyboard.translate

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** The services text can be translated by. The first is the default; the user can switch in the settings. */
enum class Provider(val code: String, val needsKey: Boolean, val needsUrl: Boolean) {
    GOOGLE("google", false, false),
    GOOGLE_CLOUD("google_cloud", true, false),
    DEEPL("deepl", true, false),
    LIBRE("libre", false, true),
    MYMEMORY("mymemory", false, false);

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: GOOGLE }
}

class HttpRequest(val method: String, val url: String, val body: ByteArray? = null, val contentType: String? = null, val headers: Map<String, String> = emptyMap())

class TranslationException(message: String) : Exception(message)

/** How much of a plan has been used. [limit] is 0 when the plan has no cap. */
data class Usage(val used: Long, val limit: Long) {
    val remaining get() = if (limit > 0) (limit - used).coerceAtLeast(0) else -1L
}

data class Translated(val text: String, val detectedLanguage: String?)

/**
 * Builds the request for a provider and reads its answer. Pure, so it is tested without a network. [source] is "auto" or a
 * language code ("it", "pt"); [target] is a language code.
 */
object Providers {
    const val AUTO = "auto"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }.toByteArray()

    /** How much text one request carries; long texts are cut at sentence ends and sent in pieces. */
    fun chunkSize(p: Provider) = when (p) { Provider.MYMEMORY -> 450; Provider.GOOGLE -> 1200; else -> 4000 }

    fun request(p: Provider, text: String, source: String, target: String, key: String, libreUrl: String): HttpRequest = when (p) {
        // The web endpoint Google's own translate page uses. It needs no key but is not an official API: it can change or limit use.
        Provider.GOOGLE -> HttpRequest("GET", "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${enc(source)}&tl=${enc(target)}&dt=t&q=${enc(text)}")
        Provider.GOOGLE_CLOUD -> HttpRequest(
            "POST", "https://translation.googleapis.com/language/translate/v2?key=${enc(key)}",
            form("q" to text, "target" to target, "format" to "text", *(if (source == AUTO) emptyArray() else arrayOf("source" to source))),
            "application/x-www-form-urlencoded",
        )
        Provider.DEEPL -> {
            val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            HttpRequest(
                "POST", "https://$host/v2/translate",
                form("text" to text, "target_lang" to deeplTarget(target), *(if (source == AUTO) emptyArray() else arrayOf("source_lang" to source.substringBefore('-').uppercase(Locale.ROOT)))),
                "application/x-www-form-urlencoded", mapOf("Authorization" to "DeepL-Auth-Key $key"),
            )
        }
        Provider.LIBRE -> HttpRequest(
            "POST", libreUrl.trimEnd('/') + "/translate",
            JSONObject().put("q", text).put("source", source).put("target", target).put("format", "text").apply { if (key.isNotBlank()) put("api_key", key) }.toString().toByteArray(),
            "application/json",
        )
        Provider.MYMEMORY -> HttpRequest("GET", "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=${enc(if (source == AUTO) "Autodetect" else source)}%7C${enc(target)}")
    }

    /** The request that tells how much of the plan is left, for the services that offer one (only DeepL does), or null. */
    fun usageRequest(p: Provider, key: String): HttpRequest? = when (p) {
        Provider.DEEPL -> HttpRequest("GET", "https://${if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"}/v2/usage", headers = mapOf("Authorization" to "DeepL-Auth-Key $key"))
        else -> null
    }

    fun parseUsage(body: String): Usage = try {
        val o = JSONObject(body)
        val limit = o.optLong("character_limit", 0)
        // A plan with no cap reports a limit too big to mean anything.
        Usage(o.getLong("character_count"), if (limit >= 1_000_000_000_000L) 0 else limit)
    } catch (e: Exception) { throw TranslationException("Unreadable answer") }

    /** DeepL wants a region for a few languages. */
    fun deeplTarget(code: String): String = when (code.lowercase(Locale.ROOT)) {
        "en" -> "EN-US"; "pt" -> "PT-PT"; "zh" -> "ZH"; "no" -> "NB"
        else -> code.substringBefore('-').uppercase(Locale.ROOT)
    }

    fun parse(p: Provider, body: String): Translated = try {
        when (p) {
            Provider.GOOGLE -> {
                val root = JSONArray(body)
                val parts = root.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until parts.length()) parts.optJSONArray(i)?.optString(0)?.takeIf { it != "null" }?.let { sb.append(it) }
                Translated(sb.toString(), root.optString(2).takeIf { it.isNotBlank() && it != "null" })
            }
            Provider.GOOGLE_CLOUD -> {
                val t = JSONObject(body).getJSONObject("data").getJSONArray("translations").getJSONObject(0)
                Translated(unescapeHtml(t.getString("translatedText")), t.optString("detectedSourceLanguage").ifBlank { null })
            }
            Provider.DEEPL -> {
                val t = JSONObject(body).getJSONArray("translations").getJSONObject(0)
                Translated(t.getString("text"), t.optString("detected_source_language").lowercase(Locale.ROOT).ifBlank { null })
            }
            Provider.LIBRE -> {
                val o = JSONObject(body)
                Translated(o.getString("translatedText"), o.optJSONObject("detectedLanguage")?.optString("language")?.ifBlank { null })
            }
            Provider.MYMEMORY -> {
                val o = JSONObject(body)
                if (o.optInt("responseStatus", 200) != 200) throw TranslationException(o.optString("responseDetails"))
                Translated(unescapeHtml(o.getJSONObject("responseData").getString("translatedText")), null)
            }
        }
    } catch (e: TranslationException) { throw e } catch (e: Exception) { throw TranslationException("Unreadable answer") }

    /** The cloud services answer with HTML entities even for plain text. */
    fun unescapeHtml(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Cuts [text] into pieces of at most [max] characters, at line and sentence ends when it can, so each piece reads on its own. */
    fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut < max / 3) cut = max - 1
            out += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

/** The languages the translation panel offers. Names are shown in the language of the phone. */
object TranslateLanguages {
    val codes = listOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "uk", "tr", "el", "cs", "sv", "ro", "ar", "he", "zh", "ja", "ko", "hi",
        "id", "vi", "th", "hu", "fi", "da", "no", "bg", "hr", "sk", "sl", "sr", "lt", "lv", "et", "fa", "ca", "ms", "bn",
    )

    fun name(code: String, inLocale: Locale = Locale.getDefault()): String =
        if (code == Providers.AUTO) "Auto" else Locale.forLanguageTag(code).getDisplayLanguage(inLocale).replaceFirstChar { it.titlecase(inLocale) }.ifBlank { code }
}
