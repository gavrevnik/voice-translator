package com.sayit.translator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
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
    val languagePackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshAndroidLanguagePacks()
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

    val openSettings = {
        viewModel.refreshAndroidLanguagePacks()
        settingsOpen = true
    }
    val openSttSettings = {
        runCatching {
            languagePackLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        Unit
    }
    val installTts: (AppLanguage) -> Unit = { language ->
        viewModel.createAndroidTtsInstallIntent(language)?.let(languagePackLauncher::launch)
    }

    if (settingsOpen) {
        SettingsScreen(
            state = state,
            onTranslationOption = viewModel::setTranslationOption,
            onSerbianScript = viewModel::setSerbianScript,
            onDownloadOfflineModel = viewModel::downloadOfflineModel,
            onDeleteOfflineModel = viewModel::deleteOfflineModel,
            onDownloadWhisperModel = viewModel::downloadWhisperModel,
            onDeleteWhisperModel = viewModel::deleteWhisperModel,
            onSttEngine = viewModel::setSttEngine,
            onLayoutMode = viewModel::setLayoutMode,
            onSilenceAutoStopSeconds = viewModel::setSilenceAutoStopSeconds,
            onDownloadSttPack = viewModel::downloadAndroidSpeechPack,
            onRefreshSpeechPacks = viewModel::refreshAndroidLanguagePacks,
            onOpenSttSettings = {
                runCatching {
                    languagePackLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            },
            onInstallTtsPack = { language ->
                viewModel.createAndroidTtsInstallIntent(language)?.let(languagePackLauncher::launch)
            },
            onExportLogs = {
                viewModel.createDiagnosticsShareIntent()?.let { shareIntent ->
                    context.startActivity(
                        Intent.createChooser(shareIntent, "Share last cycle logs"),
                    )
                }
            },
            onBack = { settingsOpen = false },
        )
        return
    }

    if (state.layoutMode == LayoutMode.CONVERSATION) {
        ConversationModeScreen(
            state = state,
            onMicrophone = onMicrophone,
            onLanguage = viewModel::setLanguage,
            onToggleMode = viewModel::toggleLayoutMode,
            onOpenSettings = openSettings,
            onReplay = viewModel::replay,
            onStopPlayback = viewModel::stopPlayback,
            onDownloadStt = viewModel::downloadAndroidSpeechPack,
            onRefreshPacks = viewModel::refreshAndroidLanguagePacks,
            onOpenSttSettings = openSttSettings,
            onInstallTts = installTts,
            onOpenText = { side, rotationDegrees ->
                val language = if (side == LanguageSide.A) state.languageA else state.languageB
                val text = if (side == LanguageSide.A) state.textA else state.textB
                val color = if (side == LanguageSide.A) SayItBlue else SayItRed
                val background = if (side == LanguageSide.A) SayItBlueSoft else SayItRedSoft
                expandedPhrase = ExpandedPhrase(
                    label = language.nativeName,
                    text = text,
                    color = color,
                    background = background,
                    canCopy = false,
                    rotationDegrees = rotationDegrees,
                )
            },
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
    val sourceLabel = sourceLanguage.nativeName
    val targetLabel = targetLanguage.nativeName

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
        MainControlRow(
            state = state,
            onToggleMode = viewModel::toggleLayoutMode,
            onOpenSettings = openSettings,
            onStopPlayback = viewModel::stopPlayback,
        )

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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        AndroidLanguagePackActions(
            state = state,
            onDownloadStt = viewModel::downloadAndroidSpeechPack,
            onRefresh = viewModel::refreshAndroidLanguagePacks,
            onOpenSttSettings = openSttSettings,
            onInstallTts = installTts,
        )

        PhraseCard(
            label = sourceLabel,
            text = sourceText,
            color = sourceColor,
            background = sourceBackground,
            isPartial = state.partialTranscriptSide == sourceSide,
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
            isPartial = false,
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
private fun MainControlRow(
    state: TranslatorUiState,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    val modeCanChange = state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TurnProgress(
            modifier = Modifier.weight(1f),
            status = state.status,
            hasResult = state.resultSide != null,
            elapsedSeconds = state.elapsedSeconds,
            recognitionLabel = state.sttEngine.progressLabel,
            translationLabel = TranslationOption.from(state).progressLabel,
            playbackLabel = PLAYBACK_PROGRESS_LABEL,
            onStopPlayback = onStopPlayback,
        )
        IconButton(
            onClick = onToggleMode,
            enabled = modeCanChange,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (state.layoutMode == LayoutMode.CONVERSATION) {
                        SayItInk
                    } else {
                        Color.White.copy(alpha = 0.55f)
                    },
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(
                Icons.Filled.People,
                contentDescription = if (state.layoutMode == LayoutMode.CONVERSATION) {
                    "Switch to single mode"
                } else {
                    "Conversation mode"
                },
                tint = if (state.layoutMode == LayoutMode.CONVERSATION) Color.White else SayItInk,
                modifier = Modifier.size(21.dp),
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ConversationModeScreen(
    state: TranslatorUiState,
    onMicrophone: (LanguageSide) -> Unit,
    onLanguage: (LanguageSide, AppLanguage) -> Unit,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
    onDownloadStt: (AppLanguage) -> Unit,
    onRefreshPacks: () -> Unit,
    onOpenSttSettings: () -> Unit,
    onInstallTts: (AppLanguage) -> Unit,
    onOpenText: (LanguageSide, Float) -> Unit,
) {
    var sidesSwapped by rememberSaveable { mutableStateOf(false) }
    val topSide = if (sidesSwapped) LanguageSide.B else LanguageSide.A
    val bottomSide = if (sidesSwapped) LanguageSide.A else LanguageSide.B
    val topLanguage = if (topSide == LanguageSide.A) state.languageA else state.languageB
    val bottomLanguage = if (bottomSide == LanguageSide.A) state.languageA else state.languageB
    val topText = if (topSide == LanguageSide.A) state.textA else state.textB
    val bottomText = if (bottomSide == LanguageSide.A) state.textA else state.textB
    val topColor = if (topSide == LanguageSide.A) SayItBlue else SayItRed
    val bottomColor = if (bottomSide == LanguageSide.A) SayItBlue else SayItRed
    val topBackground = if (topSide == LanguageSide.A) SayItBlueSoft else SayItRedSoft
    val bottomBackground = if (bottomSide == LanguageSide.A) SayItBlueSoft else SayItRedSoft
    val controlsEnabled = state.status == VoiceStatus.READY || state.status == VoiceStatus.ERROR
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val symmetricVerticalPadding = maxOf(
        safeDrawingPadding.calculateTopPadding(),
        safeDrawingPadding.calculateBottomPadding(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SayItPaper)
            .padding(horizontal = 12.dp)
            .padding(
                top = symmetricVerticalPadding + 8.dp,
                bottom = symmetricVerticalPadding + 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConversationParticipantPanel(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { rotationZ = 180f },
            side = topSide,
            language = topLanguage,
            excludedLanguage = bottomLanguage,
            text = topText,
            color = topColor,
            background = topBackground,
            state = state,
            popupRotationDegrees = 180f,
            onMicrophone = { onMicrophone(topSide) },
            onLanguage = { onLanguage(topSide, it) },
            onOpenText = { onOpenText(topSide, 180f) },
            onReplay = onReplay,
            onStopPlayback = onStopPlayback,
        )

        ConversationControlRow(
            onOpenSettings = onOpenSettings,
            onExitConversation = onToggleMode,
            onSwapSides = { if (controlsEnabled) sidesSwapped = !sidesSwapped },
            enabled = controlsEnabled,
        )

        AndroidLanguagePackActions(
            state = state,
            onDownloadStt = onDownloadStt,
            onRefresh = onRefreshPacks,
            onOpenSttSettings = onOpenSttSettings,
            onInstallTts = onInstallTts,
        )

        state.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ConversationParticipantPanel(
            modifier = Modifier.weight(1f),
            side = bottomSide,
            language = bottomLanguage,
            excludedLanguage = topLanguage,
            text = bottomText,
            color = bottomColor,
            background = bottomBackground,
            state = state,
            onMicrophone = { onMicrophone(bottomSide) },
            onLanguage = { onLanguage(bottomSide, it) },
            onOpenText = { onOpenText(bottomSide, 0f) },
            onReplay = onReplay,
            onStopPlayback = onStopPlayback,
        )
    }
}

@Composable
private fun ConversationControlRow(
    onOpenSettings: () -> Unit,
    onExitConversation: () -> Unit,
    onSwapSides: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationCentralControl(
            onClick = onOpenSettings,
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
        )
        ConversationCentralControl(
            onClick = onExitConversation,
            enabled = enabled,
            active = true,
            imageVector = Icons.Filled.People,
            contentDescription = "Exit conversation mode",
        )
        ConversationCentralControl(
            onClick = onSwapSides,
            enabled = enabled,
            imageVector = Icons.Filled.SwapVert,
            contentDescription = "Swap conversation sides",
        )
    }
}

@Composable
private fun ConversationCentralControl(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        !enabled -> SayItControlDisabled
        active && pressed -> SayItControlDarkPressed
        active -> SayItControlDark
        pressed -> SayItControlLightPressed
        else -> SayItControlLight
    }
    val iconColor = when {
        !enabled -> SayItControlDisabledIcon
        active -> Color.White
        else -> SayItControlDark
    }
    val borderColor = if (active && enabled) SayItControlDark else SayItControlBorder
    val shape = RoundedCornerShape(8.dp)

    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(26.dp)
            .background(background, shape)
            .border(1.dp, borderColor, shape),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ConversationParticipantPanel(
    modifier: Modifier,
    side: LanguageSide,
    language: AppLanguage,
    excludedLanguage: AppLanguage,
    text: String,
    color: Color,
    background: Color,
    state: TranslatorUiState,
    popupRotationDegrees: Float = 0f,
    onMicrophone: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onOpenText: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    val isTranslatedSide = state.resultSide == side && text.isNotBlank()
    val isPlayingTranslation = isTranslatedSide && state.status == VoiceStatus.SPEAKING
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConversationTextCard(
            modifier = Modifier.weight(1f),
            text = text,
            color = color,
            background = background,
            isPartial = state.partialTranscriptSide == side,
            showPlaybackControl = isTranslatedSide &&
                state.status in listOf(VoiceStatus.READY, VoiceStatus.SPEAKING),
            isPlayingTranslation = isPlayingTranslation,
            onOpen = onOpenText,
            onReplay = onReplay,
            onStopPlayback = onStopPlayback,
        )
        ConversationSpeechControl(
            side = side,
            language = language,
            excludedLanguage = excludedLanguage,
            color = color,
            status = state.status,
            activeSide = state.activeSide,
            popupRotationDegrees = popupRotationDegrees,
            onMicrophone = onMicrophone,
            onLanguage = onLanguage,
        )
    }
}

@Composable
private fun ConversationTextCard(
    modifier: Modifier,
    text: String,
    color: Color,
    background: Color,
    isPartial: Boolean,
    showPlaybackControl: Boolean,
    isPlayingTranslation: Boolean,
    onOpen: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        enabled = text.isNotBlank(),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = background.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val defaultFontSize = 25f
            val minimumFontSize = defaultFontSize / 2f
            var adaptiveFontSize by remember(text, maxHeight) {
                mutableStateOf(defaultFontSize)
            }
            val lineHeight = adaptiveFontSize * 1.24f
            val density = LocalDensity.current
            val availableTextHeight = (maxHeight - 32.dp).coerceAtLeast(1.dp)
            val maxLinesAtCurrentSize = with(density) {
                (availableTextHeight.toPx() / lineHeight.sp.toPx())
                    .toInt()
                    .coerceAtLeast(1)
            }
            val minimumFontReached = adaptiveFontSize <= minimumFontSize
            val scrollState = rememberScrollState()
            val textModifier = Modifier
                .fillMaxSize()
                .then(
                    if (minimumFontReached) Modifier.verticalScroll(scrollState) else Modifier,
                )
                .padding(
                    start = 18.dp,
                    top = 16.dp,
                    end = if (showPlaybackControl) 56.dp else 18.dp,
                    bottom = if (showPlaybackControl) 52.dp else 16.dp,
                )

            if (text.isNotBlank()) {
                Box(
                    modifier = textModifier,
                    contentAlignment = if (minimumFontReached) {
                        Alignment.TopStart
                    } else {
                        Alignment.CenterStart
                    },
                ) {
                    Text(
                        text = text,
                        color = if (isPartial) SayItInk.copy(alpha = 0.58f) else SayItInk,
                        fontFamily = FontFamily.Serif,
                        fontSize = adaptiveFontSize.sp,
                        lineHeight = lineHeight.sp,
                        maxLines = if (minimumFontReached) Int.MAX_VALUE else maxLinesAtCurrentSize,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow && !minimumFontReached) {
                                adaptiveFontSize = (adaptiveFontSize - 1f)
                                    .coerceAtLeast(minimumFontSize)
                            }
                        },
                    )
                }
            }

            if (showPlaybackControl) {
                IconButton(
                    onClick = if (isPlayingTranslation) onStopPlayback else onReplay,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(38.dp)
                        .background(
                            if (isPlayingTranslation) SayItInk else Color.White.copy(alpha = 0.74f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPlayingTranslation) {
                            Icons.Filled.Stop
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (isPlayingTranslation) {
                            "Stop translation playback"
                        } else {
                            "Replay translation"
                        },
                        tint = if (isPlayingTranslation) Color.White else color,
                        modifier = Modifier.size(if (isPlayingTranslation) 18.dp else 21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationSpeechControl(
    side: LanguageSide,
    language: AppLanguage,
    excludedLanguage: AppLanguage,
    color: Color,
    status: VoiceStatus,
    activeSide: LanguageSide?,
    popupRotationDegrees: Float,
    onMicrophone: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
) {
    val listeningHere = status == VoiceStatus.LISTENING && activeSide == side
    val busy = status !in listOf(VoiceStatus.READY, VoiceStatus.ERROR, VoiceStatus.LISTENING)
    val speechDisabled = busy || (status == VoiceStatus.LISTENING && !listeningHere)
    val languageEnabled = status == VoiceStatus.READY || status == VoiceStatus.ERROR
    var languageMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onMicrophone,
            enabled = !speechDisabled,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
                bottomStart = 18.dp,
            ),
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
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (listeningHere) language.stopLabel else language.speakLabel,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .width(68.dp)
                .height(56.dp),
        ) {
            Surface(
                onClick = { if (languageEnabled) languageMenuExpanded = true },
                enabled = languageEnabled,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 18.dp,
                    bottomEnd = 18.dp,
                    bottomStart = 0.dp,
                ),
                color = if (languageEnabled) color else color.copy(alpha = 0.42f),
                contentColor = Color.White,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color.White.copy(alpha = 0.48f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = language.code.uppercase(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Change ${language.nativeName} language",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false },
                modifier = Modifier.graphicsLayer { rotationZ = popupRotationDegrees },
            ) {
                AppLanguage.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.nativeName) },
                        enabled = option != excludedLanguage,
                        onClick = {
                            onLanguage(option)
                            languageMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnProgress(
    modifier: Modifier = Modifier,
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
    val labels = listOf(recognitionLabel, translationLabel, playbackLabel)
    val labelWeights = listOf(1.45f, 1.15f, 0.9f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier.weight(labelWeights[index]),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ProgressDot(
                            completed = activeIndex == 3 || index < activeIndex,
                            active = index == activeIndex,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = label,
                            color = if (activeIndex == 3 || index <= activeIndex) {
                                SayItInk
                            } else {
                                SayItMuted
                            },
                            fontSize = if (index == 0 && label.length > 14) 5.sp else 6.sp,
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
            if (status == VoiceStatus.LISTENING || status == VoiceStatus.SPEAKING) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
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
            .size(10.dp)
            .background(color, CircleShape)
            .border(2.dp, borderColor, CircleShape),
    )
}

@Composable
private fun AndroidLanguagePackActions(
    state: TranslatorUiState,
    onDownloadStt: (AppLanguage) -> Unit,
    onRefresh: () -> Unit,
    onOpenSttSettings: () -> Unit,
    onInstallTts: (AppLanguage) -> Unit,
) {
    val languages = listOf(state.languageA, state.languageB)
    val sttMissing = if (state.sttEngine == SttEngine.SYSTEM) {
        languages.filter { language ->
            state.androidSttLanguagePacks[language]?.isInstalled != true
        }
    } else {
        emptyList()
    }
    val ttsMissing = languages.filter { language ->
        state.androidTtsLanguagePacks[language]?.isInstalled != true
    }
    if (sttMissing.isEmpty() && ttsMissing.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.1f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            sttMissing.forEach { language ->
                val status = state.androidSttLanguagePacks[language]
                    ?: AndroidLanguagePackStatus.CHECKING
                AndroidPackAction(
                    prefix = "Speech",
                    language = language,
                    status = status,
                    actionLabel = when (status.availability) {
                        AndroidLanguagePackAvailability.DOWNLOADABLE -> "Download"
                        AndroidLanguagePackAvailability.SETUP_REQUIRED -> "Initialize"
                        AndroidLanguagePackAvailability.SCHEDULED -> "Check again"
                        AndroidLanguagePackAvailability.ONLINE_ONLY,
                        AndroidLanguagePackAvailability.UNSUPPORTED,
                        AndroidLanguagePackAvailability.UNKNOWN,
                        AndroidLanguagePackAvailability.ERROR,
                        -> "Open settings"
                        else -> null
                    },
                    onAction = when (status.availability) {
                        AndroidLanguagePackAvailability.DOWNLOADABLE -> {
                            { onDownloadStt(language) }
                        }
                        AndroidLanguagePackAvailability.SETUP_REQUIRED -> onOpenSttSettings
                        AndroidLanguagePackAvailability.SCHEDULED -> onRefresh
                        AndroidLanguagePackAvailability.ONLINE_ONLY,
                        AndroidLanguagePackAvailability.UNSUPPORTED,
                        AndroidLanguagePackAvailability.UNKNOWN,
                        AndroidLanguagePackAvailability.ERROR,
                        -> onOpenSttSettings
                        else -> null
                    },
                )
            }
            ttsMissing.forEach { language ->
                val status = state.androidTtsLanguagePacks[language]
                    ?: AndroidLanguagePackStatus.CHECKING
                AndroidPackAction(
                    prefix = "Voice",
                    language = language,
                    status = status,
                    actionLabel = when (status.availability) {
                        AndroidLanguagePackAvailability.CHECKING -> null
                        AndroidLanguagePackAvailability.SETUP_REQUIRED -> "Initialize"
                        else -> "Install"
                    },
                    onAction = if (
                        status.availability == AndroidLanguagePackAvailability.CHECKING
                    ) {
                        null
                    } else {
                        { onInstallTts(language) }
                    },
                )
            }
        }
    }
}

private enum class AndroidSpeechPackageKind(val label: String) {
    STT("Speech-to-text (STT)"),
    TTS("Text-to-speech (TTS)"),
}

@Composable
private fun AndroidSpeechPackagesSettings(
    state: TranslatorUiState,
    onDownloadStt: (AppLanguage) -> Unit,
    onRefresh: () -> Unit,
    onOpenSttSettings: () -> Unit,
    onInstallTts: (AppLanguage) -> Unit,
) {
    var selectedKind by remember { mutableStateOf(AndroidSpeechPackageKind.STT) }
    SettingPicker(
        value = selectedKind,
        items = AndroidSpeechPackageKind.entries,
        itemLabel = { it.label },
        onSelected = { selectedKind = it },
    )
    Text(
        text = when (selectedKind) {
            AndroidSpeechPackageKind.STT ->
                "Available offline packages from installed Samsung or Google speech services. " +
                    "A check mark means the package is ready on this phone."
            AndroidSpeechPackageKind.TTS ->
                "Offline voices from Samsung, Google, and Piper Serbian ONNX. " +
                    "A check mark means the voice is ready on this phone."
        },
        color = SayItMuted,
        fontSize = 13.sp,
    )

    val statuses = when (selectedKind) {
        AndroidSpeechPackageKind.STT -> state.androidSttLanguagePacks
        AndroidSpeechPackageKind.TTS -> state.androidTtsLanguagePacks
    }
    val listedPackages = AppLanguage.entries.mapNotNull { language ->
        statuses[language]
            ?.takeIf(AndroidLanguagePackStatus::isListedPackage)
            ?.let { status -> language to status }
    }

    if (listedPackages.isEmpty()) {
        Text(
            text = when (selectedKind) {
                AndroidSpeechPackageKind.STT ->
                    "No downloadable Samsung or Google STT packages were reported. " +
                        "Package discovery and direct downloads require Android 13 or newer."
                AndroidSpeechPackageKind.TTS ->
                    "No supported Android TTS packages were reported by this phone."
            },
            color = SayItMuted,
            fontSize = 13.sp,
        )
    } else {
        listedPackages.forEach { (language, status) ->
            val actionLabel: String?
            val action: (() -> Unit)?
            when (selectedKind) {
                AndroidSpeechPackageKind.STT -> when (status.availability) {
                    AndroidLanguagePackAvailability.DOWNLOADABLE -> {
                        actionLabel = "Download"
                        action = { onDownloadStt(language) }
                    }
                    AndroidLanguagePackAvailability.SCHEDULED -> {
                        actionLabel = "Check again"
                        action = onRefresh
                    }
                    else -> {
                        actionLabel = null
                        action = null
                    }
                }
                AndroidSpeechPackageKind.TTS -> when (status.availability) {
                    AndroidLanguagePackAvailability.DOWNLOADABLE -> {
                        actionLabel = "Download"
                        action = { onInstallTts(language) }
                    }
                    AndroidLanguagePackAvailability.SETUP_REQUIRED -> {
                        actionLabel = "Initialize"
                        action = { onInstallTts(language) }
                    }
                    else -> {
                        actionLabel = null
                        action = null
                    }
                }
            }
            AndroidPackAction(
                prefix = selectedKind.name,
                language = language,
                status = status,
                actionLabel = actionLabel,
                onAction = action,
            )
        }
    }

    if (selectedKind == AndroidSpeechPackageKind.STT && listedPackages.isEmpty()) {
        TextButton(onClick = onOpenSttSettings) {
            Text("Open Android voice input settings", fontWeight = FontWeight.Bold)
        }
    }
    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Refresh package list")
    }
    if (selectedKind == AndroidSpeechPackageKind.TTS) {
        Text(
            text = "Android has no universal API for a silent per-language TTS download. " +
                "Download opens the selected provider's installer. The Serbian Piper option " +
                "downloads the official sherpa-onnx TTS Engine APK with the voice built in; " +
                "install it, then return here to refresh.",
            color = SayItMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AndroidPackAction(
    prefix: String,
    language: AppLanguage,
    status: AndroidLanguagePackStatus,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val provider = status.providerName.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    val description = when (status.availability) {
        AndroidLanguagePackAvailability.CHECKING -> "Checking"
        AndroidLanguagePackAvailability.DOWNLOADING ->
            "Downloading ${status.progressPercent ?: 0}%"
        AndroidLanguagePackAvailability.SETUP_REQUIRED -> "Initialization required"
        AndroidLanguagePackAvailability.SCHEDULED -> "Download scheduled"
        AndroidLanguagePackAvailability.ONLINE_ONLY -> "Offline pack unavailable"
        AndroidLanguagePackAvailability.UNSUPPORTED -> "Not supported offline"
        AndroidLanguagePackAvailability.UNKNOWN -> "Status unavailable"
        AndroidLanguagePackAvailability.ERROR -> "Check failed"
        AndroidLanguagePackAvailability.DOWNLOADABLE -> "Offline pack missing"
        AndroidLanguagePackAvailability.INSTALLED -> "Installed"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$prefix · ${language.nativeName}$provider · $description",
            modifier = Modifier.weight(1f),
            color = SayItMuted,
            fontSize = 11.sp,
        )
        if (status.isInstalled) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Downloaded",
                tint = SayItReady,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProgressConnector(completed: Boolean) {
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(2.dp)
            .background(if (completed) SayItInk else SayItMuted.copy(alpha = 0.25f)),
    )
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
            .height(112.dp),
        shape = RoundedCornerShape(20.dp),
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
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = if (listeningHere) language.stopLabel else language.speakLabel,
            fontSize = 18.sp,
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
    popupRotationDegrees: Float = 0f,
    onSelected: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var nameFontSize by remember(selected) { mutableStateOf(15.sp) }
    Box(modifier = modifier) {
        Surface(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            color = background,
            border = BorderStroke(2.dp, color.copy(alpha = 0.66f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selected.code.uppercase(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = selected.nativeName,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Serif,
                    fontSize = nameFontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (result.hasVisualOverflow && nameFontSize.value > 9f) {
                            nameFontSize = (nameFontSize.value - 1f).sp
                        }
                    },
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.graphicsLayer { rotationZ = popupRotationDegrees },
        ) {
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
    isPartial: Boolean,
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
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 19.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = if (isPartial) "$label · partial" else label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    color = if (isPartial) SayItInk.copy(alpha = 0.58f) else SayItInk,
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
    val rotationDegrees: Float = 0f,
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
            .graphicsLayer { rotationZ = phrase.rotationDegrees }
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
    onTranslationOption: (TranslationOption) -> Unit,
    onSerbianScript: (SerbianScript) -> Unit,
    onDownloadOfflineModel: () -> Unit,
    onDeleteOfflineModel: () -> Unit,
    onDownloadWhisperModel: () -> Unit,
    onDeleteWhisperModel: () -> Unit,
    onSttEngine: (SttEngine) -> Unit,
    onLayoutMode: (LayoutMode) -> Unit,
    onSilenceAutoStopSeconds: (Int) -> Unit,
    onDownloadSttPack: (AppLanguage) -> Unit,
    onRefreshSpeechPacks: () -> Unit,
    onOpenSttSettings: () -> Unit,
    onInstallTtsPack: (AppLanguage) -> Unit,
    onExportLogs: () -> Unit,
    onBack: () -> Unit,
) {
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
                title = "Recognition",
            ) {
                SettingPicker(
                    value = state.sttEngine,
                    items = SttEngine.entries,
                    itemLabel = { it.label },
                    itemEnabled = { engine ->
                        !engine.isWhisperOffline() || state.whisperRuntimeAvailable
                    },
                    onSelected = onSttEngine,
                )
                if (!state.whisperRuntimeAvailable) {
                    Text(
                        text = "Whisper Offline requires a 64-bit ARM Android phone.",
                        color = SayItMuted,
                        fontSize = 13.sp,
                    )
                }
                if (state.sttEngine.isWhisperOffline()) {
                    OfflineModelPanel(
                        title = "Whisper Large V3 Turbo Q4_0",
                        subtitle = "On-device recognition after Stop",
                        status = state.whisperModelStatus,
                        downloadSizeLabel = state.whisperModelDownloadSizeLabel,
                        onDownload = onDownloadWhisperModel,
                        onDelete = onDeleteWhisperModel,
                    )
                }
            }

            SettingsSection(
                title = "Translation",
            ) {
                SettingPicker(
                    value = TranslationOption.from(state),
                    items = TranslationOption.entries,
                    itemLabel = { it.label },
                    itemEnabled = { option ->
                        !option.engine.isOfflineOpus() ||
                            (isOfflineOpusDirection(
                                option.engine,
                                state.languageA,
                                state.languageB,
                            ) && state.offlineRuntimeAvailable)
                    },
                    onSelected = onTranslationOption,
                )
                if (!isOfflineSlavicDirection(state.languageA, state.languageB)) {
                    Text(
                        text = "Offline OPUS supports Russian ↔ Serbian/Croatian.",
                        color = SayItMuted,
                        fontSize = 13.sp,
                    )
                }
                if (!state.offlineRuntimeAvailable) {
                    Text(
                        text = "Offline OPUS requires arm64 and Android 9 or newer.",
                        color = SayItMuted,
                        fontSize = 13.sp,
                    )
                }
                if (
                    state.translationEngine == TranslationEngine.OFFLINE_OPUS_SLAVIC &&
                    (state.languageA == AppLanguage.SERBIAN ||
                        state.languageB == AppLanguage.SERBIAN)
                ) {
                    Text(
                        text = "Serbian output",
                        color = SayItMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SettingPicker(
                        value = state.serbianScript,
                        items = SerbianScript.entries,
                        itemLabel = { it.label },
                        onSelected = onSerbianScript,
                    )
                }
                if (state.offlineRuntimeAvailable) {
                    Text(
                        text = "Offline model downloads",
                        color = SayItMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OfflineModelPanel(
                        title = "OPUS Slavic FP32",
                        subtitle = "Russian ↔ Serbian / Croatian",
                        status = state.offlineModelStatus,
                        downloadSizeLabel = state.offlineModelDownloadSizeLabel,
                        onDownload = onDownloadOfflineModel,
                        onDelete = onDeleteOfflineModel,
                    )
                }
            }

            SettingsSection(
                title = "Playback",
            ) {
                SettingPicker(
                    value = PlaybackEngine.ANDROID_SPEECH,
                    items = PlaybackEngine.entries,
                    itemLabel = { it.label },
                    onSelected = {},
                )
            }

            SettingsSection(
                title = "Android speech packages",
            ) {
                AndroidSpeechPackagesSettings(
                    state = state,
                    onDownloadStt = onDownloadSttPack,
                    onRefresh = onRefreshSpeechPacks,
                    onOpenSttSettings = onOpenSttSettings,
                    onInstallTts = onInstallTtsPack,
                )
            }

            SettingsSection(
                title = "Automatic stop",
            ) {
                Text(
                    text = "After speech starts, Groq Whisper stops and begins translation " +
                        "when this pause is reached. The Stop button remains available.",
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
                var secondsText by remember(state.silenceAutoStopSeconds) {
                    mutableStateOf(state.silenceAutoStopSeconds.toString())
                }
                val seconds = secondsText.toIntOrNull()
                val valid = seconds != null &&
                    seconds in MIN_SILENCE_AUTO_STOP_SECONDS..MAX_SILENCE_AUTO_STOP_SECONDS
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { updated ->
                        if (updated.length <= 2 && updated.all { it.isDigit() }) {
                            secondsText = updated
                            updated.toIntOrNull()
                                ?.takeIf {
                                    it in MIN_SILENCE_AUTO_STOP_SECONDS..
                                        MAX_SILENCE_AUTO_STOP_SECONDS
                                }
                                ?.let(onSilenceAutoStopSeconds)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pause duration") },
                    suffix = { Text("seconds") },
                    supportingText = {
                        Text(
                            "Allowed range: $MIN_SILENCE_AUTO_STOP_SECONDS–" +
                                "$MAX_SILENCE_AUTO_STOP_SECONDS seconds",
                        )
                    },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            SettingsSection(
                title = "Layout",
            ) {
                SettingPicker(
                    value = state.layoutMode,
                    items = LayoutMode.entries,
                    itemLabel = { it.label },
                    onSelected = onLayoutMode,
                )
            }

            SettingsSection(
                title = "Diagnostics",
            ) {
                Text(
                    text = "Exports the latest voice cycle only. Audio, speech text, " +
                        "translations, and API keys are not included.",
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onExportLogs,
                    enabled = state.hasLastDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Export logs")
                }
                if (!state.hasLastDiagnostics) {
                    Text(
                        text = "Complete or attempt a voice cycle to create a log.",
                        color = SayItMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

}

@Composable
private fun SettingsSection(
    title: String,
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
            content()
        }
    }
}

@Composable
private fun <T> SettingPicker(
    value: T,
    items: List<T>,
    itemLabel: (T) -> String,
    itemEnabled: (T) -> Boolean = { true },
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
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
                val enabled = itemEnabled(item)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item),
                            color = if (enabled) SayItInk else SayItMuted.copy(alpha = 0.55f),
                        )
                    },
                    enabled = enabled,
                    onClick = {
                        onSelected(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OfflineModelPanel(
    title: String,
    subtitle: String,
    status: OfflineModelStatus,
    downloadSizeLabel: String,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SayItPaper.copy(alpha = 0.72f),
        shape = RoundedCornerShape(15.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = SayItInk,
                fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = SayItMuted, fontSize = 13.sp)
            when (status) {
                OfflineModelStatus.NotInstalled -> Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download model ($downloadSizeLabel)")
                }

                is OfflineModelStatus.Downloading -> {
                    val percent = (status.progress * 100).toInt()
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloading · $percent%", color = SayItMuted, fontSize = 13.sp)
                }

                is OfflineModelStatus.Installed -> {
                    Text(
                        "Installed · Works offline · ${status.installedSizeLabel}",
                        color = SayItInk.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = onDelete) { Text("Delete model") }
                }

                is OfflineModelStatus.Invalid -> {
                    Text(status.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download again ($downloadSizeLabel)")
                    }
                    TextButton(onClick = onDelete) { Text("Delete model") }
                }

                is OfflineModelStatus.Error -> {
                    Text(status.reason, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry download ($downloadSizeLabel)")
                    }
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
