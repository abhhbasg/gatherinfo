package com.metrowin.keyboard.gif

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.metrowin.keyboard.Prefs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Finds GIFs in the source the user chose and prepares one for sending. All the work is off the main thread. */
class GifRepository(private val context: Context, private val prefs: Prefs) {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** What the user must still do before this source can work, or null when it is ready. */
    fun problem(): GifProblem? = when (GifSourceType.from(prefs.gifSource)) {
        GifSourceType.GIPHY, GifSourceType.TENOR -> if (prefs.gifApiKey.isBlank()) GifProblem.NEEDS_KEY else null
        GifSourceType.FOLDER -> if (prefs.gifFolder.isBlank()) GifProblem.NEEDS_FOLDER else null
        GifSourceType.CUSTOM -> if (!prefs.gifCustomUrl.startsWith("https://")) GifProblem.NEEDS_URL else null
    }

    fun load(query: String?, language: String, done: (Result<List<GifItem>>) -> Unit) {
        io.execute {
            val r = runCatching { fetch(query?.trim().orEmpty(), language) }
            main.post { done(r) }
        }
    }

    private fun fetch(query: String, language: String): List<GifItem> = when (GifSourceType.from(prefs.gifSource)) {
        GifSourceType.GIPHY -> GifSources.parseGiphy(Net.getString(GifSources.giphyUrl(prefs.gifApiKey, query, LIMIT, language)))
        GifSourceType.TENOR -> GifSources.parseTenor(Net.getString(GifSources.tenorUrl(prefs.gifApiKey, query, LIMIT, language)))
        GifSourceType.CUSTOM -> GifSources.parseCustom(
            Net.getString(GifSources.customUrl(prefs.gifCustomUrl, prefs.gifApiKey, query, LIMIT, language)),
            prefs.gifCustomList, prefs.gifCustomPreview, prefs.gifCustomFull,
        )
        GifSourceType.FOLDER -> folder(query)
    }

    /** The .gif files of the folder the user picked, filtered by file name. */
    private fun folder(query: String): List<GifItem> {
        val tree = Uri.parse(prefs.gifFolder)
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<GifItem>()
        context.contentResolver.query(
            parent,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && out.size < 300) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                if (!(mime == "image/gif" || name.endsWith(".gif", true))) continue
                if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString()
                out += GifItem(uri, uri, name.removeSuffix(".gif").removeSuffix(".GIF"))
            }
        }
        return out
    }

    /** Copies the GIF to the share folder of the app (downloading it if it is online) and gives [done] the file. */
    fun prepare(item: GifItem, done: (Result<File>) -> Unit) {
        io.execute {
            val r = runCatching {
                val dir = File(context.cacheDir, "gif_share").apply { mkdirs() }
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
                val name = MessageDigest.getInstance("SHA-1").digest(item.fullUrl.toByteArray()).joinToString("") { "%02x".format(it) } + ".gif"
                val f = File(dir, name)
                if (!f.exists()) {
                    if (item.fullUrl.startsWith("https://")) Net.download(item.fullUrl, f)
                    else context.contentResolver.openInputStream(Uri.parse(item.fullUrl))!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
                f
            }
            main.post { done(r) }
        }
    }

    fun shutdown() = io.shutdownNow()

    enum class GifProblem { NEEDS_KEY, NEEDS_FOLDER, NEEDS_URL }

    companion object { const val LIMIT = 30 }
}
