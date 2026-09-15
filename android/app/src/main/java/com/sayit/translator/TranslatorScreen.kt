package com.sayit.translator

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var settingsOpen by remember {
        mutableStateOf(
            state.translationEngine == TranslationEngine.OPENAI && !state.hasOpenAiApiKey,
        )
    }
    var pendingSide by remember { mutableStateOf<LanguageSide?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val side = pendingSide
        pendingSide = null
        if (granted && side != null) viewModel.tapMicrophone(side)
        else if (!granted) viewModel.reportPermissionDenied()
    }

    LaunchedEffect(state.translationEngine, state.hasOpenAiApiKey) {
        if (state.translationEngine == TranslationEngine.OPENAI && !state.hasOpenAiApiKey) {
            settingsOpen = true
        }
    }

    val onMicrophone: (LanguageSide) -> Unit = { side ->
        if (context.hasMicrophonePermission()) {
            viewModel.tapMicrophone(side)
        } else {
            pendingSide = side
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SayItPaper)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(onSettings = { settingsOpen = true })

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LanguageControls(
                modifier = Modifier.weight(1f),
                side = LanguageSide.A,
                language = state.languageA,
                otherLanguage = state.languageB,
                color = SayItBlue,
                softColor = SayItBlueSoft,
                status = state.status,
                activeSide = state.activeSide,
                onLanguage = { viewModel.setLanguage(LanguageSide.A, it) },
                onMicrophone = { onMicrophone(LanguageSide.A) },
            )
            LanguageControls(
                modifier = Modifier.weight(1f),
                side = LanguageSide.B,
                language = state.languageB,
                otherLanguage = state.languageA,
                color = SayItRed,
                softColor = SayItRedSoft,
                status = state.status,
                activeSide = state.activeSide,
                onLanguage = { viewModel.setLanguage(LanguageSide.B, it) },
                onMicrophone = { onMicrophone(LanguageSide.B) },
            )
        }

        TurnProgress(
            status = state.status,
            hasResult = state.resultSide != null,
            elapsedSeconds = state.elapsedSeconds,
            recognitionLabel = state.sttEngine.label,
            translationLabel = TranslationOption.from(state).label,
            playbackLabel = state.ttsEngine.label,
            onStopPlayback = viewModel::stopPlayback,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhraseCard(
                modifier = Modifier.weight(1f),
                text = state.textA,
                color = SayItBlue,
                background = SayItBlueSoft,
                canReplay = state.resultSide == LanguageSide.A && state.status == VoiceStatus.READY,
                onReplay = viewModel::replay,
            )
            PhraseCard(
                modifier = Modifier.weight(1f),
                text = state.textB,
                color = SayItRed,
                background = SayItRedSoft,
                canReplay = state.resultSide == LanguageSide.B && state.status == VoiceStatus.READY,
                onReplay = viewModel::replay,
            )
        }

        state.error?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            state = state,
            onSaveKey = viewModel::saveApiKey,
            onDeleteKey = viewModel::deleteApiKey,
            onTranslationOption = viewModel::setTranslationOption,
            onTtsEngine = viewModel::setTtsEngine,
            onSttEngine = viewModel::setSttEngine,
            canClose = state.translationEngine == TranslationEngine.GEMINI || state.hasOpenAiApiKey,
            onClose = { settingsOpen = false },
        )
    }
}

@Composable
private fun TurnProgress(
    status: VoiceStatus,
    hasResult: Boolean,
    elapsedSeconds: Int,
    recognitionLabel: String,
    translationLabel: String,
    playbackLabel: String,
    onStopPlayback: () -> Unit,
) {
    val activeIndex = when (status) {
        VoiceStatus.LISTENING, VoiceStatus.RECOGNIZING -> 0
        VoiceStatus.TRANSLATING -> 1
        VoiceStatus.SPEAKING -> 2
        VoiceStatus.READY -> if (hasResult) 3 else -1
        VoiceStatus.ERROR -> -1
    }
    val labels = listOf(
        "Speech recognition ($recognitionLabel)",
        "Translation ($translationLabel)",
        "Playback ($playbackLabel)",
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    ProgressDot(
                        completed = activeIndex == 3 || index < activeIndex,
                        active = index == activeIndex,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = label,
                        color = if (activeIndex == 3 || index <= activeIndex) SayItInk else SayItMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (index < labels.lastIndex) {
                        Spacer(Modifier.width(9.dp))
                        ProgressConnector(completed = index < activeIndex)
                        Spacer(Modifier.width(9.dp))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (status == VoiceStatus.LISTENING) {
                    Text(
                        text = formatClock(elapsedSeconds),
                        color = SayItMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                } else if (status == VoiceStatus.SPEAKING) {
                    IconButton(
                        onClick = onStopPlayback,
                        modifier = Modifier
                            .size(36.dp)
                            .background(SayItInk, RoundedCornerShape(10.dp)),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop playback",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressDot(completed: Boolean, active: Boolean) {
    val color = when {
        active -> SayItRed
        completed -> SayItInk
        else -> Color.Transparent
    }
    val borderColor = when {
        active -> SayItRed
        completed -> SayItInk
        else -> SayItMuted.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(color, CircleShape)
            .border(2.dp, borderColor, CircleShape),
    )
}

@Composable
private fun ProgressConnector(completed: Boolean) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(2.dp)
            .background(if (completed) SayItInk else SayItMuted.copy(alpha = 0.25f)),
    )
}

@Composable
private fun Header(
    onSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Say it",
                fontFamily = FontFamily.Serif,
                fontSize = 54.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-2).sp,
            )
            Text(
                text = ".",
                color = SayItRed,
                fontFamily = FontFamily.Serif,
                fontSize = 54.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun LanguageControls(
    modifier: Modifier,
    side: LanguageSide,
    language: AppLanguage,
    otherLanguage: AppLanguage,
    color: Color,
    softColor: Color,
    status: VoiceStatus,
    activeSide: LanguageSide?,
    onLanguage: (AppLanguage) -> Unit,
    onMicrophone: () -> Unit,
) {
    val listeningHere = status == VoiceStatus.LISTENING && activeSide == side
    val busy = status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR, VoiceStatus.LISTENING)
    val disabled = busy || (status == VoiceStatus.LISTENING && !listeningHere)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LanguagePicker(
            selected = language,
            excluded = otherLanguage,
            color = color,
            background = softColor,
            enabled = status == VoiceStatus.READY || status == VoiceStatus.ERROR,
            onSelected = onLanguage,
        )
        Button(
            onClick = onMicrophone,
            enabled = !disabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                disabledContainerColor = color.copy(alpha = 0.42f),
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.75f),
            ),
        ) {
            Icon(
                imageVector = if (listeningHere) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (listeningHere) language.stopLabel else language.speakLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LanguagePicker(
    selected: AppLanguage,
    excluded: AppLanguage,
    color: Color,
    background: Color,
    enabled: Boolean,
    onSelected: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(18.dp),
            color = background,
            border = BorderStroke(2.dp, color.copy(alpha = 0.66f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selected.code.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selected.nativeName,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Serif,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = color)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.nativeName) },
                    enabled = language != excluded,
                    onClick = {
                        onSelected(language)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PhraseCard(
    modifier: Modifier,
    text: String,
    color: Color,
    background: Color,
    canReplay: Boolean,
    onReplay: () -> Unit,
) {
    Surface(
        modifier = modifier.height(220.dp),
        shape = RoundedCornerShape(24.dp),
        color = background.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
        shadowElevation = 5.dp,
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    modifier = Modifier.padding(end = 24.dp),
                    fontFamily = FontFamily.Serif,
                    fontSize = 22.sp,
                    lineHeight = 29.sp,
                )
            }
            if (canReplay) {
                IconButton(
                    onClick = onReplay,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.White.copy(alpha = 0.78f), CircleShape),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Replay translation")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: TranslatorUiState,
    onSaveKey: (String) -> Boolean,
    onDeleteKey: () -> Unit,
    onTranslationOption: (TranslationOption) -> Unit,
    onTtsEngine: (TtsEngine) -> Unit,
    onSttEngine: (SttEngine) -> Unit,
    canClose: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (canClose) onClose() },
        containerColor = SayItPaper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (canClose) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close settings")
                    }
                }
            }

            SettingPicker(
                label = "Speech recognition",
                value = state.sttEngine,
                items = SttEngine.entries,
                itemLabel = { it.label },
                onSelected = onSttEngine,
            )
            Text(
                text = when (state.sttEngine) {
                    SttEngine.SYSTEM -> "Uses the Android recognition service configured on this phone."
                    SttEngine.GROQ -> "Uses Groq $GROQ_STT_MODEL after recording stops."
                },
                color = SayItMuted,
                fontSize = 13.sp,
            )

            SettingPicker(
                label = "Translation",
                value = TranslationOption.from(state),
                items = TranslationOption.entries,
                itemLabel = { it.label },
                onSelected = onTranslationOption,
            )
            if (state.translationEngine == TranslationEngine.OPENAI) {
                Text(
                    text = if (state.hasOpenAiApiKey) {
                        "OpenAI API key is saved on this phone."
                    } else {
                        "Add your OpenAI API key to use this translation engine."
                    },
                    color = SayItMuted,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "Hide key" else "Show key",
                            )
                        }
                    },
                )
                Button(
                    onClick = {
                        if (onSaveKey(apiKey)) {
                            apiKey = ""
                            onClose()
                        }
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.hasOpenAiApiKey) "Replace API key" else "Save API key")
                }
                if (state.hasOpenAiApiKey) {
                    TextButton(onClick = onDeleteKey, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete saved API key", color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEYS_URL)))
                        },
                    ) { Text("Create API key") }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BILLING_URL)))
                        },
                    ) { Text("Open billing") }
                }
            } else {
                Text(
                    text = "Uses the test Gemini key embedded in this local APK.",
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
            }

            SettingPicker(
                label = "Speech playback",
                value = state.ttsEngine,
                items = TtsEngine.entries,
                itemLabel = { it.label },
                onSelected = onTtsEngine,
            )
            Text(
                text = when (state.ttsEngine) {
                    TtsEngine.SYSTEM -> "Uses an Android system voice installed on this phone."
                    TtsEngine.GEMINI -> "Uses $GEMINI_TTS_MODEL with the embedded test key."
                },
                color = SayItMuted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun <T> SettingPicker(
    label: String,
    value: T,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Box {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.18f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(itemLabel(value), modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            onSelected(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun formatClock(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)

private const val API_KEYS_URL = "https://platform.openai.com/api-keys"
private const val BILLING_URL = "https://platform.openai.com/settings/organization/billing/overview"
