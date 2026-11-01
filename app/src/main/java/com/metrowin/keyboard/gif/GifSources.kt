package com.metrowin.keyboard.gif

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class GifItem(val previewUrl: String, val fullUrl: String, val title: String = "")

/** The places GIFs can come from. The user picks one in the settings; nothing is contacted until the GIF panel is opened. */
enum class GifSourceType(val code: String, val needsKey: Boolean, val keyPage: String?) {
    GIPHY("giphy", true, "https://developers.giphy.com/dashboard/"),
    TENOR("tenor", true, "https://developers.google.com/tenor/guides/quickstart"),
    KLIPY("klipy", true, "https://partner.klipy.com/"),
    IMGUR("imgur", true, "https://api.imgur.com/oauth2/addclient"),
    FOLDER("folder", false, null),
    CUSTOM("custom", false, null);

    /** Whether the service has a content level to choose (the custom one can use {rating} in its address). */
    val hasRating get() = this != FOLDER

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: GIPHY }
}

/**
 * How strict the search is. The names are the film-style ones GIPHY uses; the other services have their own scales and are
 * mapped to the nearest: Tenor and Klipy "high / medium / low / off", Imgur "mature or not".
 */
enum class GifRating(val code: String, val tenorLevel: String) {
    G("g", "high"), PG("pg", "medium"), PG13("pg-13", "low"), R("r", "off");

    /** Imgur has no levels, only a "mature" flag on items: it is shown only at the loosest setting. */
    val allowsMature get() = this == R

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: PG13 }
}

/** Builds the requests for the online sources and reads their answers. Pure: the network is somewhere else, so this is tested. */
object GifSources {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun giphyUrl(key: String, query: String?, limit: Int, language: String, rating: GifRating = GifRating.PG13): String =
        if (query.isNullOrBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=${enc(key)}&limit=$limit&rating=${rating.code}"
        else "https://api.giphy.com/v1/gifs/search?api_key=${enc(key)}&q=${enc(query)}&limit=$limit&rating=${rating.code}&lang=${enc(language)}"

    fun tenorUrl(key: String, query: String?, limit: Int, language: String, rating: GifRating = GifRating.PG13): String =
        if (query.isNullOrBlank()) "https://tenor.googleapis.com/v2/featured?key=${enc(key)}&limit=$limit&media_filter=tinygif,gif&contentfilter=${rating.tenorLevel}&locale=${enc(language)}"
        else "https://tenor.googleapis.com/v2/search?key=${enc(key)}&q=${enc(query)}&limit=$limit&media_filter=tinygif,gif&contentfilter=${rating.tenorLevel}&locale=${enc(language)}"

    fun klipyUrl(key: String, query: String?, limit: Int, rating: GifRating = GifRating.PG13): String {
        // No customer id is sent: Klipy does not require one, and the keyboard identifies nobody.
        val common = "per_page=$limit&format_filter=gif&content_filter=${rating.tenorLevel}"
        return if (query.isNullOrBlank()) "https://api.klipy.com/api/v1/${enc(key)}/gifs/trending?$common"
        else "https://api.klipy.com/api/v1/${enc(key)}/gifs/search?q=${enc(query)}&$common"
    }

    fun imgurUrl(query: String?): String =
        if (query.isNullOrBlank()) "https://api.imgur.com/3/gallery/hot/viral/0"
        else "https://api.imgur.com/3/gallery/search/viral/all/0?q=${enc(query)}&q_type=anigif"

    /** Imgur wants the key as a header, not in the address. */
    fun imgurHeaders(clientId: String) = mapOf("Authorization" to "Client-ID $clientId")

    /** [template] has {query}, {key}, {limit}, {lang} and {rating}; each is replaced (the query is URL-encoded). */
    fun customUrl(template: String, key: String, query: String?, limit: Int, language: String, rating: GifRating = GifRating.PG13): String =
        template.replace("{query}", enc(query.orEmpty())).replace("{key}", enc(key)).replace("{limit}", limit.toString())
            .replace("{lang}", enc(language)).replace("{rating}", rating.code)

    fun parseGiphy(json: String): List<GifItem> = parseCustom(json, "data", "images.fixed_width_small.url", "images.original.url")
        .ifEmpty { emptyList() }

    /** Klipy: `data.data[].file.{hd,md,sm,xs}.gif.url`. The small one previews, the medium one is sent. */
    fun parseKlipy(json: String): List<GifItem> {
        val list = runCatching { JSONObject(json).getJSONObject("data").getJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<GifItem>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val file = item.optJSONObject("file") ?: continue
            fun url(vararg sizes: String) = sizes.firstNotNullOfOrNull { s -> file.optJSONObject(s)?.optJSONObject("gif")?.optString("url")?.takeIf { isUrl(it) } }
            val full = url("md", "hd", "sm", "xs") ?: continue
            out += GifItem(url("sm", "xs", "md", "hd") ?: full, full, item.optString("title"))
        }
        return out
    }

    /**
     * Imgur: gallery items are single pictures or albums; the animated GIFs among them are listed. Items marked mature are left out
     * unless [allowMature]; GIFs larger than [MAX_BYTES] are left out because they would not be sent.
     */
    fun parseImgur(json: String, allowMature: Boolean): List<GifItem> {
        val list = runCatching { JSONObject(json).getJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<GifItem>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optBoolean("nsfw", false) && !allowMature) continue
            val pictures = item.optJSONArray("images")
            val candidates = if (pictures != null) (0 until pictures.length()).mapNotNull { pictures.optJSONObject(it) } else listOf(item)
            val gif = candidates.firstOrNull { c ->
                val link = c.optString("link")
                isUrl(link) && (c.optString("type") == "image/gif" || link.endsWith(".gif")) && c.optLong("size", 0) <= MAX_BYTES
            } ?: continue
            val link = gif.getString("link")
            out += GifItem(link, link, item.optString("title").takeIf { it != "null" }.orEmpty())
        }
        return out
    }

    const val MAX_BYTES = 12L * 1024 * 1024

    fun parseTenor(json: String): List<GifItem> = parseCustom(json, "results", "media_formats.tinygif.url", "media_formats.gif.url")

    /**
     * Any JSON API: [listPath] is the path to the array of results ("data", or "response.items"), and [previewPath] and [fullPath]
     * are paths inside each result ("images.original.url"; a number picks an array element: "media.0.gif.url").
     */
    fun parseCustom(json: String, listPath: String, previewPath: String, fullPath: String): List<GifItem> {
        val root: Any = runCatching { JSONObject(json) }.getOrNull() ?: runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val list = (if (listPath.isBlank()) root else walk(root, listPath)) as? JSONArray ?: return emptyList()
        val out = ArrayList<GifItem>()
        for (i in 0 until list.length()) {
            val item = list.opt(i) ?: continue
            val preview = (walk(item, previewPath) as? String)?.takeIf { isUrl(it) }
            val full = (walk(item, fullPath) as? String)?.takeIf { isUrl(it) }
            val any = full ?: preview ?: continue
            out += GifItem(preview ?: any, full ?: any, (item as? JSONObject)?.optString("title").orEmpty())
        }
        return out
    }

    private fun isUrl(s: String) = s.startsWith("https://")

    private fun walk(start: Any?, path: String): Any? {
        var cur: Any? = start
        for (part in path.split('.').filter { it.isNotEmpty() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(part)
                is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
                else -> null
            } ?: return null
        }
        return cur
    }
}
