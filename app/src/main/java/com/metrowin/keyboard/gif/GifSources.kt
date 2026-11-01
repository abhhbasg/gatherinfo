package com.metrowin.keyboard.gif

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class GifItem(val previewUrl: String, val fullUrl: String, val title: String = "")

/** The places GIFs can come from. The user picks one in the settings; nothing is contacted until the GIF panel is opened. */
enum class GifSourceType(val code: String) {
    GIPHY("giphy"), TENOR("tenor"), FOLDER("folder"), CUSTOM("custom");

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: GIPHY }
}

/** Builds the requests for the online sources and reads their answers. Pure: the network is somewhere else, so this is tested. */
object GifSources {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun giphyUrl(key: String, query: String?, limit: Int, language: String): String =
        if (query.isNullOrBlank()) "https://api.giphy.com/v1/gifs/trending?api_key=${enc(key)}&limit=$limit&rating=pg-13"
        else "https://api.giphy.com/v1/gifs/search?api_key=${enc(key)}&q=${enc(query)}&limit=$limit&rating=pg-13&lang=${enc(language)}"

    fun tenorUrl(key: String, query: String?, limit: Int, language: String): String =
        if (query.isNullOrBlank()) "https://tenor.googleapis.com/v2/featured?key=${enc(key)}&limit=$limit&media_filter=tinygif,gif&locale=${enc(language)}"
        else "https://tenor.googleapis.com/v2/search?key=${enc(key)}&q=${enc(query)}&limit=$limit&media_filter=tinygif,gif&locale=${enc(language)}"

    /** [template] has {query}, {key}, {limit} and {lang}; each is replaced (the query is URL-encoded). */
    fun customUrl(template: String, key: String, query: String?, limit: Int, language: String): String =
        template.replace("{query}", enc(query.orEmpty())).replace("{key}", enc(key)).replace("{limit}", limit.toString()).replace("{lang}", enc(language))

    fun parseGiphy(json: String): List<GifItem> = parseCustom(json, "data", "images.fixed_width_small.url", "images.original.url")
        .ifEmpty { emptyList() }

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
