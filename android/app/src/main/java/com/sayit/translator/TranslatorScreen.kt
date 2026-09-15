package com.sayit.translator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var settingsOpen by remember { mutableStateOf(false) }
    var expandedPhrase by remember { mutableStateOf<ExpandedPhrase?>(null) }
    var pendingSide by remember { mutableStateOf<LanguageSide?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val side = pendingSide
        pendingSide = null
        if (granted && side != null) viewModel.tapMicrophone(side)
        else if (!granted) viewModel.reportPermissionDenied()
    }

    val onMicrophone: (LanguageSide) -> Unit = { side ->
        if (context.hasMicrophonePermission()) {
            viewModel.tapMicrophone(side)
        } else {
            pendingSide = side
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    expandedPhrase?.let { phrase ->
        PhraseDetailScreen(
            phrase = phrase,
            onCopy = { copyText(context, phrase.text) },
            onClose = { expandedPhrase = null },
        )
        return
    }

    if (settingsOpen) {
        SettingsScreen(
            state = state,
            onSaveKey = viewModel::saveApiKey,
            onDeleteKey = viewModel::deleteApiKey,
            onTranslationOption = viewModel::setTranslationOption,
            onTtsEngine = viewModel::setTtsEngine,
            onSttEngine = viewModel::setSttEngine,
            onBack = { settingsOpen = false },
        )
        return
    }

    val sourceSide = state.activeSide
        ?: state.resultSide?.let { if (it == LanguageSide.A) LanguageSide.B else LanguageSide.A }
        ?: LanguageSide.A
    val targetSide = if (sourceSide == LanguageSide.A) LanguageSide.B else LanguageSide.A
    val sourceLanguage = if (sourceSide == LanguageSide.A) state.languageA else state.languageB
    val targetLanguage = if (targetSide == LanguageSide.A) state.languageA else state.languageB
    val sourceText = if (sourceSide == LanguageSide.A) state.textA else state.textB
    val targetText = if (targetSide == LanguageSide.A) state.textA else state.textB
    val sourceColor = if (sourceSide == LanguageSide.A) SayItBlue else SayItRed
    val targetColor = if (targetSide == LanguageSide.A) SayItBlue else SayItRed
    val sourceBackground = if (sourceSide == LanguageSide.A) SayItBlueSoft else SayItRedSoft
    val targetBackground = if (targetSide == LanguageSide.A) SayItBlueSoft else SayItRedSoft
    val sourceLabel = "Source · ${sourceLanguage.nativeName}"
    val targetLabel = "Translation · ${targetLanguage.nativeName}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SayItPaper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(onSettings = { settingsOpen = true })

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguagePicker(
                modifier = Modifier.weight(1f),
                selected = state.languageA,
                excluded = state.languageB,
                color = SayItBlue,
                background = SayItBlueSoft,
                enabled = state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR,
                onSelected = { viewModel.setLanguage(LanguageSide.A, it) },
            )
            Surface(
                onClick = viewModel::swapLanguages,
                enabled = state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.14f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = "Swap languages",
                        tint = SayItInk,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            LanguagePicker(
                modifier = Modifier.weight(1f),
                selected = state.languageB,
                excluded = state.languageA,
                color = SayItRed,
                background = SayItRedSoft,
                enabled = state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR,
                onSelected = { viewModel.setLanguage(LanguageSide.B, it) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpeechButton(
                modifier = Modifier.weight(1f),
                side = LanguageSide.A,
                language = state.languageA,
                color = SayItBlue,
                status = state.status,
                activeSide = state.activeSide,
                onMicrophone = { onMicrophone(LanguageSide.A) },
            )
            SpeechButton(
                modifier = Modifier.weight(1f),
                side = LanguageSide.B,
                language = state.languageB,
                color = SayItRed,
                status = state.status,
                activeSide = state.activeSide,
                onMicrophone = { onMicrophone(LanguageSide.B) },
            )
        }

        TurnProgress(
            status = state.status,
            hasResult = state.resultSide != null,
            elapsedSeconds = state.elapsedSeconds,
            translationLabel = TranslationOption.from(state).label,
            onStopPlayback = viewModel::stopPlayback,
        )

        PhraseCard(
            label = sourceLabel,
            text = sourceText,
            color = sourceColor,
            background = sourceBackground,
            canReplay = false,
            canCopy = false,
            onReplay = viewModel::replay,
            onCopy = {},
            onOpen = {
                expandedPhrase = ExpandedPhrase(
                    label = sourceLabel,
                    text = sourceText,
                    color = sourceColor,
                    background = sourceBackground,
                    canCopy = false,
                )
            },
        )
        PhraseCard(
            label = targetLabel,
            text = targetText,
            color = targetColor,
            background = targetBackground,
            canReplay = state.resultSide == targetSide && state.status == VoiceStatus.READY,
            canCopy = true,
            onReplay = viewModel::replay,
            onCopy = { copyText(context, targetText) },
            onOpen = {
                expandedPhrase = ExpandedPhrase(
                    label = targetLabel,
                    text = targetText,
                    color = targetColor,
                    background = targetBackground,
                    canCopy = true,
                )
            },
        )

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

}

@Composable
private fun TurnProgress(
    status: VoiceStatus,
    hasResult: Boolean,
    elapsedSeconds: Int,
    translationLabel: String,
    onStopPlayback: () -> Unit,
) {
    val activeIndex = when (status) {
        VoiceStatus.LISTENING, VoiceStatus.RECOGNIZING -> 0
        VoiceStatus.TRANSLATING -> 1
        VoiceStatus.SPEAKING -> 2
        VoiceStatus.READY -> if (hasResult) 3 else -1
        VoiceStatus.ERROR -> -1
    }
    val labels = listOf("Speech", translationLabel, "Playback")

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
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier.weight(if (index == 1) 1.6f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ProgressDot(
                            completed = activeIndex == 3 || index < activeIndex,
                            active = index == activeIndex,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = label,
                            color = if (activeIndex == 3 || index <= activeIndex) {
                                SayItInk
                            } else {
                                SayItMuted
                            },
                            fontSize = if (index == 1) 9.sp else 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index < labels.lastIndex) {
                        ProgressConnector(completed = index < activeIndex)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(40.dp)
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
            .size(12.dp)
            .background(color, CircleShape)
            .border(2.dp, borderColor, CircleShape),
    )
}

@Composable
private fun ProgressConnector(completed: Boolean) {
    Box(
        modifier = Modifier
            .width(8.dp)
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
private fun SpeechButton(
    modifier: Modifier,
    side: LanguageSide,
    language: AppLanguage,
    color: Color,
    status: VoiceStatus,
    activeSide: LanguageSide?,
    onMicrophone: () -> Unit,
) {
    val listeningHere = status == VoiceStatus.LISTENING && activeSide == side
    val busy = status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR, VoiceStatus.LISTENING)
    val disabled = busy || (status == VoiceStatus.LISTENING && !listeningHere)

    Button(
        onClick = onMicrophone,
        enabled = !disabled,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
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
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (listeningHere) language.stopLabel else language.speakLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LanguagePicker(
    modifier: Modifier = Modifier,
    selected: AppLanguage,
    excluded: AppLanguage,
    color: Color,
    background: Color,
    enabled: Boolean,
    onSelected: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(18.dp),
            color = background,
            border = BorderStroke(2.dp, color.copy(alpha = 0.66f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(31.dp)
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
                Spacer(Modifier.width(7.dp))
                Text(
                    text = selected.nativeName,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
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
    label: String,
    text: String,
    color: Color,
    background: Color,
    canReplay: Boolean,
    canCopy: Boolean,
    onReplay: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        enabled = text.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 138.dp),
        shape = RoundedCornerShape(24.dp),
        color = background.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
        shadowElevation = 5.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 19.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.isBlank()) {
                Text(
                    text = "Your text will appear here",
                    color = SayItMuted.copy(alpha = 0.62f),
                    fontSize = 14.sp,
                )
            } else {
                Text(
                    text = text,
                    fontFamily = FontFamily.Serif,
                    fontSize = 29.sp,
                    lineHeight = 35.sp,
                )
            }
            if (canCopy || canReplay) {
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canCopy) {
                        OutlinedButton(
                            onClick = onCopy,
                            enabled = text.isNotBlank(),
                            modifier = Modifier.height(52.dp),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("Copy", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (canReplay) {
                        Button(
                            onClick = onReplay,
                            modifier = Modifier.height(52.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = color,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("Replay", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private data class ExpandedPhrase(
    val label: String,
    val text: String,
    val color: Color,
    val background: Color,
    val canCopy: Boolean,
)

@Composable
private fun PhraseDetailScreen(
    phrase: ExpandedPhrase,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(phrase.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = phrase.label,
                modifier = Modifier.weight(1f),
                color = phrase.color,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close full-screen text",
                    tint = SayItInk,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Text(
                text = phrase.text,
                color = SayItInk,
                fontFamily = FontFamily.Serif,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            )
        }

        if (phrase.canCopy) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .height(52.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, phrase.color.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text("Copy", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: TranslatorUiState,
    onSaveKey: (String) -> Boolean,
    onDeleteKey: () -> Unit,
    onTranslationOption: (TranslationOption) -> Unit,
    onTtsEngine: (TtsEngine) -> Unit,
    onSttEngine: (SttEngine) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var openAiKeyDialog by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SayItPaper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                color = SayItInk,
                fontFamily = FontFamily.Serif,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsSection(
                title = "Speech recognition",
                description = "Turns speech into text before translation.",
            ) {
                SettingPicker(
                    label = "Engine",
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
            }

            SettingsSection(
                title = "Translation",
                description = "Choose the model used for every translated phrase.",
            ) {
                SettingPicker(
                    label = "Model",
                    value = TranslationOption.from(state),
                    items = TranslationOption.entries,
                    itemLabel = { it.label },
                    onSelected = onTranslationOption,
                )
                Text(
                    text = when (state.translationEngine) {
                        TranslationEngine.OPENAI -> "Reasoning is disabled for low-latency spoken translation."
                        TranslationEngine.GEMINI -> "Uses minimal thinking for low-latency spoken translation."
                    },
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
                if (state.translationEngine == TranslationEngine.OPENAI && !state.hasOpenAiApiKey) {
                    Text(
                        text = "Add an OpenAI API key below before using this model.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }

            SettingsSection(
                title = "Speech playback",
                description = "Controls how the translated phrase is spoken aloud.",
            ) {
                SettingPicker(
                    label = "Engine",
                    value = state.ttsEngine,
                    items = TtsEngine.entries,
                    itemLabel = { it.label },
                    onSelected = onTtsEngine,
                )
                Text(
                    text = when (state.ttsEngine) {
                        TtsEngine.SYSTEM -> "Uses an Android system voice installed on this phone."
                        TtsEngine.GEMINI -> "Uses $GEMINI_TTS_MODEL with the packaged test key."
                    },
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
            }

            SettingsSection(
                title = "API keys & connection",
                description = "Keys are never displayed. Packaged service keys are configured at build time.",
            ) {
                KeyStatusRow(
                    label = "Gemini API key",
                    configured = BuildConfig.GEMINI_API_KEY.isNotBlank(),
                )
                KeyStatusRow(
                    label = "Groq API key",
                    configured = BuildConfig.GROQ_API_KEY.isNotBlank(),
                )
                KeyStatusRow(
                    label = "OpenAI API key",
                    configured = state.hasOpenAiApiKey,
                    onClick = { openAiKeyDialog = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEYS_URL)))
                        },
                    ) { Text("Create key") }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BILLING_URL)))
                        },
                    ) { Text("Billing") }
                }
            }
        }
    }

    if (openAiKeyDialog) {
        OpenAiKeyDialog(
            hasKey = state.hasOpenAiApiKey,
            onSaveKey = onSaveKey,
            onDeleteKey = onDeleteKey,
            onDismiss = { openAiKeyDialog = false },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.62f),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.09f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = SayItInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(description, color = SayItMuted, fontSize = 13.sp, lineHeight = 18.sp)
            content()
        }
    }
}

@Composable
private fun KeyStatusRow(
    label: String,
    configured: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = SayItInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (configured) "Configured" else "Not configured",
                color = if (configured) SayItInk.copy(alpha = 0.66f) else MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SayItMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (onClick == null) {
        Surface(
            color = SayItPaper.copy(alpha = 0.72f),
            shape = RoundedCornerShape(15.dp),
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            color = SayItPaper.copy(alpha = 0.72f),
            shape = RoundedCornerShape(15.dp),
            content = content,
        )
    }
}

@Composable
private fun OpenAiKeyDialog(
    hasKey: Boolean,
    onSaveKey: (String) -> Boolean,
    onDeleteKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasKey) "Replace OpenAI API key" else "Add OpenAI API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (hasKey) {
                        "A key is already stored securely on this device. Enter a new one to replace it."
                    } else {
                        "The key is stored securely on this device and is never shown again."
                    },
                    color = SayItMuted,
                    fontSize = 13.sp,
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = apiKey.isNotBlank(),
                onClick = {
                    if (onSaveKey(apiKey)) onDismiss()
                },
            ) {
                Text(if (hasKey) "Replace" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (hasKey) {
                    TextButton(
                        onClick = {
                            onDeleteKey()
                            onDismiss()
                        },
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Say it translation", text))
}

private const val API_KEYS_URL = "https://platform.openai.com/api-keys"
private const val BILLING_URL = "https://platform.openai.com/settings/organization/billing/overview"
