package com.sameer.livetranslator.model

enum class SpeakerSide { A, B }

data class ConversationTurn(
    val id: Long,
    val speaker: SpeakerSide,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val originalText: String,
    val translatedText: String,
    val timestampMillis: Long
)

data class TranslatorUiState(
    val languageA: AppLanguage = LanguageCatalog.defaultA,
    val languageB: AppLanguage = LanguageCatalog.defaultB,
    val handsFreeEnabled: Boolean = false,
    val handsFreeSupported: Boolean = false,
    val onDeviceSpeechAvailable: Boolean = false,
    val preferOnDeviceSpeech: Boolean = true,
    val speakTranslation: Boolean = true,
    val modelsReady: Boolean = false,
    val modelStatus: String = "Preparing translation models…",
    val isListening: Boolean = false,
    val activeSpeaker: SpeakerSide? = null,
    val detectedLanguage: AppLanguage? = null,
    val liveOriginal: String = "",
    val liveTranslation: String = "",
    val statusMessage: String = "Ready",
    val micLevel: Float = 0f,
    val conversation: List<ConversationTurn> = emptyList()
)
