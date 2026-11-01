package com.metrowin.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.metrowin.keyboard.gif.Net
import java.util.concurrent.Executors

/** Small pictures for the panels: decoded smaller than the screen, cached, loaded off the main thread. GIFs show their first frame. */
class ImageLoader(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, targetPx: Int, done: (Bitmap?) -> Unit) {
        val key = "$url@$targetPx"
        cache.get(key)?.let { done(it); return }
        io.execute {
            val bmp = runCatching { decode(url, targetPx) }.getOrNull()
            if (bmp != null) cache.put(key, bmp)
            main.post { done(bmp) }
        }
    }

    private fun decode(url: String, target: Int): Bitmap? {
        val bytes = if (url.startsWith("https://")) Net.getBytes(url, 6 * 1024 * 1024)
        else context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun clear() = cache.evictAll()
    fun shutdown() { io.shutdownNow(); cache.evictAll() }
}
