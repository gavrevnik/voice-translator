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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    if (state.liveModeActive) {
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    var settingsOpen by remember { mutableStateOf(false) }
    var expandedPhrase by remember { mutableStateOf<ExpandedPhrase?>(null) }
    var pendingSide by remember { mutableStateOf<LanguageSide?>(null) }
    var pendingLiveSide by remember { mutableStateOf<LanguageSide?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val side = pendingSide
        val liveSide = pendingLiveSide
        pendingSide = null
        pendingLiveSide = null
        when {
            granted && liveSide != null -> viewModel.toggleLiveMode(liveSide)
            granted && side != null -> viewModel.tapMicrophone(side)
            !granted -> viewModel.reportPermissionDenied()
        }
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
    val onLive: (LanguageSide) -> Unit = { initialSpeakerSide ->
        if (state.liveModeActive || context.hasMicrophonePermission()) {
            viewModel.toggleLiveMode(initialSpeakerSide)
        } else {
            pendingLiveSide = initialSpeakerSide
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
    val installTts: (AppLanguage) -> Unit = { language ->
        viewModel.createAndroidTtsInstallIntent(language)?.let(languagePackLauncher::launch)
    }

    if (settingsOpen) {
        SettingsScreen(
            state = state,
            onTranslationOption = viewModel::setTranslationOption,
            onRefreshSpeechPacks = viewModel::refreshAndroidLanguagePacks,
            onInstallTtsPack = { language ->
                viewModel.createAndroidTtsInstallIntent(language)?.let(languagePackLauncher::launch)
            },
            onExportLogs = {
                viewModel.createDiagnosticsShareIntent()?.let { shareIntent ->
                    context.startActivity(
                        Intent.createChooser(shareIntent, "Share diagnostics logs"),
                    )
                }
            },
            onOpenCloudUsage = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
            onBack = { settingsOpen = false },
        )
        return
    }

    ConversationModeScreen(
        state = state,
        onMicrophone = onMicrophone,
        onLive = onLive,
        onFinishLiveTurn = viewModel::finishCurrentLiveTurn,
        onLanguage = viewModel::setLanguage,
        onOpenSettings = openSettings,
        onReplay = viewModel::replay,
        onStopPlayback = viewModel::stopPlayback,
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
}

@Composable
private fun ConversationModeScreen(
    state: TranslatorUiState,
    onMicrophone: (LanguageSide) -> Unit,
    onLive: (LanguageSide) -> Unit,
    onFinishLiveTurn: () -> Unit,
    onLanguage: (LanguageSide, AppLanguage) -> Unit,
    onOpenSettings: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
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
            onToggleLive = { onLive(bottomSide) },
            onFinishLiveTurn = onFinishLiveTurn,
            onSwapSides = { if (controlsEnabled) sidesSwapped = !sidesSwapped },
            enabled = controlsEnabled,
            liveModeActive = state.liveModeActive,
            liveTurnCanFinish = state.status == VoiceStatus.LISTENING,
        )

        AndroidLanguagePackActions(
            state = state,
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
    onToggleLive: () -> Unit,
    onFinishLiveTurn: () -> Unit,
    onSwapSides: () -> Unit,
    enabled: Boolean,
    liveModeActive: Boolean,
    liveTurnCanFinish: Boolean,
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
            enabled = enabled,
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
        )
        ConversationCentralControl(
            onClick = onToggleLive,
            enabled = enabled || liveModeActive,
            active = liveModeActive,
            label = "LIVE",
            contentDescription = if (liveModeActive) {
                "Stop Conversation Live"
            } else {
                "Start Conversation Live"
            },
        )
        ConversationCentralControl(
            onClick = if (liveModeActive) onFinishLiveTurn else onSwapSides,
            enabled = if (liveModeActive) liveTurnCanFinish else enabled,
            active = liveModeActive && liveTurnCanFinish,
            imageVector = if (liveModeActive) AppIcons.Translate else AppIcons.SwapVert,
            contentDescription = if (liveModeActive) {
                "Finish listening and translate"
            } else {
                "Swap conversation sides"
            },
        )
    }
}

@Composable
private fun ConversationCentralControl(
    onClick: () -> Unit,
    contentDescription: String,
    imageVector: ImageVector? = null,
    label: String? = null,
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
            .width(44.dp)
            .height(30.dp)
            .background(background, shape)
            .border(1.dp, borderColor, shape),
    ) {
        if (label != null) {
            Text(
                text = label,
                color = iconColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(15.dp),
            )
        }
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
            showPlaybackControl = !state.liveModeActive && isTranslatedSide &&
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
                            AppIcons.Stop
                        } else {
                            AppIcons.VolumeUp
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
                imageVector = if (listeningHere) AppIcons.Stop else AppIcons.Mic,
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
private fun AndroidLanguagePackActions(
    state: TranslatorUiState,
    onInstallTts: (AppLanguage) -> Unit,
) {
    val languages = listOf(state.languageA, state.languageB)
    val ttsMissing = languages.filter { language ->
        state.androidTtsLanguagePacks[language]?.isInstalled != true
    }
    if (ttsMissing.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SayItInk.copy(alpha = 0.1f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
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

@Composable
private fun AndroidTtsVoicesSettings(
    state: TranslatorUiState,
    onRefresh: () -> Unit,
    onInstallTts: (AppLanguage) -> Unit,
) {
    Text(
        text = "Offline voices from Samsung, Google, and Piper Serbian ONNX. " +
            "A check mark means the voice is ready on this phone.",
        color = SayItMuted,
        fontSize = 13.sp,
    )

    val listedPackages = AppLanguage.entries.mapNotNull { language ->
        state.androidTtsLanguagePacks[language]
            ?.takeIf(AndroidLanguagePackStatus::isListedPackage)
            ?.let { status -> language to status }
    }

    if (listedPackages.isEmpty()) {
        Text(
            text = "No supported Android TTS packages were reported by this phone.",
            color = SayItMuted,
            fontSize = 13.sp,
        )
    } else {
        listedPackages.forEach { (language, status) ->
            val actionLabel: String?
            val action: (() -> Unit)?
            when (status.availability) {
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
            AndroidPackAction(
                prefix = "TTS",
                language = language,
                status = status,
                actionLabel = actionLabel,
                onAction = action,
            )
        }
    }

    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Refresh package list")
    }
    Text(
        text = "Android has no universal API for a silent per-language TTS download. " +
            "Download opens the selected provider's installer. The Serbian Piper option " +
            "downloads the official sherpa-onnx TTS Engine APK with the voice built in; " +
            "install it, then return here to refresh.",
        color = SayItMuted,
        fontSize = 12.sp,
    )
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
                    AppIcons.ContentCopy,
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
    onRefreshSpeechPacks: () -> Unit,
    onInstallTtsPack: (AppLanguage) -> Unit,
    onExportLogs: () -> Unit,
    onOpenCloudUsage: (String) -> Unit,
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
                title = "Translation",
            ) {
                SettingPicker(
                    value = TranslationOption.from(state),
                    items = TranslationOption.entries,
                    itemLabel = { it.label },
                    onSelected = onTranslationOption,
                )
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
                title = "Android TTS voices",
            ) {
                AndroidTtsVoicesSettings(
                    state = state,
                    onRefresh = onRefreshSpeechPacks,
                    onInstallTts = onInstallTtsPack,
                )
            }

            SettingsSection(
                title = "Automatic stop",
            ) {
                Text(
                    text = "Gemini Transcribe Live uses server voice activity detection with " +
                        "a 0.7-second pause and " +
                        "a local 1-second fallback. Manual translation remains available in Live.",
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
            }

            SettingsSection(
                title = "Diagnostics",
            ) {
                Text(
                    text = "Exports the latest normal voice cycle or the complete Live session " +
                        "between Start and Stop. Audio, speech text, translations, and API " +
                        "keys are not included.",
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

            SettingsSection(
                title = "Cloud usage and limits",
            ) {
                Text(
                    text = "Open the official dashboards and sign in to the account that owns " +
                        "the API key used by this build.",
                    color = SayItMuted,
                    fontSize = 13.sp,
                )
                CloudUsageLink(
                    label = "Gemini usage and rate limits",
                    urlLabel = "aistudio.google.com",
                    url = GEMINI_RATE_LIMITS_URL,
                    onOpen = onOpenCloudUsage,
                )
            }
        }
    }

}

@Composable
private fun CloudUsageLink(
    label: String,
    urlLabel: String,
    url: String,
    onOpen: (String) -> Unit,
) {
    OutlinedButton(
        onClick = { onOpen(url) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = SayItInk,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = urlLabel,
                color = SayItMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Say it translation", text))
}

private const val GEMINI_RATE_LIMITS_URL =
    "https://aistudio.google.com/rate-limit?timeRange=last-28-days"
