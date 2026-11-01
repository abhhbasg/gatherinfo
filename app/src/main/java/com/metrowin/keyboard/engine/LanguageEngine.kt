package com.metrowin.keyboard.engine

import android.content.Context
import com.metrowin.keyboard.layout.LanguageLayout
import java.io.File
import java.io.InputStream

/** Everything the keyboard needs for one language: its word list, what the user has taught it, the blocklist, and the engines on top. */
class LanguageEngine(
    val layout: LanguageLayout,
    val dictionary: WordDictionary,
    val user: UserModel,
    val blocklist: Blocklist,
    private val userFile: File,
) {
    val suggester = Suggester(dictionary, user, blocklist, layout.locale)
    val swipe = SwipeDecoder(dictionary, user) { blocklist.contains(it) }

    /** Writes what was learned to the phone's storage, if anything changed. */
    fun save() {
        if (!user.dirty) return
        runCatching {
            userFile.parentFile?.mkdirs()
            val tmp = File(userFile.path + ".tmp")
            tmp.bufferedWriter().use { user.write(it) }
            tmp.renameTo(userFile)
        }
    }

    companion object {
        fun dictionaryFile(c: Context, id: String) = File(File(c.filesDir, "dictionaries").apply { mkdirs() }, "$id.txt")
        fun userFile(c: Context, id: String) = File(File(c.filesDir, "user").apply { mkdirs() }, "$id.txt")

        fun load(c: Context, layout: LanguageLayout, customBlocked: String): LanguageEngine {
            val dictId = layout.dictionaryId
            val imported = dictionaryFile(c, dictId)
            val dictionary = runCatching {
                val reader = if (imported.exists()) imported.bufferedReader() else c.assets.open("dictionaries/$dictId.txt").bufferedReader()
                reader.use { WordDictionary.parse(it, layout.locale) }
            }.getOrDefault(WordDictionary.EMPTY)
            val blocklist = Blocklist()
            runCatching { c.assets.open("blocklists/${layout.locale.language}.txt").bufferedReader().use { blocklist.add(Blocklist.parse(it.readText())) } }
            blocklist.add(Blocklist.parse(customBlocked))
            val file = userFile(c, layout.id)
            val user = UserModel()
            if (file.exists()) runCatching { file.bufferedReader().use { user.read(it) } }
            return LanguageEngine(layout, dictionary, user, blocklist, file)
        }

        /** Saves a word list for [dictId]. Returns the number of words, or 0 if the file held no words (nothing is saved then). */
        fun importDictionary(c: Context, dictId: String, locale: java.util.Locale, input: InputStream): Int {
            val parsed = input.bufferedReader().use { WordDictionary.parse(it, locale) }
            if (parsed.size == 0) return 0
            // Save the text again in the simple "word<TAB>count" shape, so any input format ends up the same on disk.
            val out = File(dictionaryFile(c, dictId).path + ".tmp")
            out.bufferedWriter().use { w -> for (i in 0 until parsed.size) w.append(parsed.wordAt(i)).append('\t').append(parsed.freqAt(i).toString()).append('\n') }
            out.renameTo(dictionaryFile(c, dictId))
            return parsed.size
        }

        fun removeImportedDictionary(c: Context, dictId: String) = dictionaryFile(c, dictId).delete()
    }
}
