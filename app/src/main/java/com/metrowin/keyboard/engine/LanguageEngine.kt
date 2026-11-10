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
    seed: NextWordSeed = NextWordSeed.EMPTY,
    /** For a Pinyin layout: what turns the letters into Chinese words. */
    val pinyin: PinyinEngine? = null,
    private val pinyinFile: File? = null,
) {
    val suggester = Suggester(dictionary, user, blocklist, layout.locale, seed, Corrector(KeyGrid.of(layout)))
    val swipe = SwipeDecoder(dictionary, user) { blocklist.contains(it) }

    /** Writes what was learned to the phone's storage, if anything changed. */
    fun save() {
        if (user.dirty) write(userFile) { user.write(it) }
        if (pinyin != null && pinyinFile != null && pinyin.user.dirty) write(pinyinFile) { pinyin.user.write(it) }
    }

    private fun write(file: File, body: (java.io.Writer) -> Unit) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { body(it) }
            tmp.renameTo(file)
        }
    }

    companion object {
        /** The Chinese word lists already read: they come with the app and never change, so an engine that is rebuilt does not read them again. */
        private val pinyinWords = java.util.concurrent.ConcurrentHashMap<String, PinyinDictionary>()
        private val pinyinPairs = java.util.concurrent.ConcurrentHashMap<String, PinyinPairs>()
        private val pinyinUsers = java.util.concurrent.ConcurrentHashMap<String, PinyinUser>()

        fun dictionaryFile(c: Context, id: String) = File(File(c.filesDir, "dictionaries").apply { mkdirs() }, "$id.txt")
        fun userFile(c: Context, id: String) = File(File(c.filesDir, "user").apply { mkdirs() }, "$id.txt")
        /** What the user chose while typing Pinyin; next to the learned words, so the backup and "forget what was learned" cover it. */
        fun pinyinUserFile(c: Context, id: String) = File(File(c.filesDir, "user").apply { mkdirs() }, "$id.pinyin.txt")

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
            val seed = runCatching { c.assets.open("bigrams/$dictId.txt").bufferedReader().use { NextWordSeed.parse(it) } }.getOrDefault(NextWordSeed.EMPTY)
            if (!layout.pinyin) return LanguageEngine(layout, dictionary, user, blocklist, file, seed)
            val words = pinyinWords.getOrPut(dictId) {
                runCatching { c.assets.open("pinyin/$dictId.txt").bufferedReader().use { PinyinDictionary.parse(it) } }.getOrDefault(PinyinDictionary.EMPTY)
            }
            val pairs = pinyinPairs.getOrPut(dictId) {
                runCatching { c.assets.open("pinyin/$dictId.pairs").bufferedReader().use { PinyinPairs.parse(it) } }.getOrDefault(PinyinPairs.EMPTY)
            }
            // Full Pinyin and Shuangpin are two ways to type the same language: they learn into one file.
            val pinyinFile = pinyinUserFile(c, dictId)
            val picked = pinyinUsers.getOrPut(dictId) {
                PinyinUser().also { u -> if (pinyinFile.exists()) runCatching { pinyinFile.bufferedReader().use { u.read(it) } } }
            }
            return LanguageEngine(layout, dictionary, user, blocklist, file, seed, PinyinEngine(words, picked, pairs), pinyinFile)
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
