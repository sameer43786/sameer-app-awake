package com.sameer.livetranslator

import android.app.Application
import android.content.Context
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sameer.livetranslator.model.AppLanguage
import com.sameer.livetranslator.model.ConversationTurn
import com.sameer.livetranslator.model.LanguageCatalog
import com.sameer.livetranslator.model.SpeakerSide
import com.sameer.livetranslator.model.TranslatorUiState
import com.sameer.livetranslator.speech.SpeechController
import com.sameer.livetranslator.translation.TranslationManager
import com.sameer.livetranslator.tts.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Conversation coordinator.
 *
 * It combines three independent pipelines:
 * microphone -> speech recognition -> partial/final text
 * final/partial text -> language routing -> ML Kit translation
 * final translation -> optional TTS -> restart listening
 *
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
class MainViewModel(application: Application) : AndroidViewModel(application), SpeechController.Listener {

    private val speechController = SpeechController(application.applicationContext)
    private val translationManager = TranslationManager()
    private val ttsManager = TtsManager(application.applicationContext)
    private val prefs = application.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        TranslatorUiState(
            handsFreeSupported = speechController.supportsFrameworkLanguageSwitch(),
            onDeviceSpeechAvailable = speechController.isOnDeviceRecognitionAvailable(),
            speakTranslation = prefs.getBoolean("speak_translation", true),
            preferOnDeviceSpeech = prefs.getBoolean("prefer_on_device", true)
        )
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    private var partialJob: Job? = null
    private var restartJob: Job? = null
    private var translationGeneration = 0L
    private var turnCounter = 0L
    private var lastResolvedSpeaker = SpeakerSide.A
    private var stoppedForBackground = false
    private var forceSystemRecognizerForSession = false

    init {
        speechController.listener = this
        configureTranslationPair()
    }

    fun setLanguageA(language: AppLanguage) {
        if (language.mlKitTag == _uiState.value.languageB.mlKitTag) return
        stopConversation("Languages changed")
        _uiState.update { it.copy(languageA = language) }
        configureTranslationPair()
    }

    fun setLanguageB(language: AppLanguage) {
        if (language.mlKitTag == _uiState.value.languageA.mlKitTag) return
        stopConversation("Languages changed")
        _uiState.update { it.copy(languageB = language) }
        configureTranslationPair()
    }

    fun swapLanguages() {
        stopConversation("Languages swapped")
        _uiState.update {
            it.copy(
                languageA = it.languageB,
                languageB = it.languageA,
                liveOriginal = "",
                liveTranslation = "",
                detectedLanguage = null
            )
        }
        lastResolvedSpeaker = SpeakerSide.A
        configureTranslationPair()
    }

    fun setSpeakTranslation(enabled: Boolean) {
        prefs.edit().putBoolean("speak_translation", enabled).apply()
        _uiState.update { it.copy(speakTranslation = enabled) }
        if (!enabled) ttsManager.stop()
    }

    fun setPreferOnDeviceSpeech(enabled: Boolean) {
        prefs.edit().putBoolean("prefer_on_device", enabled).apply()
        _uiState.update { it.copy(preferOnDeviceSpeech = enabled) }
    }

    fun listenForSpeaker(side: SpeakerSide) {
        restartJob?.cancel()
        partialJob?.cancel()
        forceSystemRecognizerForSession = false
        ttsManager.stop()
        speechController.cancel()

        _uiState.update {
            it.copy(
                handsFreeEnabled = false,
                activeSpeaker = side,
                isListening = true,
                liveOriginal = "",
                liveTranslation = "",
                detectedLanguage = null,
                statusMessage = "Listening to ${speakerLanguage(side).displayName}…"
            )
        }
        lastResolvedSpeaker = side
        startSpeechForManualSide(side)
    }

    fun toggleHandsFree() {
        if (_uiState.value.handsFreeEnabled) {
            stopConversation("Hands-free stopped")
            return
        }
        startHandsFree()
    }

    fun stopConversation(message: String = "Stopped") {
        restartJob?.cancel()
        partialJob?.cancel()
        translationGeneration++
        speechController.cancel()
        ttsManager.stop()
        _uiState.update {
            it.copy(
                handsFreeEnabled = false,
                isListening = false,
                activeSpeaker = null,
                micLevel = 0f,
                statusMessage = message
            )
        }
    }

    fun clearConversation() {
        _uiState.update {
            it.copy(
                conversation = emptyList(),
                liveOriginal = "",
                liveTranslation = "",
                detectedLanguage = null,
                statusMessage = "Transcript cleared"
            )
        }
    }

    fun prepareModelsAgain() {
        configureTranslationPair()
    }

    fun onMicrophonePermissionDenied() {
        stopConversation("Microphone permission is required for live translation")
    }

    fun onAppBackgrounded() {
        stoppedForBackground = _uiState.value.handsFreeEnabled || _uiState.value.isListening
        if (stoppedForBackground) {
            stopConversation("Paused while the app is in the background")
        }
    }

    override fun onReady() {
        _uiState.update { it.copy(isListening = true, statusMessage = "Listening…") }
    }

    override fun onSpeechStarted() {
        _uiState.update { it.copy(statusMessage = "Speech detected") }
    }

    override fun onSpeechEnded() {
        _uiState.update { it.copy(isListening = false, micLevel = 0f, statusMessage = "Translating…") }
    }

    override fun onPartial(text: String, detectedLanguageTag: String?) {
        val speaker = resolveSpeakerFromTag(detectedLanguageTag)
            ?: _uiState.value.activeSpeaker
            ?: lastResolvedSpeaker
        val source = speakerLanguage(speaker)
        val target = oppositeLanguage(speaker)
        lastResolvedSpeaker = speaker

        _uiState.update {
            it.copy(
                activeSpeaker = speaker,
                liveOriginal = text,
                detectedLanguage = source,
                statusMessage = "Live caption"
            )
        }

        // Debounce partial hypotheses. This keeps the UI responsive without translating every
        // unstable character sequence emitted by the recognizer.
        partialJob?.cancel()
        val generation = ++translationGeneration
        partialJob = viewModelScope.launch {
            delay(180)
            translationManager.translate(
                text = text,
                source = source,
                target = target,
                onSuccess = { translated ->
                    if (generation == translationGeneration) {
                        _uiState.update { it.copy(liveTranslation = translated) }
                    }
                },
                onFailure = { message ->
                    if (generation == translationGeneration) {
                        _uiState.update { it.copy(statusMessage = message) }
                    }
                }
            )
        }
    }

    override fun onFinal(text: String, detectedLanguageTag: String?) {
        partialJob?.cancel()
        val generation = ++translationGeneration

        val preliminarySpeaker = resolveSpeakerFromTag(detectedLanguageTag)
            ?: _uiState.value.activeSpeaker
            ?: lastResolvedSpeaker

        // Framework language detection is preferred when Android supplies it. Otherwise, run the
        // compact on-device ML Kit language identifier against the final text.
        if (detectedLanguageTag != null || !_uiState.value.handsFreeEnabled) {
            translateFinal(text, preliminarySpeaker, generation)
        } else {
            translationManager.identifyBetween(
                text = text,
                first = _uiState.value.languageA,
                second = _uiState.value.languageB,
                onResolved = { identified ->
                    val side = when (identified?.mlKitTag) {
                        _uiState.value.languageB.mlKitTag -> SpeakerSide.B
                        _uiState.value.languageA.mlKitTag -> SpeakerSide.A
                        else -> preliminarySpeaker
                    }
                    translateFinal(text, side, generation)
                }
            )
        }
    }

    override fun onError(code: Int, message: String) {
        val state = _uiState.value
        val recoverable = code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            code == SpeechRecognizer.ERROR_CLIENT ||
            code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

        if (state.preferOnDeviceSpeech && !forceSystemRecognizerForSession &&
            (code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        ) {
            forceSystemRecognizerForSession = true
            _uiState.update { it.copy(statusMessage = "Offline speech model unavailable. Retrying with system recognition…") }
            scheduleRestart(300)
            return
        }

        _uiState.update { it.copy(isListening = false, micLevel = 0f, statusMessage = message) }
        if (state.handsFreeEnabled && recoverable) scheduleRestart(320)
    }

    override fun onRms(level: Float) {
        _uiState.update { it.copy(micLevel = level.coerceIn(0f, 1f)) }
    }

    private fun startHandsFree() {
        forceSystemRecognizerForSession = false
        ttsManager.stop()
        speechController.cancel()
        _uiState.update {
            it.copy(
                handsFreeEnabled = true,
                activeSpeaker = null,
                liveOriginal = "",
                liveTranslation = "",
                detectedLanguage = null,
                statusMessage = if (it.handsFreeSupported) {
                    "Hands-free: speak either language"
                } else {
                    "Hands-free fallback: automatic text language detection"
                }
            )
        }
        startSpeechHandsFree()
    }

    private fun startSpeechHandsFree() {
        val state = _uiState.value
        val preferOnDevice = state.preferOnDeviceSpeech && !forceSystemRecognizerForSession
        speechController.startHandsFree(
            languageA = state.languageA.speechLocaleTag,
            languageB = state.languageB.speechLocaleTag,
            preferOnDevice = preferOnDevice
        )
    }

    private fun startSpeechForManualSide(side: SpeakerSide) {
        val state = _uiState.value
        speechController.startSingleLanguage(
            localeTag = speakerLanguage(side).speechLocaleTag,
            preferOnDevice = state.preferOnDeviceSpeech && !forceSystemRecognizerForSession
        )
    }

    private fun translateFinal(text: String, side: SpeakerSide, generation: Long) {
        val source = speakerLanguage(side)
        val target = oppositeLanguage(side)
        lastResolvedSpeaker = side
        _uiState.update {
            it.copy(
                activeSpeaker = side,
                detectedLanguage = source,
                liveOriginal = text,
                statusMessage = "Translating ${source.displayName} → ${target.displayName}…"
            )
        }

        translationManager.translate(
            text = text,
            source = source,
            target = target,
            onSuccess = { translated ->
                if (generation != translationGeneration) return@translate
                turnCounter++
                val newTurn = ConversationTurn(
                    id = turnCounter,
                    speaker = side,
                    sourceLanguage = source,
                    targetLanguage = target,
                    originalText = text,
                    translatedText = translated,
                    timestampMillis = System.currentTimeMillis()
                )
                _uiState.update {
                    it.copy(
                        conversation = it.conversation + newTurn,
                        liveTranslation = translated,
                        statusMessage = "Translation complete",
                        isListening = false,
                        micLevel = 0f
                    )
                }

                if (_uiState.value.speakTranslation) {
                    _uiState.update { it.copy(statusMessage = "Speaking ${target.displayName} translation…") }
                    ttsManager.speak(
                        text = translated,
                        localeTag = target.ttsLocaleTag,
                        onDone = {
                            _uiState.update { it.copy(statusMessage = "Ready") }
                            if (_uiState.value.handsFreeEnabled) scheduleRestart(140)
                        },
                        onError = {
                            _uiState.update { it.copy(statusMessage = "Translation ready; TTS unavailable") }
                            if (_uiState.value.handsFreeEnabled) scheduleRestart(140)
                        }
                    )
                } else if (_uiState.value.handsFreeEnabled) {
                    scheduleRestart(80)
                }
            },
            onFailure = { message ->
                if (generation == translationGeneration) {
                    _uiState.update { it.copy(statusMessage = message, isListening = false, micLevel = 0f) }
                    if (_uiState.value.handsFreeEnabled) scheduleRestart(350)
                }
            }
        )
    }

    private fun scheduleRestart(delayMs: Long) {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(delayMs)
            if (_uiState.value.handsFreeEnabled) startSpeechHandsFree()
        }
    }

    private fun configureTranslationPair() {
        val a = _uiState.value.languageA
        val b = _uiState.value.languageB
        _uiState.update { it.copy(modelsReady = false, modelStatus = "Preparing ${a.displayName} and ${b.displayName} models…") }
        translationManager.preparePair(
            first = a,
            second = b,
            onReady = {
                _uiState.update { it.copy(modelsReady = true, modelStatus = "Translation models ready") }
            },
            onFailure = { message ->
                _uiState.update { it.copy(modelsReady = false, modelStatus = message) }
            }
        )
    }

    private fun resolveSpeakerFromTag(tag: String?): SpeakerSide? {
        if (tag.isNullOrBlank()) return null
        val normalized = tag.lowercase()
        val a = _uiState.value.languageA
        val b = _uiState.value.languageB
        return when {
            normalized.startsWith(a.languageCode.lowercase()) -> SpeakerSide.A
            normalized.startsWith(b.languageCode.lowercase()) -> SpeakerSide.B
            else -> null
        }
    }

    private fun speakerLanguage(side: SpeakerSide): AppLanguage =
        if (side == SpeakerSide.A) _uiState.value.languageA else _uiState.value.languageB

    private fun oppositeLanguage(side: SpeakerSide): AppLanguage =
        if (side == SpeakerSide.A) _uiState.value.languageB else _uiState.value.languageA

    override fun onCleared() {
        super.onCleared()
        speechController.destroy()
        translationManager.close()
        ttsManager.shutdown()
    }
}
