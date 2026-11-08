package com.metrowin.keyboard.backup

import android.content.Context
import com.metrowin.keyboard.KeyboardApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup of everything the app keeps, in one zip file the user stores wherever they want: the settings, what the keyboard learned,
 * the imported dictionaries and layouts, the clipboard history, the picture behind the keys and the custom key sound. It is what makes
 * moving to another phone, or to an app signed with another key (which needs a reinstall), cost nothing.
 *
 * The file holds the user's own data and their service keys (GIF, translation): it is theirs to keep private. A file that is read back
 * is checked before anything is touched: only known folders, no path that leaves them, and a limit on the size.
 */
object Backup {
    private const val FORMAT = "metrowin-keyboard-backup"
    private const val VERSION = 1
    private const val JSON = "backup.json"
    private const val FILES = "files/"
    private const val MAX_TOTAL = 256L shl 20
    private val roots = listOf("user", "dictionaries", "layouts", "clipboard", "background.jpg", "custom_sound")

    class Invalid(message: String) : IOException(message)

    /** The description is a few kilobytes; one that is more than a megabyte is not ours. */
    private fun readSmall(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf); if (n < 0) break
            if (out.size() + n > (1 shl 20)) throw Invalid("too big")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun write(c: Context, out: OutputStream) {
        KeyboardApp.of(c).saveAll()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON)); zip.write(describe(c).toString().toByteArray()); zip.closeEntry()
            for (root in roots) File(c.filesDir, root).walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(FILES + f.relativeTo(c.filesDir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun describe(c: Context): JSONObject {
        val prefs = JSONArray()
        for ((k, v) in KeyboardApp.of(c).prefs.sp.all) {
            val (type, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "set" to JSONArray(v.map { it.toString() })
                else -> continue
            }
            prefs.put(JSONObject().put("k", k).put("t", type).put("v", value))
        }
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty()
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("app", version).put("created", System.currentTimeMillis()).put("prefs", prefs)
    }

    /**
     * Puts a backup back. Nothing of the app is touched until the whole file has been read and found sound. Returns the number of
     * settings restored; throws [Invalid] for a file that is not one of ours.
     */
    fun restore(c: Context, input: InputStream): Int {
        val tmp = File(c.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var json: JSONObject? = null
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == JSON) { json = JSONObject(readSmall(zip).toString(Charsets.UTF_8)); continue }
                    if (!e.name.startsWith(FILES)) throw Invalid("unexpected entry")
                    val rel = e.name.removePrefix(FILES)
                    val parts = rel.split('/')
                    if (rel.startsWith("/") || '\\' in rel || parts.any { it.isEmpty() || it == ".." || it == "." } || parts[0] !in roots) throw Invalid("unexpected path")
                    val target = File(tmp, rel)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw Invalid("unexpected path")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf); if (n < 0) break
                            total += n; if (total > MAX_TOTAL) throw Invalid("too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val meta = json ?: throw Invalid("no description")
            if (meta.optString("format") != FORMAT || meta.optInt("version") !in 1..VERSION) throw Invalid("not a MetroWin Keyboard backup")
            val prefs = meta.getJSONArray("prefs")

            // Sound: now the files, then the settings.
            KeyboardApp.of(c).saveAll()
            for (root in roots) {
                val dest = File(c.filesDir, root)
                dest.deleteRecursively()
                val src = File(tmp, root)
                if (src.exists()) src.copyRecursively(dest, overwrite = true)
            }
            val edit = KeyboardApp.of(c).prefs.sp.edit().clear()
            for (i in 0 until prefs.length()) {
                val p = prefs.getJSONObject(i)
                val k = p.getString("k")
                when (p.getString("t")) {
                    "b" -> edit.putBoolean(k, p.getBoolean("v"))
                    "i" -> edit.putInt(k, p.getInt("v"))
                    "l" -> edit.putLong(k, p.getLong("v"))
                    "f" -> edit.putFloat(k, p.getDouble("v").toFloat())
                    "s" -> edit.putString(k, p.getString("v"))
                    "set" -> edit.putStringSet(k, p.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            edit.commit()
            KeyboardApp.of(c).reloadEngines()
            return prefs.length()
        } finally {
            tmp.deleteRecursively()
        }
    }
}
