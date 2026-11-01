package com.metrowin.keyboard.clipboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class ClipItem(
    val id: String,
    val image: Boolean,
    val text: String?,
    val file: String?,
    var time: Long,
    var pinned: Boolean,
)

/**
 * The clipboard history on the phone: copied text and pictures, newest first, pinned ones on top and never dropped. The number
 * of items kept is the user's choice; when it is exceeded the oldest unpinned items go, with their picture files.
 * No Android classes, so it is tested on the JVM.
 */
class ClipboardStore(private val dir: File, private val maxItems: () -> Int, private val now: () -> Long = System::currentTimeMillis) {
    private val items = ArrayList<ClipItem>()
    private val index = File(dir, "clips.json")
    private val listeners = ArrayList<() -> Unit>()

    init {
        dir.mkdirs()
        load()
    }

    @Synchronized fun all(): List<ClipItem> = items.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })

    @Synchronized fun size() = items.size

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() { save(); synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun imageFile(item: ClipItem): File? = item.file?.let { File(dir, it) }

    /** Adds [text]; if the same text is already there it moves to the top instead of being doubled. */
    fun addText(text: String): Boolean {
        val t = text.take(MAX_TEXT)
        if (t.isBlank()) return false
        synchronized(this) {
            val same = items.firstOrNull { !it.image && it.text == t }
            if (same != null) same.time = now() else items += ClipItem(UUID.randomUUID().toString(), false, t, null, now(), false)
            trim()
        }
        changed()
        return true
    }

    /** Adds a picture read from [source]. Returns false when it is empty or larger than [MAX_IMAGE] bytes. */
    fun addImage(source: InputStream, extension: String): Boolean {
        val bytes = source.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IMAGE) return false
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) return false
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "img" }.take(5)
        val name = "${UUID.randomUUID()}.$ext"
        File(dir, name).writeBytes(bytes)
        synchronized(this) {
            items += ClipItem(UUID.randomUUID().toString(), true, null, name, now(), false)
            trim()
        }
        changed()
        return true
    }

    fun togglePin(id: String) {
        synchronized(this) { items.firstOrNull { it.id == id }?.let { it.pinned = !it.pinned } }
        changed()
    }

    fun remove(id: String) {
        synchronized(this) {
            val it = items.firstOrNull { c -> c.id == id } ?: return
            deleteFile(it); items.remove(it)
        }
        changed()
    }

    /** Removes everything that is not pinned (or everything, when [includePinned]). */
    fun clear(includePinned: Boolean = false) {
        synchronized(this) {
            val gone = items.filter { includePinned || !it.pinned }
            gone.forEach { deleteFile(it) }
            items.removeAll(gone.toSet())
        }
        changed()
    }

    /** Applies a lower limit at once. */
    fun enforceLimit() { synchronized(this) { trim() }; changed() }

    private fun trim() {
        val limit = maxItems().coerceAtLeast(1)
        val unpinned = items.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > limit) unpinned.drop(limit).forEach { deleteFile(it); items.remove(it) }
    }

    private fun deleteFile(item: ClipItem) { item.file?.let { File(dir, it).delete() } }

    private fun save() {
        val arr = JSONArray()
        synchronized(this) {
            for (i in items) arr.put(JSONObject().apply {
                put("id", i.id); put("image", i.image); put("time", i.time); put("pinned", i.pinned)
                i.text?.let { put("text", it) }; i.file?.let { put("file", it) }
            })
        }
        runCatching { File(dir, "clips.json.tmp").also { it.writeText(arr.toString()) }.renameTo(index) }
    }

    private fun load() {
        runCatching {
            val arr = JSONArray(index.readText())
            for (n in 0 until arr.length()) {
                val o = arr.getJSONObject(n)
                val file = o.optString("file").ifEmpty { null }
                if (file != null && !File(dir, file).exists()) continue
                items += ClipItem(o.getString("id"), o.optBoolean("image"), o.optString("text").ifEmpty { null }, file, o.optLong("time"), o.optBoolean("pinned"))
            }
        }
    }

    companion object {
        const val MAX_TEXT = 20_000
        const val MAX_IMAGE = 8 * 1024 * 1024
    }
}
