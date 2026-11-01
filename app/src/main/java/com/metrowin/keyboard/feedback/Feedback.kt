package com.metrowin.keyboard.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.metrowin.keyboard.Prefs
import com.metrowin.keyboard.layout.KeyType
import java.io.File

/** Sound and vibration for a key press, from the settings: the synthesised click, Android's own, a file of the user's, or nothing. */
class Feedback(private val context: Context, private val prefs: Prefs) {
    private var pool: SoundPool? = null
    private val ids = HashMap<SoundSynth.Tick, Int>()
    private var customId = 0
    private var loadedMode = ""
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun customSoundFile() = File(context.filesDir, "custom_sound")

    /** Loads what the current settings need; cheap to call again when a setting changed. */
    fun prepare() {
        val mode = prefs.soundMode
        val signature = mode + (if (mode == "custom") customSoundFile().lastModified() else 0)
        if (signature == loadedMode) return
        release()
        loadedMode = signature
        if (mode != "metro" && mode != "custom") return
        val sp = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        pool = sp
        if (mode == "metro") {
            for (t in SoundSynth.Tick.entries) {
                val f = File(context.cacheDir, "tick_${t.name.lowercase()}.wav")
                if (!f.exists()) f.writeBytes(SoundSynth.wav(SoundSynth.pcm(t)))
                ids[t] = sp.load(f.absolutePath, 1)
            }
        } else if (customSoundFile().exists()) {
            customId = sp.load(customSoundFile().absolutePath, 1)
        }
    }

    fun press(type: KeyType) {
        sound(type)
        vibrate()
    }

    private fun sound(type: KeyType) {
        val volume = (prefs.soundVolume / 100f).coerceIn(0f, 1f)
        when (prefs.soundMode) {
            "off" -> {}
            "system" -> audio.playSoundEffect(when (type) {
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }, volume)
            "custom" -> pool?.play(customId, volume, volume, 1, 0, 1f)
            else -> {
                val tick = when (type) { KeyType.SPACE -> SoundSynth.Tick.SPACE; KeyType.BACKSPACE -> SoundSynth.Tick.DELETE; else -> SoundSynth.Tick.KEY }
                ids[tick]?.let { pool?.play(it, volume, volume, 1, 0, 1f) }
            }
        }
    }

    private fun vibrate() {
        if (!prefs.vibration) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val strength = prefs.vibrationIntensity.coerceIn(1, 100)
        // 0-100 % of the strongest vibration the motor can do; a motor without amplitude control gets a longer or shorter tick instead.
        if (v.hasAmplitudeControl()) {
            v.vibrate(VibrationEffect.createOneShot(14, (strength * 255 / 100).coerceIn(1, 255)))
        } else {
            v.vibrate(VibrationEffect.createOneShot((6 + strength * 24L / 100), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun release() {
        pool?.release(); pool = null; ids.clear(); customId = 0; loadedMode = ""
    }
}
