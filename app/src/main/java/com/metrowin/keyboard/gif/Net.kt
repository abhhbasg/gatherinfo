package com.metrowin.keyboard.gif

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The only network code of the keyboard: https GET with timeouts and a size limit. Used for GIFs, and only when the user opens them. */
object Net {
    const val MAX_JSON = 2 * 1024 * 1024
    const val MAX_GIF = 15 * 1024 * 1024

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        if (headers.isEmpty()) String(getBytes(url, MAX_JSON), Charsets.UTF_8) else request("GET", url, null, null, headers)

    /** A request with a body and headers (translation services). The answer is read as text, with the same limits as the rest. */
    fun request(method: String, url: String, body: ByteArray?, contentType: String?, headers: Map<String, String> = emptyMap()): String {
        require(url.startsWith("https://")) { "Only https addresses are used" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "MetroWinKeyboard")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { copyLimited(it, out, MAX_JSON) }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    fun getBytes(url: String, limit: Int): ByteArray {
        open(url).use { s ->
            val out = ByteArrayOutputStream()
            copyLimited(s, out, limit)
            return out.toByteArray()
        }
    }

    fun download(url: String, to: File, limit: Int = MAX_GIF) {
        val tmp = File(to.path + ".part")
        try {
            open(url).use { s -> tmp.outputStream().use { copyLimited(s, it, limit) } }
            if (!tmp.renameTo(to)) throw IOException("rename failed")
        } finally { tmp.delete() }
    }

    private fun open(url: String): InputStream {
        require(url.startsWith("https://")) { "Only https addresses are used" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MetroWinKeyboard")
        if (c.responseCode !in 200..299) { c.disconnect(); throw IOException("HTTP ${c.responseCode}") }
        return c.inputStream
    }

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, limit: Int) {
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Too large")
            out.write(buf, 0, n)
        }
    }
}
