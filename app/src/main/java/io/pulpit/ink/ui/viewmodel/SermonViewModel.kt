package io.pulpit.ink.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.pulpit.ink.R
import io.pulpit.ink.data.db.AppDatabase
import io.pulpit.ink.data.model.SermonJob
import io.pulpit.ink.data.model.SermonSegment
import io.pulpit.ink.data.repository.SermonRepository
import io.pulpit.ink.data.repository.TranscriptionRunner
import io.pulpit.ink.data.api.WhisperModelManager
import io.pulpit.ink.data.api.WhisperModelConfig
import io.pulpit.ink.ui.audio.AudioEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns

class SermonViewModel(
    private val context: Context,
    private val repository: SermonRepository
) : ViewModel() {

    private val TAG = "SermonViewModel"
    private val audioEngine = AudioEngine(context)
    private val whisperModelManager = WhisperModelManager(context)

    private val prefs
        get() = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)

    // Search query states
    val searchQuery = MutableStateFlow("")

    // Raw sermons from Database
    private val _allJobs = repository.allJobs

    // Filtered sermons combined with query
    val sermonJobs: StateFlow<List<SermonJob>> = _allJobs
        .combine(searchQuery) { jobs, query ->
            if (query.isBlank()) {
                jobs
            } else {
                jobs.filter {
                    it.title.contains(query, ignoreCase = true) ||
                    (it.summary?.contains(query, ignoreCase = true) ?: false) ||
                    (it.keywords?.contains(query, ignoreCase = true) ?: false)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Sermon detailed navigation
    private val _activeJobId = MutableStateFlow<String?>(null)
    val activeJobId: StateFlow<String?> = _activeJobId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSermonJob: StateFlow<SermonJob?> = _activeJobId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else repository.getJobByIdFlow(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSermonSegments: StateFlow<List<SermonSegment>> = _activeJobId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.getSegmentsFlow(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Recording States
    val isRecording: StateFlow<Boolean> = io.pulpit.ink.ui.audio.AudioRecordingService.isRecording
    val recordingDurationSec: StateFlow<Double> = io.pulpit.ink.ui.audio.AudioRecordingService.durationSec

    // Audio Playback States
    private val _playbackProgress = MutableStateFlow(0)
    val playbackProgress: StateFlow<Int> = _playbackProgress.asStateFlow()

    private val _playbackDuration = MutableStateFlow(0)
    val playbackDuration: StateFlow<Int> = _playbackDuration.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Status Notification Alerts
    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    // Dispatch newly recorded sermon IDs to auto-transition into Detail Screen
    private val _newJobFlow = MutableSharedFlow<String>()
    val newJobFlow: SharedFlow<String> = _newJobFlow.asSharedFlow()

    // Whisper Model State Flows
    val selectedWhisperModel = MutableStateFlow(
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .getString("selected_model", null) ?: run {
                // Default to first downloaded model if available, otherwise tiny
                val downloaded = WhisperModelConfig.values().firstOrNull { whisperModelManager.isModelDownloaded(it) }
                downloaded?.modelKey ?: "tiny"
            }
    )
    
    val isHapticFeedbackEnabled = MutableStateFlow(
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .getBoolean("haptic_feedback_enabled", true)
    )

    val selectedAudioPreset = MutableStateFlow(
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .getString("selected_audio_preset", "sermon") ?: "sermon"
    )

    // Experimental: transcribe incrementally WHILE recording so results are ready almost
    // immediately on stop. Default OFF — the proven record-then-transcribe path stays the
    // default until this is field-verified.
    val isIncrementalTranscriptionEnabled = MutableStateFlow(
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .getBoolean("incremental_transcription", false)
    )
    
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _downloadState = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadState = _downloadState.asStateFlow()

    private val _storageUsage = MutableStateFlow(0L)
    val storageUsage = _storageUsage.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transcriptionProgress = _transcriptionProgress.asStateFlow()

    // First-launch onboarding state
    val onboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_completed", false))

    init {
        refreshWhisperStates()
        viewModelScope.launch {
            // Sweep any jobs left in `Transcribing` state from a previous app run —
            // the transcription runner can't survive process death.
            repository.resetStuckTranscribingJobs()
        }
        viewModelScope.launch {
            SermonRepository.transcriptionProgress.collect { progressMap ->
                _transcriptionProgress.value = progressMap
            }
        }
        viewModelScope.launch {
            io.pulpit.ink.ui.audio.AudioRecordingService.newJobIdFlow.collect { jobId ->
                _newJobFlow.emit(jobId)
            }
        }
        viewModelScope.launch {
            io.pulpit.ink.ui.audio.ModelDownloadService.downloadState.collect { states ->
                val current = _downloadState.value.toMutableMap()
                states.forEach { (k, v) ->
                    current[k] = v
                }
                _downloadState.value = current
                refreshWhisperStates()
            }
        }
        viewModelScope.launch {
            io.pulpit.ink.ui.audio.ModelDownloadService.downloadProgress.collect { progressMap ->
                val current = _downloadProgress.value.toMutableMap()
                progressMap.forEach { (k, v) ->
                    current[k] = v
                }
                _downloadProgress.value = current
            }
        }
    }

    private fun isKoreanLanguage(): Boolean {
        return Locale.getDefault().language == "ko"
    }

    fun selectSermon(jobId: String?) {
        _activeJobId.value = jobId
        // Stop playing any existing audio when shifting context
        stopPlayback()
    }

    /* =========================================================================
       WHISPER MODEL DOWNLOAD & SETTINGS LOGIC
       ========================================================================= */

    fun refreshWhisperStates() {
        viewModelScope.launch {
            val serviceStates = io.pulpit.ink.ui.audio.ModelDownloadService.downloadState.value
            val stateMap = mutableMapOf<String, String>()
            WhisperModelConfig.values().forEach { config ->
                val activeServiceState = serviceStates[config.modelKey]
                if (activeServiceState == "downloading") {
                    stateMap[config.modelKey] = "downloading"
                } else {
                    val isDownloaded = whisperModelManager.isModelDownloaded(config)
                    stateMap[config.modelKey] = if (isDownloaded) "downloaded" else "not_downloaded"
                }
            }
            _downloadState.value = stateMap
            _storageUsage.value = whisperModelManager.getStorageUsageBytes()
        }
    }

    fun cancelWhisperModelDownload(modelKey: String) {
        val intent = android.content.Intent(context, io.pulpit.ink.ui.audio.ModelDownloadService::class.java).apply {
            action = io.pulpit.ink.ui.audio.ModelDownloadService.ACTION_STOP_DOWNLOAD
        }
        context.startService(intent)
        refreshWhisperStates()
    }

    fun cancelSermonTranscription(jobId: String) {
        io.pulpit.ink.data.repository.TranscriptionRunner.cancel(jobId)
    }

    fun selectWhisperModel(modelKey: String) {
        selectedWhisperModel.value = modelKey
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_model", modelKey)
            .apply()
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        isHapticFeedbackEnabled.value = enabled
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("haptic_feedback_enabled", enabled)
            .apply()
    }

    fun setSelectedAudioPreset(preset: String) {
        selectedAudioPreset.value = preset
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_audio_preset", preset)
            .apply()
    }

    fun setIncrementalTranscriptionEnabled(enabled: Boolean) {
        isIncrementalTranscriptionEnabled.value = enabled
        context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("incremental_transcription", enabled)
            .apply()
    }

    fun downloadWhisperModel(modelKey: String) {
        val config = WhisperModelConfig.fromKey(modelKey)
        postUiEvent(context.getString(R.string.toast_download_start, config.modelKey))
        io.pulpit.ink.ui.audio.ModelDownloadService.startDownload(context, modelKey)
    }

    /* =========================================================================
       ONBOARDING + BUNDLED MODEL DELIVERY
       ========================================================================= */

    fun completeOnboarding() {
        onboardingCompleted.value = true
        prefs.edit().putBoolean("onboarding_completed", true).apply()
    }

    /**
     * Starts downloading the default (base) model during onboarding, unless it
     * is already present or downloading. Progress surfaces via
     * [downloadState] / [downloadProgress] keyed by "base". Idempotent.
     */
    fun ensureBaseModelForOnboarding() {
        if (whisperModelManager.isModelDownloaded(WhisperModelConfig.BASE)) {
            selectWhisperModel("base")
            refreshWhisperStates()
            return
        }
        if (downloadState.value["base"] == "downloading") return
        downloadWhisperModel("base")
    }

    fun deleteWhisperModel(modelKey: String) {
        val config = WhisperModelConfig.fromKey(modelKey)
        val success = whisperModelManager.deleteModel(config)
        if (success) {
            postUiEvent(context.getString(R.string.toast_delete_success, config.modelKey))
        }
        refreshWhisperStates()
    }

    /* =========================================================================
       AUDIO RECORDING LOGIC
       ========================================================================= */

    fun startRecording(title: String = "", topic: String = "") {
        if (isRecording.value) return
        val intent = android.content.Intent(context, io.pulpit.ink.ui.audio.AudioRecordingService::class.java).apply {
            action = io.pulpit.ink.ui.audio.AudioRecordingService.ACTION_START
            putExtra(io.pulpit.ink.ui.audio.AudioRecordingService.EXTRA_TITLE, title)
            putExtra(io.pulpit.ink.ui.audio.AudioRecordingService.EXTRA_TOPIC, topic)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun stopRecordingAndSave(title: String, sermonTheme: String? = null) {
        if (!isRecording.value) return
        val intent = android.content.Intent(context, io.pulpit.ink.ui.audio.AudioRecordingService::class.java).apply {
            action = io.pulpit.ink.ui.audio.AudioRecordingService.ACTION_STOP
            putExtra(io.pulpit.ink.ui.audio.AudioRecordingService.EXTRA_TITLE, title)
            putExtra(io.pulpit.ink.ui.audio.AudioRecordingService.EXTRA_TOPIC, sermonTheme ?: "")
        }
        context.startService(intent)
    }

    fun cancelRecording() {
        if (!isRecording.value) return
        val intent = android.content.Intent(context, io.pulpit.ink.ui.audio.AudioRecordingService::class.java).apply {
            action = io.pulpit.ink.ui.audio.AudioRecordingService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    /* =========================================================================
       AUDIO IMPORT LOGIC
       ========================================================================= */

    fun importAudioFile(uri: Uri) {
        viewModelScope.launch {
            try {
                // 1. Get original filename and extension
                val (originalName, ext) = getFileNameAndExtension(context, uri)
                val finalTitle = originalName.trim().ifBlank {
                    if (isKoreanLanguage()) "가져온 설교" else "Imported Sermon"
                }

                // 2. Generate unique local cache path
                val localFile = File(context.filesDir, "imported_sermon_${System.currentTimeMillis()}.$ext")

                // 3. Copy audio stream to local app files directory
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!localFile.exists() || localFile.length() == 0L) {
                    postUiEvent(context.getString(R.string.toast_import_failed))
                    return@launch
                }

                // 4. Retrieve audio duration using MediaMetadataRetriever
                var durationSec = 0.0
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    durationSec = durationMs / 1000.0
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get duration using retriever, estimating by file size", e)
                    durationSec = (localFile.length() / 12000.0).coerceAtLeast(1.0)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }

                // 5. Create a new SermonJob row in the DB
                val jobId = repository.createNewJob(
                    title = finalTitle,
                    audioPath = localFile.absolutePath,
                    durationSec = durationSec
                )

                // 6. Trigger auto-navigation to DetailScreen
                _newJobFlow.emit(jobId)

                // 7. Enqueue transcription job using selected/fallback Whisper model
                triggerTranscription(jobId)

                postUiEvent(context.getString(R.string.toast_import_success, finalTitle))
            } catch (e: Exception) {
                Log.e(TAG, "Error importing audio file", e)
                postUiEvent(context.getString(R.string.toast_import_failed))
            }
        }
    }

    private fun getFileNameAndExtension(context: Context, uri: Uri): Pair<String, String> {
        var name = "imported_sermon_${System.currentTimeMillis()}"
        var ext = "mp3"

        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val displayName = it.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        val dotIndex = displayName.lastIndexOf('.')
                        if (dotIndex != -1) {
                            name = displayName.substring(0, dotIndex)
                            ext = displayName.substring(dotIndex + 1)
                        } else {
                            name = displayName
                        }
                    }
                }
            }
        }
        return Pair(name, ext)
    }

    /* =========================================================================
       API TRANSCRIPTION & POST-PROCESSING ENGINE
       ========================================================================= */

    fun triggerTranscription(jobId: String, themeHint: String? = null) {
        val modelKey = selectedWhisperModel.value
        val config = WhisperModelConfig.fromKey(modelKey)
        val hasAnyModel = WhisperModelConfig.values().any { whisperModelManager.isModelDownloaded(it) }

        if (!whisperModelManager.isModelDownloaded(config) && !hasAnyModel) {
            postUiEvent(context.getString(R.string.transcription_failed))
            viewModelScope.launch {
                repository.markJobFailed(
                    jobId,
                    if (isKoreanLanguage())
                        "설치된 전사 엔진 모델이 없습니다. 설정에서 먼저 다운로드해 주세요."
                    else
                        "No transcription model is installed. Please download one from settings first."
                )
            }
            return
        }
        postUiEvent(context.getString(R.string.status_transcribing))
        // Submit to the process-wide runner so the work survives ViewModel destruction.
        TranscriptionRunner.submit(
            context = context,
            jobId = jobId,
            topicHint = themeHint,
            modelKey = modelKey
        )
    }

    fun deleteSermon(jobId: String) {
        viewModelScope.launch {
            repository.deleteJob(jobId)
            if (_activeJobId.value == jobId) {
                _activeJobId.value = null
            }
            postUiEvent(context.getString(R.string.rec_removed))
        }
    }

    fun editSegment(segmentId: Int, newText: String) {
        viewModelScope.launch {
            repository.updateSegmentText(segmentId, newText)
        }
    }

    fun runAutoRefCorrection(jobId: String) {
        viewModelScope.launch {
            postUiEvent(context.getString(R.string.toast_proofreading))
            val success = repository.runAutoCorrect(jobId)
            if (success) {
                postUiEvent(context.getString(R.string.toast_proofread_success))
            } else {
                postUiEvent(context.getString(R.string.toast_proofread_failed))
            }
        }
    }

    fun runRegenerateOutline(jobId: String) {
        viewModelScope.launch {
            postUiEvent(context.getString(R.string.toast_outline_compiling))
            val success = repository.regenerateOutline(jobId)
            if (success) {
                postUiEvent(context.getString(R.string.toast_outline_success))
            } else {
                postUiEvent(context.getString(R.string.toast_outline_failed))
            }
        }
    }

    /* =========================================================================
       AUDIO PLAYBACK HANDLERS
       ========================================================================= */

    fun startPlayback(audioPath: String) {
        audioEngine.startPlayback(
            filePath = audioPath,
            onStart = { duration ->
                _playbackDuration.value = duration
                _isPlaying.value = true
            },
            onProgress = { position ->
                _playbackProgress.value = position
            },
            onCompletion = {
                _isPlaying.value = false
                _playbackProgress.value = 0
            }
        )
    }

    fun togglePlayback(audioPath: String) {
        if (_isPlaying.value) {
            audioEngine.pausePlayback()
            _isPlaying.value = false
        } else {
            if (_playbackProgress.value > 0 && _playbackProgress.value < _playbackDuration.value) {
                audioEngine.resumePlayback { position ->
                    _playbackProgress.value = position
                }
                _isPlaying.value = true
            } else {
                startPlayback(audioPath)
            }
        }
    }

    fun seekPlayback(positionMs: Int) {
        audioEngine.seekTo(positionMs)
        _playbackProgress.value = positionMs
    }

    fun stopPlayback() {
        audioEngine.stopPlayback()
        _isPlaying.value = false
        _playbackProgress.value = 0
    }

    private fun postUiEvent(msg: String) {
        viewModelScope.launch {
            _uiEvents.emit(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stopPlayback()
    }
}

// Simple Factory provider
class SermonViewModelFactory(
    private val context: Context,
    private val repository: SermonRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SermonViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SermonViewModel(context, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
