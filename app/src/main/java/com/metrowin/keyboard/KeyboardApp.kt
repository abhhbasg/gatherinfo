package com.metrowin.keyboard

import android.app.Application
import android.content.Context
import com.metrowin.keyboard.clipboard.ClipboardStore
import com.metrowin.keyboard.engine.LanguageEngine
import com.metrowin.keyboard.layout.LayoutRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The things the input method, the settings and the services share. Created once with the process. */
class KeyboardApp : Application() {
    val prefs by lazy { Prefs(this) }
    val layouts by lazy { LayoutRegistry(this) }
    val clipboard by lazy { ClipboardStore(File(filesDir, "clipboard"), { prefs.clipboardMax }) }
    private val engines = ConcurrentHashMap<String, LanguageEngine>()

    override fun onCreate() {
        super.onCreate()
        runCatching { com.metrowin.keyboard.update.UpdateScheduler.sync(this) }
    }

    /** The engine of [id], loading its dictionary on first use. Call from a background thread: a big dictionary takes a moment. */
    fun engine(id: String): LanguageEngine = engines.getOrPut(id) {
        LanguageEngine.load(this, layouts.get(id) ?: layouts.fallback(), prefs.customBlocked)
    }

    fun cachedEngine(id: String): LanguageEngine? = engines[id]

    /** Forget loaded engines (after a dictionary import or a change of the blocked words); they are rebuilt on next use. */
    fun reloadEngines() { engines.values.forEach { it.save() }; engines.clear() }

    fun saveAll() = engines.values.forEach { it.save() }

    fun clearLearned() {
        engines.values.forEach { it.user.clear(); it.pinyin?.user?.clear() }
        File(filesDir, "user").listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun of(c: Context) = c.applicationContext as KeyboardApp
    }
}
