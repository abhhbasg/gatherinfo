package com.metrowin.keyboard.translate

import android.os.Handler
import android.os.Looper
import com.metrowin.keyboard.Prefs
import com.metrowin.keyboard.gif.Net
import java.util.concurrent.Executors

/** Sends text to the translation service the user chose, only when they ask, and gives back the result on the main thread. */
class Translator(private val prefs: Prefs) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    /** What the user must still set up for the chosen service, or null. */
    fun problem(): String? {
        val p = Provider.from(prefs.translateProvider)
        if (p.needsKey && prefs.translateKey.isBlank()) return "key"
        if (p.needsUrl && !prefs.translateUrl.startsWith("https://")) return "url"
        return null
    }

    fun translate(text: String, source: String, target: String, done: (Result<Translated>) -> Unit) {
        val mine = ++generation
        val p = Provider.from(prefs.translateProvider)
        val key = prefs.translateKey; val url = prefs.translateUrl
        io.execute {
            val r = runCatching {
                var detected: String? = null
                val sb = StringBuilder()
                // MyMemory cannot detect the language: use the one the caller passed.
                for (piece in Providers.chunk(text, Providers.chunkSize(p))) {
                    if (mine != generation) throw InterruptedException()
                    val req = Providers.request(p, piece, source, target, key, url)
                    val body = Net.request(req.method, req.url, req.body, req.contentType, req.headers)
                    val t = Providers.parse(p, body)
                    if (detected == null) detected = t.detectedLanguage
                    sb.append(t.text)
                }
                Translated(sb.toString(), detected)
            }
            if (mine == generation) main.post { done(r) }
        }
    }

    /** How much of the plan is left, or null when the chosen service cannot tell. Runs off the main thread; [done] on the main one. */
    fun usage(done: (Result<Usage>?) -> Unit) {
        val p = Provider.from(prefs.translateProvider)
        val key = prefs.translateKey
        val req = Providers.usageRequest(p, key)
        if (req == null || key.isBlank()) { done(null); return }
        io.execute {
            val r = runCatching { Providers.parseUsage(Net.request(req.method, req.url, req.body, req.contentType, req.headers)) }
            main.post { done(r) }
        }
    }

    fun cancel() { generation++ }
    fun shutdown() { generation++; io.shutdownNow() }
}
