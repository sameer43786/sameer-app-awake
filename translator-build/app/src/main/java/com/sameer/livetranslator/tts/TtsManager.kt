package com.sameer.livetranslator.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-speech wrapper that reports completion so the microphone can resume only after the
 * phone finishes speaking. This prevents the app from translating its own speaker output.
 *
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val sequence = AtomicLong(0L)
    private var currentDone: (() -> Unit)? = null
    private var currentError: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setSpeechRate(1.02f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        val callback = currentDone
                        currentDone = null
                        currentError = null
                        callback?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val callback = currentError
                        currentDone = null
                        currentError = null
                        callback?.invoke()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val callback = currentError
                        currentDone = null
                        currentError = null
                        callback?.invoke()
                    }
                })
            }
        }
    }

    fun speak(
        text: String,
        localeTag: String,
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val engine = tts
        if (!ready || engine == null || text.isBlank()) {
            onError()
            return
        }

        val locale = Locale.forLanguageTag(localeTag)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onError()
            return
        }

        currentDone = onDone
        currentError = onError
        val utteranceId = "sameer_translation_${sequence.incrementAndGet()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (status == TextToSpeech.ERROR) {
            currentDone = null
            currentError = null
            onError()
        }
    }

    fun stop() {
        currentDone = null
        currentError = null
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
    }
}
