package com.metrowin.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.metrowin.keyboard.Prefs

/**
 * Dictation with the speech recogniser of the phone. With "offline only" on, it asks for on-device recognition, so the sound does
 * not leave the phone (if the phone has the language downloaded). In continuous mode it keeps listening until stopped.
 */
class VoiceInput(private val context: Context, private val prefs: Prefs, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onListening(on: Boolean)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onNeedPermission()
        fun onUnavailable()
        fun onError(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var tag = "en"

    val isActive get() = active

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(languageTag: String) {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { callbacks.onUnavailable(); return }
        if (!hasPermission()) { callbacks.onNeedPermission(); return }
        tag = languageTag
        active = true
        callbacks.onListening(true)
        listen()
    }

    fun stop() {
        if (!active) return
        active = false
        recognizer?.runCatching { stopListening(); destroy() }
        recognizer = null
        callbacks.onListening(false)
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it; it.setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.voiceOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { callbacks.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) callbacks.onResult(text)
            if (active && prefs.voiceContinuous) listen() else stop()
        }

        override fun onError(error: Int) {
            // Silence is not a failure in continuous mode: just listen again.
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (active && prefs.voiceContinuous && quiet) { listen(); return }
            stop()
            if (!quiet) callbacks.onError(error)
        }
    }
}
