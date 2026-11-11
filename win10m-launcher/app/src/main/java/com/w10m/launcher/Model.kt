package com.w10m.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

enum class TileSize(val w: Int, val h: Int) {
    SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2);

    /** W10M resize cycle: medium -> small -> wide -> medium. */
    fun next() = when (this) { MEDIUM -> SMALL; SMALL -> WIDE; WIDE -> MEDIUM }
}

enum class TileKind { APP, CALENDAR, WEB }

data class TileSpec(
    val id: String,
    val kind: TileKind,
    val component: String?,
    var size: TileSize,
    /** Title for WEB tiles (component holds the URL). */
    val label: String? = null,
) {
    fun toJson() = JSONObject().put("id", id).put("kind", kind.name)
        .put("component", component ?: "").put("size", size.name).put("label", label ?: "")

    companion object {
        fun fromJson(o: JSONObject) = TileSpec(
            o.getString("id"), TileKind.valueOf(o.getString("kind")),
            o.optString("component").ifEmpty { null }, TileSize.valueOf(o.getString("size")),
            o.optString("label").ifEmpty { null },
        )
    }
}

class AppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Drawable,
    /** Monochrome layer of an adaptive icon (Android 13+), drawn white on the tile like MDL2 glyphs. */
    val mono: Drawable?,
    val installTime: Long,
    /** Icon-pack glyph (brand logo or line glyph), or null when the pack has none for this app. */
    val glyph: Glyph? = null,
) {
    val key: String get() = component.flattenToString()
}

enum class Background { SOLID, BEHIND_TILES, ON_TILES }

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("w10m", Context.MODE_PRIVATE)
    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()
    var accent: Int
        get() = sp.getInt("accent", Metro.DEFAULT_ACCENT)
        set(v) = sp.edit().putInt("accent", v).apply()
    var moreTiles: Boolean
        get() = sp.getBoolean("more", false)
        set(v) = sp.edit().putBoolean("more", v).apply()
    var background: Background
        get() = Background.valueOf(sp.getString("bg", Background.SOLID.name)!!)
        set(v) = sp.edit().putString("bg", v.name).apply()
    /** Tile transparency 0..100 (only for BEHIND_TILES). */
    var transparency: Int
        get() = sp.getInt("transp", 20)
        set(v) = sp.edit().putInt("transp", v).apply()
    var monoIcons: Boolean
        get() = sp.getBoolean("mono", true)
        set(v) = sp.edit().putBoolean("mono", v).apply()

    /** Show the Windows Phone Back / Start / Search keys at the bottom of the launcher's screens. */
    var navKeys: Boolean
        get() = sp.getBoolean("navkeys", true)
        set(v) = sp.edit().putBoolean("navkeys", v).apply()
    /** Replace the system status bar with the W10M one (and enable the Action Center) inside the launcher. */
    var statusBar: Boolean
        get() = sp.getBoolean("statusbar", true)
        set(v) = sp.edit().putBoolean("statusbar", v).apply()
    /** Apps hidden from the app list (component keys). */
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()
    var photosAsked: Boolean
        get() = sp.getBoolean("photosasked", false)
        set(v) = sp.edit().putBoolean("photosasked", v).apply()
    var iconPack: Boolean
        get() = sp.getBoolean("iconpack", true)
        set(v) = sp.edit().putBoolean("iconpack", v).apply()
    var brandColors: Boolean
        get() = sp.getBoolean("brandcolors", true)
        set(v) = sp.edit().putBoolean("brandcolors", v).apply()
    /** Bumped whenever another screen (e.g. the browser) edits the pinned tiles. */
    var tilesStamp: Int
        get() = sp.getInt("stamp", 0)
        set(v) = sp.edit().putInt("stamp", v).apply()

    fun palette() = Palette(dark, accent)

    fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    fun putStr(key: String, v: String) = sp.edit().putString(key, v).apply()

    fun pinWeb(url: String, title: String) {
        val t = loadTiles() ?: mutableListOf()
        if (t.none { it.kind == TileKind.WEB && it.component == url }) {
            t += TileSpec("w:$url", TileKind.WEB, url, TileSize.MEDIUM, title)
            saveTiles(t); tilesStamp += 1
        }
    }

    fun loadTiles(): MutableList<TileSpec>? {
        val s = sp.getString("tiles", null) ?: return null
        val a = JSONArray(s)
        return MutableList(a.length()) { TileSpec.fromJson(a.getJSONObject(it)) }
    }

    fun saveTiles(t: List<TileSpec>) {
        val a = JSONArray(); t.forEach { a.put(it.toJson()) }
        sp.edit().putString("tiles", a.toString()).apply()
    }
}

object AppRepo {
    fun load(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(Locale.getDefault())
        return pm.queryIntentActivities(i, 0)
            .map { ri ->
                val ai = ri.activityInfo
                val icon = ri.loadIcon(pm)
                val mono = if (Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable) icon.monochrome else null
                val t = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (_: Exception) { 0L }
                AppEntry(ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString(), icon, mono, t,
                    IconPack.forApp(c, ai.packageName, ai.name))
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** First-run Start layout, mirroring the W10M out-of-box screen (phone, people, messaging, ...). */
    fun defaultTiles(c: Context, apps: List<AppEntry>): MutableList<TileSpec> {
        val pm = c.packageManager
        val byPkg = apps.groupBy { it.component.packageName }
        fun resolve(intent: Intent): String? {
            val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
            val pkg = ri.activityInfo?.packageName ?: return null
            if (pkg == c.packageName) return null
            return byPkg[pkg]?.firstOrNull()?.key
        }
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL) to TileSize.MEDIUM,
            Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people")) to TileSize.MEDIUM,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to TileSize.MEDIUM,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) to TileSize.MEDIUM,
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to TileSize.SMALL,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL) to TileSize.MEDIUM,
        )
        val out = mutableListOf<TileSpec>()
        val used = HashSet<String>()
        fun own(cls: String, size: TileSize) {
            val k = apps.firstOrNull { it.component.packageName == c.packageName && it.component.className.endsWith(cls) }?.key ?: return
            if (used.add(k)) out += TileSpec("a:$k", TileKind.APP, k, size)
        }
        // The built-in phone, people, messaging and browser lead the layout, as on W10M.
        for (cls in listOf("DialerActivity", "PeopleActivity", "MessagingActivity", "BrowserActivity")) own(cls, TileSize.MEDIUM)
        own("PhotosActivity", TileSize.WIDE)
        own("SystemSettingsActivity", TileSize.SMALL)
        own("ClockActivity", TileSize.SMALL)
        own("CalculatorActivity", TileSize.SMALL)
        own("MusicActivity", TileSize.MEDIUM)
        for ((intent, size) in wanted) {
            val k = try { resolve(intent) } catch (_: Exception) { null } ?: continue
            if (used.add(k)) out += TileSpec("a:$k", TileKind.APP, k, size)
        }
        // Calendar live tile sits in the wide slot like on W10M.
        out.add(minOf(4, out.size), TileSpec("calendar", TileKind.CALENDAR, null, TileSize.WIDE))
        for (a in apps) {
            if (out.size >= 14) break
            if (used.add(a.key)) out += TileSpec("a:${a.key}", TileKind.APP, a.key, TileSize.MEDIUM)
        }
        return out
    }
}

/**
 * Ordered first-fit packer on a [cols]-wide grid of small cells.
 * Medium and wide tiles snap to even columns, just like W10M (they live on the medium grid).
 * Tiles keep their order: each tile is placed at the first free slot at or after the previous tile's row.
 */
object Packer {
    data class Cell(val col: Int, val row: Int)

    fun pack(tiles: List<TileSpec>, cols: Int): List<Cell> {
        val occ = ArrayList<BooleanArray>()
        fun free(c: Int, r: Int, w: Int, h: Int): Boolean {
            if (c + w > cols) return false
            for (y in r until r + h) {
                if (y >= occ.size) continue
                for (x in c until c + w) if (occ[y][x]) return false
            }
            return true
        }
        fun mark(c: Int, r: Int, w: Int, h: Int) {
            while (occ.size < r + h) occ.add(BooleanArray(cols))
            for (y in r until r + h) for (x in c until c + w) occ[y][x] = true
        }
        val res = ArrayList<Cell>(tiles.size)
        var minRow = 0
        for (t in tiles) {
            val w = minOf(t.size.w, cols); val h = t.size.h
            val step = if (w >= 2) 2 else 1
            var placed: Cell? = null
            var r = minRow
            while (placed == null) {
                var c = 0
                while (c + w <= cols) {
                    if (free(c, r, w, h)) { placed = Cell(c, r); break }
                    c += step
                }
                if (placed == null) r++
            }
            mark(placed.col, placed.row, w, h)
            res += placed
            minRow = placed.row
        }
        return res
    }
}
