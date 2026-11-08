package com.metrowin.keyboard.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The network side of the updater: https GET to this repository's addresses only, redirects followed by hand and only to GitHub's own
 * hosts, with timeouts and size limits. Nothing is sent but the request; no identifier, no cookie.
 */
/** The repository has no release yet (or the address is gone): not a network failure, just nothing to install. */
class NotFound : IOException("HTTP 404")

object UpdateNet {
    private const val MAX_TEXT = 1024 * 1024
    const val MAX_APK = 100L * 1024 * 1024

    private fun open(first: String): HttpURLConnection {
        require(Repo.isOurs(first)) { "Not an address of this repository" }
        var url = first
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "MetroWinKeyboard")
            c.setRequestProperty("Accept", "application/vnd.github+json, */*")
            when (val code = c.responseCode) {
                in 200..299 -> return c
                301, 302, 303, 307, 308 -> {
                    val next = c.getHeaderField("Location") ?: throw IOException("Redirect without a place")
                    c.disconnect()
                    val target = URL(URL(url), next)
                    if (target.protocol != "https" || target.host !in Repo.REDIRECT_HOSTS) throw IOException("Redirect to another host refused")
                    url = target.toString()
                }
                404 -> { c.disconnect(); throw NotFound() }
                else -> { c.disconnect(); throw IOException("HTTP $code") }
            }
        }
        throw IOException("Too many redirects")
    }

    fun getString(url: String): String {
        val c = open(url)
        try {
            val out = ByteArrayOutputStream()
            c.inputStream.use { copy(it, out, MAX_TEXT.toLong(), null) }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    /** Downloads [url] to [to] (through a .part file), reporting the bytes so far; returns the SHA-256 of what was written. */
    fun download(url: String, to: File, limit: Long = MAX_APK, progress: (Long) -> Unit): String {
        val tmp = File(to.path + ".part")
        val c = open(url)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out -> c.inputStream.use { copy(it, out, limit, { b, n -> md.update(b, 0, n) }, progress) } }
            if (!tmp.renameTo(to)) throw IOException("rename failed")
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally { c.disconnect(); tmp.delete() }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, limit: Long, each: ((ByteArray, Int) -> Unit)?, progress: ((Long) -> Unit)? = null) {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Too large")
            each?.invoke(buf, n)
            out.write(buf, 0, n)
            progress?.invoke(total)
        }
    }
}
