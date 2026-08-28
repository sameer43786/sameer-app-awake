package com.sameer.livetranslator.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameer.livetranslator.MainViewModel
import com.sameer.livetranslator.model.AppLanguage
import com.sameer.livetranslator.model.ConversationTurn
import com.sameer.livetranslator.model.LanguageCatalog
import com.sameer.livetranslator.model.SpeakerSide
import java.text.DateFormat
import java.util.Date

/**
 * Main Compose UI.
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) permissionAction?.invoke() else viewModel.onMicrophonePermissionDenied()
        permissionAction = null
    }

    fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            permissionAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onAppBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sameer Live Translator", fontWeight = FontWeight.Bold)
                        Text(
                            "By: Sameer Ali | Contact: sameer43786@gmail.com",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LanguagePairCard(
                    languageA = state.languageA,
                    languageB = state.languageB,
                    onLanguageA = viewModel::setLanguageA,
                    onLanguageB = viewModel::setLanguageB,
                    onSwap = viewModel::swapLanguages,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                ModelStatusCard(
                    ready = state.modelsReady,
                    message = state.modelStatus,
                    onPrepare = viewModel::prepareModelsAgain,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                HandsFreeCard(
                    enabled = state.handsFreeEnabled,
                    supported = state.handsFreeSupported,
                    onToggle = {
                        withMicrophonePermission { viewModel.toggleHandsFree() }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                LiveTranslationCard(
                    sourceLanguage = state.detectedLanguage?.displayName ?: "Waiting for speech",
                    original = state.liveOriginal,
                    translated = state.liveTranslation,
                    status = state.statusMessage,
                    micLevel = state.micLevel,
                    isListening = state.isListening,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                SpeakerButtons(
                    languageA = state.languageA,
                    languageB = state.languageB,
                    activeSpeaker = state.activeSpeaker,
                    isListening = state.isListening,
                    onSpeakerA = { withMicrophonePermission { viewModel.listenForSpeaker(SpeakerSide.A) } },
                    onSpeakerB = { withMicrophonePermission { viewModel.listenForSpeaker(SpeakerSide.B) } },
                    onStop = { viewModel.stopConversation() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                SettingsCard(
                    speakTranslation = state.speakTranslation,
                    preferOnDevice = state.preferOnDeviceSpeech,
                    onDeviceAvailable = state.onDeviceSpeechAvailable,
                    onSpeakChanged = viewModel::setSpeakTranslation,
                    onOnDeviceChanged = viewModel::setPreferOnDeviceSpeech,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                TranscriptHeader(
                    count = state.conversation.size,
                    onClear = viewModel::clearConversation,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (state.conversation.isEmpty()) {
                item {
                    Text(
                        text = "Completed conversation turns appear here. The transcript stays in memory only and is cleared when the app process ends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(state.conversation, key = { it.id }) { turn ->
                    TranscriptTurn(
                        turn = turn,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun LanguagePairCard(
    languageA: AppLanguage,
    languageB: AppLanguage,
    onLanguageA: (AppLanguage) -> Unit,
    onLanguageB: (AppLanguage) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conversation languages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LanguageSelector("Person A", languageA, onLanguageA, Modifier.weight(1f))
                IconButton(onClick = onSwap) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap languages")
                }
                LanguageSelector("Person B", languageB, onLanguageB, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    label: String,
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selected.displayName, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LanguageCatalog.languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        expanded = false
                        onSelected(language)
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    ready: Boolean,
    message: String,
    onPrepare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (ready) "✓" else "↓", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(if (ready) "On-device translation ready" else "Translation models", fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!ready) OutlinedButton(onClick = onPrepare) { Text("Prepare") }
        }
    }
}

@Composable
private fun HandsFreeCard(
    enabled: Boolean,
    supported: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hands-free conversation", fontWeight = FontWeight.Bold)
                    Text(
                        if (supported) "Android can request automatic switching between the two selected languages."
                        else "Older Android fallback uses text language identification after each phrase.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = enabled, onCheckedChange = { onToggle() })
            }
            if (enabled) Text("Speak naturally. The app resumes listening after it finishes speaking each translation.")
        }
    }
}

@Composable
private fun LiveTranslationCard(
    sourceLanguage: String,
    original: String,
    translated: String,
    status: String,
    micLevel: Float,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live translation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isListening) Text("● LIVE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(sourceLanguage, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (original.isBlank()) "Speech appears here as it is recognized…" else original,
                style = MaterialTheme.typography.titleMedium,
                color = if (original.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (translated.isBlank()) "Translation appears here…" else translated,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (translated.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
            if (isListening) LinearProgressIndicator(progress = { micLevel }, modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeakerButtons(
    languageA: AppLanguage,
    languageB: AppLanguage,
    activeSpeaker: SpeakerSide?,
    isListening: Boolean,
    onSpeakerA: () -> Unit,
    onSpeakerB: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeakerButton(
                text = languageA.displayName,
                selected = activeSpeaker == SpeakerSide.A && isListening,
                onClick = onSpeakerA,
                modifier = Modifier.weight(1f)
            )
            SpeakerButton(
                text = languageB.displayName,
                selected = activeSpeaker == SpeakerSide.B && isListening,
                onClick = onSpeakerB,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Stop microphone")
        }
    }
}

@Composable
private fun SpeakerButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        colors = if (selected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        else ButtonDefaults.buttonColors()
    ) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsCard(
    speakTranslation: Boolean,
    preferOnDevice: Boolean,
    onDeviceAvailable: Boolean,
    onSpeakChanged: (Boolean) -> Unit,
    onOnDeviceChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conversation settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SettingSwitch(
                title = "Speak translations aloud",
                subtitle = "Pauses recognition while the phone speaks to prevent feedback loops.",
                checked = speakTranslation,
                enabled = true,
                onChecked = onSpeakChanged
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Prefer on-device speech recognition",
                subtitle = if (onDeviceAvailable) "Available on this device. Falls back automatically if a language model is missing."
                else "No Android on-device recognizer is currently available; system recognition will be used.",
                checked = preferOnDevice,
                enabled = onDeviceAvailable,
                onChecked = onOnDeviceChanged
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun TranscriptHeader(count: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Conversation transcript", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$count completed turn${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Clear transcript") }
    }
}

@Composable
private fun TranscriptTurn(turn: ConversationTurn, modifier: Modifier = Modifier) {
    val container = if (turn.speaker == SpeakerSide.A) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.tertiaryContainer

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Person ${turn.speaker.name} • ${turn.sourceLanguage.displayName} → ${turn.targetLanguage.displayName}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(turn.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(turn.originalText, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(turn.translatedText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
