package com.sameer.livetranslator.model

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * A user-selectable conversation language.
 * speechLocaleTag is intentionally region-specific for better ASR/TTS pronunciation.
 * mlKitTag uses ML Kit's BCP-47 translation model tag.
 */
data class AppLanguage(
    val displayName: String,
    val languageCode: String,
    val speechLocaleTag: String,
    val ttsLocaleTag: String,
    val mlKitTag: String
)

object LanguageCatalog {
    val languages = listOf(
        AppLanguage("English", "en", "en-US", "en-US", TranslateLanguage.ENGLISH),
        AppLanguage("Spanish", "es", "es-ES", "es-ES", TranslateLanguage.SPANISH),
        AppLanguage("Catalan", "ca", "ca-ES", "ca-ES", TranslateLanguage.CATALAN),
        AppLanguage("French", "fr", "fr-FR", "fr-FR", TranslateLanguage.FRENCH),
        AppLanguage("German", "de", "de-DE", "de-DE", TranslateLanguage.GERMAN),
        AppLanguage("Italian", "it", "it-IT", "it-IT", TranslateLanguage.ITALIAN),
        AppLanguage("Portuguese", "pt", "pt-PT", "pt-PT", TranslateLanguage.PORTUGUESE),
        AppLanguage("Urdu", "ur", "ur-PK", "ur-PK", TranslateLanguage.URDU),
        AppLanguage("Arabic", "ar", "ar-SA", "ar-SA", TranslateLanguage.ARABIC),
        AppLanguage("Hindi", "hi", "hi-IN", "hi-IN", TranslateLanguage.HINDI),
        AppLanguage("Chinese", "zh", "zh-CN", "zh-CN", TranslateLanguage.CHINESE),
        AppLanguage("Japanese", "ja", "ja-JP", "ja-JP", TranslateLanguage.JAPANESE),
        AppLanguage("Korean", "ko", "ko-KR", "ko-KR", TranslateLanguage.KOREAN),
        AppLanguage("Turkish", "tr", "tr-TR", "tr-TR", TranslateLanguage.TURKISH),
        AppLanguage("Dutch", "nl", "nl-NL", "nl-NL", TranslateLanguage.DUTCH)
    )

    val defaultA: AppLanguage = languages.first { it.languageCode == "es" }
    val defaultB: AppLanguage = languages.first { it.languageCode == "en" }
}
