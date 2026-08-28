package com.sameer.livetranslator.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sameer.livetranslator.model.AppLanguage

/**
 * Owns ML Kit translation clients and the bundled language identifier.
 * Translator instances are cached per direction to avoid rebuilding a model client on every phrase.
 *
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
class TranslationManager {

    private val clients = mutableMapOf<String, Translator>()
    private val languageIdentifier = LanguageIdentification.getClient()
    private val downloadConditions = DownloadConditions.Builder().build()

    fun preparePair(
        first: AppLanguage,
        second: AppLanguage,
        onReady: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val forward = clientFor(first, second)
        val reverse = clientFor(second, first)

        forward.downloadModelIfNeeded(downloadConditions)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Model download failed")
                reverse.downloadModelIfNeeded(downloadConditions)
            }
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { e ->
                onFailure("Translation model preparation failed: ${e.message ?: "check internet connection"}")
            }
    }

    fun translate(
        text: String,
        source: AppLanguage,
        target: AppLanguage,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (text.isBlank()) {
            onSuccess("")
            return
        }

        val translator = clientFor(source, target)
        translator.downloadModelIfNeeded(downloadConditions)
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Model unavailable")
                translator.translate(text)
            }
            .addOnSuccessListener(onSuccess)
            .addOnFailureListener { e ->
                onFailure("Translation failed: ${e.message ?: "model unavailable"}")
            }
    }

    /**
     * Identify only between the two selected conversation languages. The language identifier can
     * emit close language codes; this function deliberately rejects unrelated results instead of
     * silently routing a phrase in the wrong direction.
     */
    fun identifyBetween(
        text: String,
        first: AppLanguage,
        second: AppLanguage,
        onResolved: (AppLanguage?) -> Unit
    ) {
        if (text.length < 3) {
            onResolved(null)
            return
        }

        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { tag ->
                when {
                    tag.equals(first.languageCode, ignoreCase = true) ||
                        tag.startsWith("${first.languageCode}-", ignoreCase = true) -> onResolved(first)

                    tag.equals(second.languageCode, ignoreCase = true) ||
                        tag.startsWith("${second.languageCode}-", ignoreCase = true) -> onResolved(second)

                    else -> onResolved(null)
                }
            }
            .addOnFailureListener { onResolved(null) }
    }

    private fun clientFor(source: AppLanguage, target: AppLanguage): Translator {
        val key = "${source.mlKitTag}->${target.mlKitTag}"
        return clients.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source.mlKitTag)
                    .setTargetLanguage(target.mlKitTag)
                    .build()
            )
        }
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
        languageIdentifier.close()
    }
}
