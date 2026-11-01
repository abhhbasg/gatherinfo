package com.metrowin.keyboard.service

import android.os.Handler
import android.os.Looper

/**
 * The bridge between the optional screen-colour service and the keyboard: the service writes the colour it sampled above the
 * keyboard, the keyboard reads it. Both live in this app's process. Nothing is stored or sent anywhere.
 */
object AdaptiveColor {
    @Volatile var color: Int? = null
        private set

    /** Top edge of the keyboard on screen, in pixels, set by the keyboard so the service knows where to look. */
    @Volatile var keyboardTop: Int = 0

    /** True while the keyboard is on screen: the service does nothing the rest of the time. */
    @Volatile var keyboardShown: Boolean = false

    /** True while the service is running; the settings show it. */
    @Volatile var serviceRunning: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun add(l: () -> Unit) { listeners += l }
    fun remove(l: () -> Unit) { listeners -= l }

    fun set(c: Int?) {
        if (color == c) return
        color = c
        main.post { listeners.forEach { it() } }
    }
}
