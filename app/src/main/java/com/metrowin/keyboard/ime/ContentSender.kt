package com.metrowin.keyboard.ime

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/** Sends a picture or a GIF into the app being typed in, the way keyboards do (rich content). */
object ContentSender {
    fun authority(c: Context) = "${c.packageName}.files"

    fun supports(info: EditorInfo?, mime: String): Boolean {
        if (info == null) return false
        return EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
    }

    /** True if the app accepted it. */
    fun send(c: Context, ic: InputConnection?, info: EditorInfo?, file: File, mime: String, label: String): Boolean {
        if (ic == null || info == null || !supports(info, mime)) return false
        val uri = FileProvider.getUriForFile(c, authority(c), file)
        val content = InputContentInfoCompat(uri, ClipDescription(label, arrayOf(mime)), null)
        var flags = 0
        if (Build.VERSION.SDK_INT >= 25) flags = flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        else info.packageName?.let { c.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return InputConnectionCompat.commitContent(ic, info, content, flags, null)
    }

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "gif" -> "image/gif"; "png" -> "image/png"; "webp" -> "image/webp"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/*"
    }
}
