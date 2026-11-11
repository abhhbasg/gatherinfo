package com.w10m.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * White tile glyph on a 24x24 grid. Brand glyphs (Simple Icons, CC0) are filled; the project's own
 * line glyphs are stroked. [brandColor] is the tile colour for brand apps, or null to use the accent.
 */
class Glyph(val name: String, private val data: String, val stroke: Boolean, val brandColor: Int?) {
    private val path: Path by lazy {
        try { PathParser.createPathFromPathData(data) } catch (_: Exception) { Path() }
    }
    private val scaled = Path()
    private val m = Matrix()

    /** Draws the glyph into a [size] square whose top-left is ([x],[y]). */
    fun draw(c: Canvas, x: Float, y: Float, size: Float, paint: Paint, color: Int = Color.WHITE) {
        val s = size / 24f
        m.setScale(s, s); m.postTranslate(x, y)
        path.transform(m, scaled)
        paint.color = color
        if (stroke) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.25f * s
            paint.strokeCap = Paint.Cap.SQUARE
            paint.strokeJoin = Paint.Join.MITER
        } else paint.style = Paint.Style.FILL
        c.drawPath(scaled, paint)
    }
}

object IconPack {
    private var brands: Map<String, Glyph> = emptyMap()
    private var glyphs: Map<String, Glyph> = emptyMap()
    private var byPackage: Map<String, String> = emptyMap()
    private var loaded = false

    /** Package-name keywords for stock apps of any vendor, checked in order. */
    private val KEYWORDS = listOf(
        "dialer" to "phone", "incallui" to "phone", ".phone" to "phone",
        "contacts" to "people",
        "mms" to "message", "messaging" to "message", ".sms" to "message",
        "camera" to "camera",
        "settings" to "settings",
        "gallery" to "photos", "photos" to "photos", "album" to "photos",
        "deskclock" to "clock", "clock" to "clock", "alarm" to "clock",
        "calculator" to "calculator",
        "calendar" to "calendar",
        "browser" to "browser",
        "music" to "music", "player" to "music",
        "filemanager" to "folder", "fileexplorer" to "folder", "documentsui" to "folder", ".files" to "folder", "myfiles" to "folder",
        "email" to "mail", ".mail" to "mail",
        "maps" to "map", ".map" to "map",
        "market" to "store", "appstore" to "store", "galaxystore" to "store",
        "weather" to "weather",
        "notes" to "notes", "notepad" to "notes", "memo" to "notes",
        "video" to "video",
        "recorder" to "mic",
        "download" to "download",
        "compass" to "compass",
        "security" to "shield", "safecenter" to "shield",
        "theme" to "brush",
        "wallet" to "wallet",
        "gamecenter" to "game", "game" to "game",
        "health" to "health",
        "reader" to "book",
        "search" to "search",
    )

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val am = c.assets
            brands = am.open("icons/brands.tsv").bufferedReader().readLines().mapNotNull { l ->
                val p = l.split('\t'); if (p.size < 3) null else
                    p[0] to Glyph(p[0], p[2], false, (0xFF000000.toInt() or p[1].toInt(16)))
            }.toMap()
            glyphs = am.open("icons/glyphs.tsv").bufferedReader().readLines().mapNotNull { l ->
                val p = l.split('\t'); if (p.size < 2) null else p[0] to Glyph(p[0], p[1], true, null)
            }.toMap()
            byPackage = am.open("icons/map.tsv").bufferedReader().readLines().mapNotNull { l ->
                val p = l.split('\t'); if (p.size < 2) null else p[0] to p[1]
            }.toMap()
        } catch (_: Exception) {}
    }

    fun glyph(name: String): Glyph? = glyphs[name]

    /** Glyph for an app: own activities, then the package map, then stock-app keywords. */
    fun forApp(c: Context, pkg: String, cls: String): Glyph? {
        load(c)
        if (pkg == c.packageName) return glyphs[when {
            cls.endsWith("BrowserActivity") -> "browser"
            cls.endsWith("DialerActivity") -> "phone"
            else -> "settings"
        }]
        byPackage[pkg]?.let { id -> return if (id.startsWith("@")) glyphs[id.substring(1)] else brands[id] }
        val lower = pkg.lowercase()
        for ((k, g) in KEYWORDS) if (lower.contains(k)) return glyphs[g]
        return null
    }

    /** Tile colour for a brand glyph; very light brand colours fall back to the accent (white glyph must stay readable). */
    fun tileColor(g: Glyph?, accent: Int, useBrand: Boolean): Int {
        val b = g?.brandColor ?: return accent
        if (!useBrand) return accent
        val lum = (0.299 * Color.red(b) + 0.587 * Color.green(b) + 0.114 * Color.blue(b)) / 255.0
        return if (lum > 0.72) accent else if (lum < 0.08) 0xFF1F1F1F.toInt() else b
    }
}
