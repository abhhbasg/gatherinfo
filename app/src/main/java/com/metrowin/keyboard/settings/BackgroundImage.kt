package com.metrowin.keyboard.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.metrowin.keyboard.K
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.theme.ColorMath
import java.io.File
import java.nio.ByteBuffer

/** The picture the user chose for the keyboard: made smaller, saved on the phone, and its main colour picked as an accent. */
object BackgroundImage {
    private const val MAX_SIDE = 1280
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun file(c: Context) = File(c.filesDir, "background.jpg")

    /** Returns true if the picture was saved. Does I/O: call off the main thread. */
    fun save(c: Context, uri: Uri): Boolean = runCatching {
        val bytes = c.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) return false }
            out.toByteArray()
        } ?: return false
        val bmp = decode(bytes) ?: return false
        file(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val small = Bitmap.createScaledBitmap(bmp, 48, 48, true)
        val px = IntArray(48 * 48)
        small.getPixels(px, 0, 48, 0, 0, 48, 48)
        val accent = ColorMath.asAccent(ColorMath.dominant(px))
        small.recycle(); bmp.recycle()
        val p = KeyboardApp.of(c).prefs
        p.sp.edit().putInt(K.PICTURE_ACCENT, accent).putBoolean(K.IMAGE_BACKGROUND, true).apply()
        true
    }.getOrDefault(false)

    fun remove(c: Context) {
        file(c).delete()
        KeyboardApp.of(c).prefs.sp.edit().putBoolean(K.IMAGE_BACKGROUND, false).putInt(K.PICTURE_ACCENT, 0).apply()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also turns the picture the right way up using its EXIF data.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
            }
        } else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
