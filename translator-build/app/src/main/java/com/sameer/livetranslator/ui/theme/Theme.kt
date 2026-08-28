package com.sameer.livetranslator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    secondary = Color(0xFF315D85),
    tertiary = Color(0xFF675087),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE8EEF5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    secondary = Color(0xFF9CCBFA),
    tertiary = Color(0xFFD3BAF8)
)

@Composable
fun SameerLiveTranslatorTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
