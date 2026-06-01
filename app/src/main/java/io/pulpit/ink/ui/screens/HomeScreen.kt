package io.pulpit.ink.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pulpit.ink.R
import io.pulpit.ink.data.model.SermonJob
import io.pulpit.ink.data.api.WhisperModelConfig
import io.pulpit.ink.ui.viewmodel.SermonViewModel
import io.pulpit.ink.ui.theme.bounceClickable
import java.text.SimpleDateFormat
import java.util.*

enum class SettingsScreen {
    MAIN,
    ENGINE_MODELS,
    PREPROCESSING
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: SermonViewModel,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sermonJobs by viewModel.sermonJobs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf<SermonJob?>(null) }
    var showWhisperManager by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importAudioFile(uri)
        }
    }

    // Elegant Dark Theme Palette
    val deepInk = Color(0xFFE6E1E5) // Clean premium white/silver
    val parchmentBackground = Color(0xFF1C1B1F) // Deep obsidian black
    val primaryEmerald = Color(0xFFD0BCFF) // Gorgeous warm theme lavender
    val softSlate = Color(0xFFCAC4D0) // Muted lavender grey

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.MenuBook,
                                contentDescription = null,
                                tint = primaryEmerald,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.app_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = deepInk,
                                modifier = Modifier.testTag("app_title")
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = softSlate,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { filePickerLauncher.launch("audio/*") },
                        modifier = Modifier.testTag("import_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.import_audio),
                            tint = primaryEmerald,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(
                        onClick = { showWhisperManager = true },
                        modifier = Modifier.testTag("whisper_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Whisper Settings",
                            tint = primaryEmerald,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = parchmentBackground,
                    scrolledContainerColor = parchmentBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToRecord,
                containerColor = primaryEmerald,
                contentColor = Color(0xFF381E72),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("record_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.record_sermon)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.record_sermon),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        containerColor = parchmentBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // High-Contrast Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_field"),
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = softSlate
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = softSlate
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = null,
                                tint = softSlate
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = deepInk,
                    unfocusedTextColor = deepInk,
                    focusedBorderColor = primaryEmerald,
                    unfocusedBorderColor = Color(0xFF49454F),
                    focusedContainerColor = Color(0xFF2B2930),
                    unfocusedContainerColor = Color(0xFF2B2930)
                )
            )

            if (sermonJobs.isEmpty()) {
                // Refined empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFF2B2930),
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = primaryEmerald,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) stringResource(R.string.empty_pulpit_title) else stringResource(R.string.empty_search_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = deepInk
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) {
                                stringResource(R.string.empty_pulpit_desc)
                            } else {
                                stringResource(R.string.empty_search_desc)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = softSlate,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp)
                ) {
                    items(sermonJobs, key = { it.id }) { job ->
                        SermonItemCard(
                            job = job,
                            onClick = {
                                viewModel.selectSermon(job.id)
                                onNavigateToDetail(job.id)
                            },
                            onLongClick = { showDeleteConfirmDialog = job }
                        )
                    }
                }
            }
        }
    }

    // Deletion Dialog
    showDeleteConfirmDialog?.let { job ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = {
                Text(
                    text = stringResource(R.string.delete_dialog_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.delete_dialog_desc, job.title),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSermon(job.id)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text(text = stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    // Whisper Model Downloader & Storage Dashboard Manager Dialog
    if (showWhisperManager) {
        val storageUsage by viewModel.storageUsage.collectAsState()
        val downloadStates by viewModel.downloadState.collectAsState()
        val downloadProgresses by viewModel.downloadProgress.collectAsState()
        val selectedModel by viewModel.selectedWhisperModel.collectAsState()
        val hapticEnabled by viewModel.isHapticFeedbackEnabled.collectAsState()
        val selectedPreset by viewModel.selectedAudioPreset.collectAsState()
        val incrementalEnabled by viewModel.isIncrementalTranscriptionEnabled.collectAsState()
        val isKo = java.util.Locale.getDefault().language == "ko"

        var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }

        AlertDialog(
            onDismissRequest = { 
                showWhisperManager = false 
                currentScreen = SettingsScreen.MAIN
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (currentScreen != SettingsScreen.MAIN) {
                        IconButton(
                            onClick = { currentScreen = SettingsScreen.MAIN },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = primaryEmerald
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.SettingsVoice,
                            contentDescription = null,
                            tint = primaryEmerald,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when (currentScreen) {
                            SettingsScreen.MAIN -> if (isKo) "음성 필기 & 고급 설정" else "Voice & Advanced Settings"
                            SettingsScreen.ENGINE_MODELS -> if (isKo) "필기 엔진 모델 설정" else "Engine Model Settings"
                            SettingsScreen.PREPROCESSING -> if (isKo) "음성 전처리 필터 설정" else "Audio Preprocessing"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = deepInk
                    )
                }
            },
            text = {
                // Respect in-dialog depth: when inside a sub-screen, system back
                // should return to MAIN instead of dismissing the whole dialog.
                // Composed inside the dialog window so it intercepts the dialog's
                // own dismiss-on-back handler.
                BackHandler(enabled = currentScreen != SettingsScreen.MAIN) {
                    currentScreen = SettingsScreen.MAIN
                }
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState == SettingsScreen.MAIN) {
                            slideInHorizontally { -it } + fadeIn() togetherWith
                                    slideOutHorizontally { it } + fadeOut()
                        } else {
                            slideInHorizontally { it } + fadeIn() togetherWith
                                    slideOutHorizontally { -it } + fadeOut()
                        }
                    },
                    label = "SettingsDepthTransition"
                ) { screen ->
                    when (screen) {
                        SettingsScreen.MAIN -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 450.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val currentModelLabel = when (selectedModel) {
                                    "tiny" -> if (isKo) "Whisper Tiny (초경량)" else "Whisper Tiny"
                                    "base" -> if (isKo) "Whisper Base (표준)" else "Whisper Base"
                                    "small" -> if (isKo) "Whisper Small (고정밀)" else "Whisper Small"
                                    else -> if (isKo) "선택 안 됨" else "Not Selected"
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bounceClickable { currentScreen = SettingsScreen.ENGINE_MODELS }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isKo) "필기 엔진 모델 설정" else "Engine Models",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = deepInk
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isKo) {
                                                    "현재 모델: $currentModelLabel • 저장소: ${formatBytesToMB(storageUsage)} 사용"
                                                } else {
                                                    "Model: $currentModelLabel • Storage: ${formatBytesToMB(storageUsage)}"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = softSlate
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Navigate",
                                            tint = primaryEmerald,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                val currentPresetLabel = when (selectedPreset) {
                                    "none" -> if (isKo) "전처리 없음" else "None"
                                    "stt_basic" -> if (isKo) "STT 기본 최적화" else "Basic"
                                    "sermon" -> if (isKo) "설교 최적화 (권장)" else "Sermon (Recommended)"
                                    "noisy" -> if (isKo) "강력 노이즈 감쇄" else "Heavy Noise"
                                    else -> if (isKo) "선택 안 됨" else "Not Selected"
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bounceClickable { currentScreen = SettingsScreen.PREPROCESSING }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isKo) "음성 전처리 필터 설정" else "Audio Preprocessing",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = deepInk
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isKo) "현재 적용: $currentPresetLabel" else "Preset: $currentPresetLabel",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = softSlate
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "Navigate",
                                            tint = primaryEmerald,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isKo) "터치 햅틱 피드백" else "Touch Haptic Feedback",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = deepInk
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isKo) "버튼을 클릭할 때 모래알 같은 미세 정밀 진동을 제공합니다." else "Provides tiny micro-haptic clicks when tapping buttons.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = softSlate
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Switch(
                                            checked = hapticEnabled,
                                            onCheckedChange = { viewModel.setHapticFeedbackEnabled(it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = primaryEmerald,
                                                checkedTrackColor = primaryEmerald.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isKo) "녹음 중 실시간 필기 (실험적)" else "Live transcription while recording (Experimental)",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = deepInk
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isKo)
                                                    "녹음하는 동안 미리 필기를 진행해, 녹음을 멈추면 거의 기다림 없이 결과가 나옵니다. 최신 기기일수록 부드럽게 동작하며, 배터리·발열이 늘 수 있습니다."
                                                else
                                                    "Transcribes as you record so results are ready almost instantly when you stop. Smoothest on recent devices; may increase battery use and heat.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = softSlate
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Switch(
                                            checked = incrementalEnabled,
                                            onCheckedChange = { viewModel.setIncrementalTranscriptionEnabled(it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = primaryEmerald,
                                                checkedTrackColor = primaryEmerald.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = primaryEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.whisper_info),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = deepInk,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                        SettingsScreen.ENGINE_MODELS -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 450.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.storage_used),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = softSlate
                                        )
                                        Text(
                                            text = formatBytesToMB(storageUsage),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryEmerald
                                        )
                                    }
                                }

                                WhisperModelConfig.values().forEach { config ->
                                    val state = downloadStates[config.modelKey] ?: "not_downloaded"
                                    val progress = downloadProgresses[config.modelKey] ?: 0
                                    val isSelected = selectedModel == config.modelKey && state == "downloaded"

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) Color(0xFF2E2445) else Color(0xFF211F24)
                                        ),
                                        border = BorderStroke(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) primaryEmerald else Color(0xFF49454F).copy(alpha = 0.5f)
                                        ),
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = stringResource(config.titleResId),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = deepInk
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "(${config.sizeDisplay})",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = softSlate
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = stringResource(config.descResId),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = softSlate,
                                                        lineHeight = 16.sp
                                                    )
                                                }

                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(Color(0xFF0F5234))
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.selected_label),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFFA7F3D0),
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            when (state) {
                                                "downloaded" -> {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (!isSelected) {
                                                            Button(
                                                                onClick = { viewModel.selectWhisperModel(config.modelKey) },
                                                                colors = ButtonDefaults.buttonColors(containerColor = primaryEmerald),
                                                                shape = RoundedCornerShape(12.dp),
                                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                                                modifier = Modifier.height(32.dp)
                                                            ) {
                                                                Text(
                                                                    text = stringResource(R.string.select_label),
                                                                    style = MaterialTheme.typography.labelMedium,
                                                                    color = Color(0xFF381E72)
                                                                )
                                                            }
                                                        } else {
                                                            Spacer(modifier = Modifier.width(1.dp))
                                                        }

                                                        IconButton(
                                                            onClick = { viewModel.deleteWhisperModel(config.modelKey) },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete Model",
                                                                tint = MaterialTheme.colorScheme.error,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                "downloading" -> {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = stringResource(R.string.downloading, progress),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = primaryEmerald
                                                            )
                                                            TextButton(
                                                                onClick = { viewModel.cancelWhisperModelDownload(config.modelKey) },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text(
                                                                    text = if (isKo) "다운로드 취소" else "Cancel",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.error
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        LinearProgressIndicator(
                                                            progress = progress / 100f,
                                                            color = primaryEmerald,
                                                            trackColor = Color(0xFF334155),
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(6.dp)
                                                                .clip(RoundedCornerShape(3.dp))
                                                        )
                                                    }
                                                }
                                                else -> {
                                                    Button(
                                                        onClick = { viewModel.downloadWhisperModel(config.modelKey) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2930)),
                                                        border = BorderStroke(1.dp, Color(0xFF49454F)),
                                                        shape = RoundedCornerShape(12.dp),
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = null,
                                                                tint = primaryEmerald,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = stringResource(R.string.download_button, config.sizeDisplay),
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = primaryEmerald
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        SettingsScreen.PREPROCESSING -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 450.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = if (isKo) "음성 전처리 필터 설정" else "Audio Preprocessing Preset",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = deepInk
                                        )
                                        Text(
                                            text = if (isKo) "Whisper 전사 품질을 높이기 위해 디코딩 시점의 오디오 잡음과 주파수를 보정합니다." else "Enhances Whisper transcription accuracy by preprocessing room reverb, volume, and static noise.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = softSlate,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                        )

                                        val presetsList = listOf(
                                            Triple("none", if (isKo) "전처리 없음" else "None", if (isKo) "기본 포맷 변환만 수행" else "Raw Audio"),
                                            Triple("stt_basic", if (isKo) "STT 기본 최적화" else "Basic", if (isKo) "저역/고역 대역 차단 필터" else "Band Filter"),
                                            Triple("sermon", if (isKo) "설교 최적화 (권장)" else "Sermon", if (isKo) "소음 차단 및 음성 강도 증폭" else "Voice Boost"),
                                            Triple("noisy", if (isKo) "강력 노이즈 감쇄" else "Noisy", if (isKo) "주변 기계 소음 제거용 강력 필터" else "Heavy Noise")
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            presetsList.forEach { (presetKey, titleText, descText) ->
                                                val isSelected = selectedPreset == presetKey
                                                Surface(
                                                    onClick = { viewModel.setSelectedAudioPreset(presetKey) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isSelected) primaryEmerald.copy(alpha = 0.15f) else Color.Transparent,
                                                    border = BorderStroke(
                                                        width = 1.dp,
                                                        color = if (isSelected) primaryEmerald else Color(0xFF49454F)
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        RadioButton(
                                                            selected = isSelected,
                                                            onClick = { viewModel.setSelectedAudioPreset(presetKey) },
                                                            colors = RadioButtonDefaults.colors(selectedColor = primaryEmerald)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = titleText,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isSelected) primaryEmerald else deepInk
                                                            )
                                                            Text(
                                                                text = descText,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = if (isSelected) primaryEmerald.copy(alpha = 0.8f) else softSlate,
                                                                modifier = Modifier.padding(top = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showWhisperManager = false 
                        currentScreen = SettingsScreen.MAIN
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryEmerald)
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF381E72)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SermonItemCard(
    job: SermonJob,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val inkBack = Color(0xFF2B2930)
    val borderCol = Color(0xFF49454F).copy(alpha = 0.3f)
    val emeraldAccent = Color(0xFFD0BCFF)
    val textDeep = Color(0xFFE6E1E5)
    val textMuted = Color(0xFFCAC4D0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClickable(
                onLongClick = onLongClick,
                onClick = onClick
            )
            .testTag("sermon_card_${job.id}"),
        colors = CardDefaults.cardColors(containerColor = inkBack),
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderCol),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Title and Date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textDeep,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTimestamp(job.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted
                        )
                    }
                }

                // Dynamic Status Badge
                StatusBadge(status = job.status)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sermon Details Snippets
            if (!job.summary.isNullOrBlank()) {
                Text(
                    text = job.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Duration and Scripture tags footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatDuration(job.durationSec),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = textMuted
                    )
                }

                // If Bible Verse keys detected, display prominent badge
                if (!job.bibleRefs.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF49454F).copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = null,
                            tint = emeraldAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = job.bibleRefs.split(",").firstOrNull()?.trim() ?: "Refs",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = textDeep
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val (backColor, textColor, labelId) = when (status) {
        "Recording" -> Triple(Color(0xFF5A1616), Color(0xFFFFB4AB), R.string.status_recording)
        "Transcribing" -> Triple(Color(0xFF624000), Color(0xFFFFE082), R.string.status_transcribing)
        "Done" -> Triple(Color(0xFF0F5234), Color(0xFFA7F3D0), R.string.status_done)
        "Failed" -> Triple(Color(0xFF5A1616), Color(0xFFFFB4AB), R.string.status_failed)
        else -> Triple(Color(0xFF49454F), Color(0xFFCAC4D0), R.string.status_queue)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(backColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(labelId),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun formatDuration(durationSec: Double): String {
    val totalSeconds = durationSec.toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun formatBytesToMB(bytes: Long): String {
    return String.format(Locale.getDefault(), "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
}
