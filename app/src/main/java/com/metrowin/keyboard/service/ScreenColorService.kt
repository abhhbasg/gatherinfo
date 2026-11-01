package com.metrowin.keyboard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.theme.ColorMath

/**
 * Optional. Lets the keyboard take the colour of the app under it (a chat, a website). While the keyboard is on screen, it looks at
 * a strip of the screen just above the keys, a few times a second at most, and keeps one colour from it. It reads no text and
 * no window content (the service asks for neither), keeps no picture, and sends nothing anywhere. Needs Android 11 or newer.
 */
class ScreenColorService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastShot = 0L
    private var pending = false

    override fun onServiceConnected() { AdaptiveColor.serviceRunning = true }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AdaptiveColor.serviceRunning = false
        AdaptiveColor.set(null)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!AdaptiveColor.keyboardShown || !KeyboardApp.of(this).prefs.adaptiveColor) return
        // Our own keyboard changing is not a change of the app below.
        if (event.packageName == packageName) return
        schedule()
    }

    private fun schedule() {
        if (pending) return
        pending = true
        val wait = (MIN_GAP_MS - (SystemClock.uptimeMillis() - lastShot)).coerceAtLeast(150L)
        main.postDelayed({ pending = false; capture() }, wait)
    }

    private fun capture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!AdaptiveColor.keyboardShown) return
        lastShot = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer: HardwareBuffer = result.hardwareBuffer
                try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                    val color = sampleAbove(hw, AdaptiveColor.keyboardTop)
                    hw.recycle()
                    AdaptiveColor.set(color)
                } finally { buffer.close() }
            }

            // A protected window (a bank, a password screen) refuses: keep the theme's own colours.
            override fun onFailure(errorCode: Int) { if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) AdaptiveColor.set(null) }
        })
    }

    /** The colour of a strip just above [top]: a few rows of pixels, averaged, ignoring the extremes. */
    private fun sampleAbove(hw: Bitmap, top: Int): Int? {
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        try {
            val bandH = (resources.displayMetrics.density * 56).toInt().coerceAtLeast(8)
            val bottom = if (top in 1..soft.height) top else (soft.height * 0.55f).toInt()
            val y0 = (bottom - bandH).coerceAtLeast(0)
            val h = (bottom - y0).coerceAtLeast(1)
            val w = soft.width
            val px = IntArray(w * h)
            soft.getPixels(px, 0, w, 0, y0, w, h)
            // Every 7th pixel is plenty for an average.
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            var i = 0
            while (i < px.size) { val p = px[i]; r += ColorMath.red(p); g += ColorMath.green(p); b += ColorMath.blue(p); n++; i += 7 }
            if (n == 0) return null
            return ColorMath.argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally { soft.recycle() }
    }

    override fun onDestroy() { AdaptiveColor.serviceRunning = false; super.onDestroy() }

    private companion object { const val MIN_GAP_MS = 700L }
}
