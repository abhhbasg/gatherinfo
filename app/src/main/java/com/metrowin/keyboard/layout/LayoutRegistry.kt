package com.metrowin.keyboard.layout

import android.content.Context
import java.io.File

/** The languages the keyboard knows: the ones inside the app and the ones the user imported. */
class LayoutRegistry(private val context: Context) {
    private val dir get() = File(context.filesDir, "layouts").apply { mkdirs() }
    @Volatile private var cache: Map<String, LanguageLayout>? = null

    fun all(): Map<String, LanguageLayout> = cache ?: load().also { cache = it }

    fun get(id: String): LanguageLayout? = all()[id]

    fun fallback(): LanguageLayout = all()["en"] ?: all().values.first()

    /** The layout for a system language tag such as "it" or "pt-BR": exact id, then language, then the fallback. */
    fun forLanguage(tag: String): LanguageLayout {
        val lang = tag.substringBefore('-').substringBefore('_').lowercase()
        return all()[tag.lowercase()] ?: all()[lang] ?: all().values.firstOrNull { it.locale.language == lang } ?: fallback()
    }

    fun invalidate() { cache = null }

    /** Saves an imported layout. Returns it, or null if the text is not a valid layout. */
    fun import(text: String): LanguageLayout? {
        val layout = LanguageLayout.parse(text, builtIn = false) ?: return null
        File(dir, "${layout.id}.mwl").writeText(text)
        invalidate()
        return layout
    }

    fun remove(id: String): Boolean {
        val ok = File(dir, "$id.mwl").delete()
        invalidate()
        return ok
    }

    private fun load(): Map<String, LanguageLayout> {
        val out = LinkedHashMap<String, LanguageLayout>()
        context.assets.list("layouts").orEmpty().sorted().forEach { name ->
            runCatching { context.assets.open("layouts/$name").bufferedReader().use { it.readText() } }
                .getOrNull()?.let { LanguageLayout.parse(it, builtIn = true) }?.let { out[it.id] = it }
        }
        dir.listFiles { f -> f.extension == "mwl" }?.sortedBy { it.name }?.forEach { f ->
            runCatching { f.readText() }.getOrNull()?.let { LanguageLayout.parse(it, builtIn = false) }?.let { out[it.id] = it }
        }
        return out
    }
}
