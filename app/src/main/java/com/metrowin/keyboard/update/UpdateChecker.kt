package com.metrowin.keyboard.update

import org.json.JSONObject

/**
 * The one place the keyboard looks for updates: the releases of its own repository, and nothing else. Every address that is fetched is
 * built from these constants or checked against them (see [UpdateNet]); a release that points anywhere else is ignored.
 */
object Repo {
    const val OWNER = "developerkenshi-coder"
    const val NAME = "MetroWin-Keyboard"
    const val LATEST = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"
    const val DOWNLOADS = "https://github.com/$OWNER/$NAME/releases/download/"
    const val RAW = "https://raw.githubusercontent.com/$OWNER/$NAME/"
    const val PAGE = "https://github.com/$OWNER/$NAME"

    /** Hosts a download may be sent on to by GitHub itself (the release files are served from its own storage). */
    val REDIRECT_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    val FIRST_HOSTS = setOf("api.github.com", "github.com", "raw.githubusercontent.com")

    /** Is [url] one the updater may ask for first: this repository's release list, its release files or its raw files. */
    fun isOurs(url: String) =
        (url == LATEST || url.startsWith(DOWNLOADS) || url.startsWith(RAW)) &&
            // Nothing that could walk out of the repository's path or carry a login or a second address.
            !url.contains("..") && !url.contains("%2e", ignoreCase = true) && !url.contains('\\') && !url.contains('@') && url.none { it.isWhitespace() }
}

/** A release of the keyboard: what to show, and the file to download. */
data class Release(
    val tag: String,
    val version: String,
    val date: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sumsUrl: String?,
) {
    fun toJson(): String = JSONObject().put("tag", tag).put("version", version).put("date", date).put("notes", notes)
        .put("apkUrl", apkUrl).put("apkName", apkName).put("apkSize", apkSize).put("sumsUrl", sumsUrl ?: "").toString()

    companion object {
        fun fromJson(s: String): Release? = runCatching {
            val j = JSONObject(s)
            Release(j.getString("tag"), j.getString("version"), j.optString("date"), j.optString("notes"), j.getString("apkUrl"), j.getString("apkName"), j.optLong("apkSize"), j.optString("sumsUrl").ifEmpty { null })
        }.getOrNull()?.takeIf { it.apkUrl.startsWith(Repo.DOWNLOADS) }
    }
}

/** Version names like "26.1S" or "v26.7.2S": the numbers in order, compared one by one. */
object Versions {
    fun parts(v: String): List<Int> = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    fun compare(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String) = parts(candidate).isNotEmpty() && compare(candidate, installed) > 0
}

/** The notes shown with an update: the sections of the repository's CHANGELOG.md newer than the installed version, as plain text. */
object Changelog {
    /** The sections "## 26.2S" of [markdown] after [installed] and up to [latest], newest first, cleaned for reading. */
    fun since(markdown: String, installed: String, latest: String): String {
        val out = ArrayList<String>()
        var heading: String? = null
        val body = StringBuilder()
        fun flush() {
            val h = heading ?: return
            if (Versions.parts(h).isNotEmpty() && Versions.isNewer(h, installed) && Versions.compare(h, latest) <= 0) out += h + "\n" + body.toString().trim()
        }
        for (line in markdown.lineSequence()) {
            if (line.startsWith("## ")) { flush(); heading = line.removePrefix("## ").trim(); body.setLength(0) } else body.appendLine(line)
        }
        flush()
        return clean(out.joinToString("\n\n"))
    }

    /** Markdown as plain text: no emphasis marks, links as their text, list items as bullets. */
    fun clean(md: String): String = md.lines().joinToString("\n") { raw ->
        var l = raw.trimEnd()
        l = l.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").replace("**", "").replace("__", "").replace("`", "")
        l = l.replace(Regex("(?<![A-Za-z0-9])\\*([^*\\n]+)\\*(?![A-Za-z0-9])"), "$1")
        l = l.replace(Regex("^#+\\s*"), "")
        if (l.startsWith("- ")) l = "• " + l.removePrefix("- ")
        l
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}

/** Finds the newest release of the repository. [get] fetches a text from an address and is the only thing that touches the network. */
class UpdateChecker(private val get: (String) -> String) {
    /** The latest release, or null when it has no keyboard file that comes from this repository. */
    fun latest(): Release? = parse(get(Repo.LATEST))

    fun parse(json: String): Release? {
        val j = JSONObject(json)
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        val tag = j.getString("tag_name")
        if (!Regex("[A-Za-z0-9._-]{1,40}").matches(tag) || tag.contains("..")) return null
        val assets = j.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null; var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name"); val url = a.optString("browser_download_url")
            if (!url.startsWith(Repo.DOWNLOADS + tag + "/")) continue      // only files of this very release of this repository
            if (name.endsWith(".apk") && name.startsWith("MetroWinKeyboard") && apk == null) apk = a
            if (name == "SHA256SUMS") sums = url
        }
        val file = apk ?: return null
        return Release(
            tag, tag.removePrefix("v"), j.optString("published_at").take(10), j.optString("body"),
            file.getString("browser_download_url"), file.getString("name"), file.optLong("size"), sums,
        )
    }

    /** [latest] if it is newer than [installed], with the notes of the changelog (or the release text when there are none). */
    fun newerThan(installed: String): Release? {
        val r = latest() ?: return null
        if (!Versions.isNewer(r.version, installed)) return null
        val notes = runCatching { Changelog.since(get(Repo.RAW + r.tag + "/CHANGELOG.md"), installed, r.version) }.getOrDefault("").ifBlank { Changelog.clean(r.notes) }
        return r.copy(notes = notes)
    }

    companion object {
        /** The checksum of [file] in a SHA256SUMS text ("hash  name" lines), or null. */
        fun checksumOf(sums: String, file: String): String? = sums.lineSequence().map { it.trim().split(Regex("\\s+"), 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == file }?.get(0)?.lowercase()
    }
}
