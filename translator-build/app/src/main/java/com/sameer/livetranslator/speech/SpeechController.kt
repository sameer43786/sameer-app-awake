package com.sameer.livetranslator.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.math.max

/**
 * Thin lifecycle-safe adapter around Android SpeechRecognizer.
 *
 * It requests partial results for low perceived latency and, on Android 14+, asks the framework
 * recognizer to switch automatically between the two selected conversation languages.
 *
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
class SpeechController(private val context: Context) {

    interface Listener {
        fun onReady()
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onPartial(text: String, detectedLanguageTag: String?)
        fun onFinal(text: String, detectedLanguageTag: String?)
        fun onError(code: Int, message: String)
        fun onRms(level: Float)
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDeviceRecognizer = false
    private var lastDetectedLanguage: String? = null
    private var destroyed = false

    fun isOnDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun supportsFrameworkLanguageSwitch(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun startSingleLanguage(localeTag: String, preferOnDevice: Boolean) {
        start(
            localeTag = localeTag,
            allowedLanguages = listOf(localeTag),
            enableLanguageSwitch = false,
            preferOnDevice = preferOnDevice
        )
    }

    fun startHandsFree(languageA: String, languageB: String, preferOnDevice: Boolean) {
        start(
            localeTag = languageA,
            allowedLanguages = listOf(languageA, languageB),
            enableLanguageSwitch = supportsFrameworkLanguageSwitch(),
            preferOnDevice = preferOnDevice
        )
    }

    private fun start(
        localeTag: String,
        allowedLanguages: List<String>,
        enableLanguageSwitch: Boolean,
        preferOnDevice: Boolean
    ) {
        if (destroyed) return
        mainHandler.post {
            try {
                ensureRecognizer(preferOnDevice)
                lastDetectedLanguage = null
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 520L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 330L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && enableLanguageSwitch) {
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                        putStringArrayListExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                            ArrayList(allowedLanguages)
                        )
                    }
                }
                recognizer?.startListening(intent)
            } catch (t: Throwable) {
                listener?.onError(SpeechRecognizer.ERROR_CLIENT, "Speech recognizer could not start: ${t.message.orEmpty()}")
            }
        }
    }

    private fun ensureRecognizer(preferOnDevice: Boolean) {
        val wantOnDevice = preferOnDevice && isOnDeviceRecognitionAvailable()
        if (recognizer != null && wantOnDevice == usingOnDeviceRecognizer) return

        recognizer?.destroy()
        recognizer = null
        usingOnDeviceRecognizer = false

        recognizer = if (wantOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            usingOnDeviceRecognizer = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.also { it.setRecognitionListener(recognitionListener) }
    }

    fun cancel() {
        mainHandler.post {
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() {
            listener?.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Android commonly reports roughly -2..12 dB. Normalize conservatively for UI only.
            val normalized = ((max(0f, rmsdB) / 12f)).coerceIn(0f, 1f)
            listener?.onRms(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listener?.onSpeechEnded()
        }

        override fun onError(error: Int) {
            listener?.onError(error, humanError(error))
        }

        override fun onResults(results: Bundle?) {
            updateDetectedLanguage(results)
            val best = extractBestText(results)
            if (best.isNotBlank()) listener?.onFinal(best, lastDetectedLanguage)
            else listener?.onError(SpeechRecognizer.ERROR_NO_MATCH, "No speech was recognized")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            updateDetectedLanguage(partialResults)
            val best = extractBestText(partialResults)
            if (best.isNotBlank()) listener?.onPartial(best, lastDetectedLanguage)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onSegmentResults(segmentResults: Bundle) {
            updateDetectedLanguage(segmentResults)
            val best = extractBestText(segmentResults)
            if (best.isNotBlank()) listener?.onPartial(best, lastDetectedLanguage)
        }

        override fun onEndOfSegmentedSession() = Unit

        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { lastDetectedLanguage = it }
            }
        }
    }

    private fun extractBestText(bundle: Bundle?): String {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun updateDetectedLanguage(bundle: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            bundle?.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { lastDetectedLanguage = it }
        }
    }

    private fun humanError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition session ended"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No clear speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service rate limit reached"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Selected speech language is not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Selected offline speech model is not installed"
        else -> "Speech recognition error ($error)"
    }
}
