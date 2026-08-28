package com.sameer.livetranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.sameer.livetranslator.ui.TranslatorScreen
import com.sameer.livetranslator.ui.theme.SameerLiveTranslatorTheme

/**
 * Main activity for Sameer Live Translator.
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SameerLiveTranslatorTheme {
                TranslatorScreen(viewModel)
            }
        }
    }
}
