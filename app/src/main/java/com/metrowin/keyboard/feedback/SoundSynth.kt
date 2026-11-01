package com.metrowin.keyboard.feedback

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The key click, made here instead of taken from a file: nothing of Microsoft's is shipped, and it works whatever the "touch
 * sounds" switch of Android says. A few thousandths of a second of a tone that dies away at once, so it is a tick and not a
 * beep. Three pitches: letters, space and delete.
 */
object SoundSynth {
    const val RATE = 22_050

    enum class Tick(val hertz: Double, val millis: Int) { KEY(2100.0, 16), SPACE(1500.0, 20), DELETE(1200.0, 18) }

    fun pcm(tick: Tick, volume: Double = 0.6): ShortArray {
        val count = RATE * tick.millis / 1000
        val fade = (count / 4).coerceAtLeast(1)
        return ShortArray(count) { i ->
            val t = i.toDouble() / RATE
            val tail = if (i >= count - fade) (count - 1 - i).toDouble() / fade else 1.0
            (sin(2.0 * PI * tick.hertz * t) * exp(-t * 260.0) * tail * volume * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** [pcm] as the bytes of a WAVE file (a SoundPool loads sounds from files). */
    fun wav(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun int(v: Int) = repeat(4) { out.write(v shr (8 * it) and 0xFF) }
        fun short(v: Int) = repeat(2) { out.write(v shr (8 * it) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + data); out.write("WAVEfmt ".toByteArray())
        int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(data)
        pcm.forEach { short(it.toInt()) }
        return out.toByteArray()
    }
}
