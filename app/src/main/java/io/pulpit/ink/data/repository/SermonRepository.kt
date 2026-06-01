package io.pulpit.ink.data.repository

import android.content.Context
import io.pulpit.ink.data.api.SpeechToTextEngineFactory
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.data.api.WhisperModelConfig
import io.pulpit.ink.data.api.WhisperModelManager
import io.pulpit.ink.data.text.OfflineTextProcessor
import io.pulpit.ink.data.text.TranscriptionNotificationHelper
import io.pulpit.ink.data.text.TranscriptCleaner
import io.pulpit.ink.data.db.SermonDao
import io.pulpit.ink.data.model.SermonJob
import io.pulpit.ink.data.model.SermonSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import java.util.UUID

class SermonRepository(private val sermonDao: SermonDao) {

    val allJobs: Flow<List<SermonJob>> = sermonDao.getAllJobsFlow()

    fun getJobByIdFlow(jobId: String): Flow<SermonJob?> = sermonDao.getJobByIdFlow(jobId)

    fun getSegmentsFlow(jobId: String): Flow<List<SermonSegment>> = sermonDao.getSegmentsByJobIdFlow(jobId)

    suspend fun deleteJob(jobId: String) {
        sermonDao.deleteJobById(jobId)
    }

    suspend fun updateSegmentText(segmentId: Int, text: String) {
        sermonDao.updateSegmentText(segmentId, text)
    }

    /**
     * Force a job into the Failed state. Safe to call from cleanup paths — runs in
     * NonCancellable so the DB write completes even if the caller's scope was cancelled.
     */
    suspend fun markJobFailed(jobId: String, message: String) {
        withContext(NonCancellable) {
            val job = sermonDao.getJobById(jobId) ?: return@withContext
            sermonDao.updateJob(job.copy(status = "Failed", errorMessage = message))
        }
    }

    /**
     * Sweep orphaned `Transcribing` jobs at app startup. The transcription runner
     * cannot survive process death, so anything still marked Transcribing at startup
     * must have been interrupted.
     */
    suspend fun resetStuckTranscribingJobs() {
        val stuck = sermonDao.getJobsByStatus("Transcribing")
        if (stuck.isEmpty()) return
        val msg = if (Locale.getDefault().language == "ko")
            "이전 전사 작업이 중단되었습니다. 다시 시도해 주세요."
        else
            "Previous transcription was interrupted. Please retry."
        stuck.forEach { job ->
            sermonDao.updateJob(job.copy(status = "Failed", errorMessage = msg))
        }
    }

    suspend fun createNewJob(title: String, audioPath: String, durationSec: Double): String {
        val jobId = UUID.randomUUID().toString()
        val job = SermonJob(
            id = jobId,
            title = title,
            audioPath = audioPath,
            durationSec = durationSec,
            status = "Pending"
        )
        sermonDao.insertJob(job)
        return jobId
    }

    /**
     * Transcribe audio with Whisper, then post-process entirely offline
     * (summary / outline / scripture / proper nouns / keywords / title).
     * No API key or internet required.
     */
    suspend fun transcribeJob(
        context: Context,
        jobId: String,
        topicHint: String? = null,
        selectedModelKey: String = "base"
    ): Boolean = withContext(Dispatchers.IO) {
        val job = sermonDao.getJobById(jobId) ?: return@withContext false
        val isKo = java.util.Locale.getDefault().language == "ko"
        val title = job.title

        // --- Pre-flight: fail fast and DO NOT mark Transcribing if we can't actually run.
        val audioFile = File(job.audioPath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            markJobFailed(jobId, "Audio file missing or empty: ${job.audioPath}")
            return@withContext false
        }
        if (!WhisperLib.isLibraryLoaded) {
            markJobFailed(
                jobId,
                if (isKo) "전사 엔진(libwhisper.so) 로드 실패. 앱을 재설치해 주세요."
                else "Whisper native library failed to load. Please reinstall the app."
            )
            return@withContext false
        }
        val modelManager = WhisperModelManager(context)
        var modelConfig = WhisperModelConfig.fromKey(selectedModelKey)
        var effectiveModelKey = selectedModelKey
        if (!modelManager.isModelDownloaded(modelConfig)) {
            // Fallback dynamically to any model that is downloaded (e.g. tiny)
            val downloadedFallback = WhisperModelConfig.values().firstOrNull { modelManager.isModelDownloaded(it) }
            if (downloadedFallback != null) {
                modelConfig = downloadedFallback
                effectiveModelKey = downloadedFallback.modelKey
                android.util.Log.i("SermonRepository", "Selected model '$selectedModelKey' is not downloaded. Seamlessly falling back to downloaded model: '$effectiveModelKey'")
            } else {
                markJobFailed(
                    jobId,
                    if (isKo) "Whisper '${modelConfig.modelKey}' 모델이 설치되어 있지 않습니다. 설정에서 먼저 다운로드해 주세요."
                    else "Whisper '${modelConfig.modelKey}' model is not installed. Please download it from settings first."
                )
                return@withContext false
            }
        }

        // All pre-flight checks passed — claim the job.
        sermonDao.updateJob(job.copy(status = "Transcribing", errorMessage = null))

        try {
            updateProgress(jobId, 10)
            TranscriptionNotificationHelper.showProgressNotification(
                context, jobId, title, 10,
                if (isKo) "대기 중..." else "Waiting..."
            )

            updateProgress(jobId, 20)
            TranscriptionNotificationHelper.showProgressNotification(
                context, jobId, title, 20,
                if (isKo) "오디오 디코딩 중..." else "Decoding audio..."
            )

            val engine = SpeechToTextEngineFactory.getEngine(context, effectiveModelKey)

            updateProgress(jobId, 40)
            TranscriptionNotificationHelper.showProgressNotification(
                context, jobId, title, 40,
                if (isKo) "음성 필기 분석 중..." else "Running Whisper transcription..."
            )

            // Whisper inference is the long pole — without progress feedback the
            // notification appears frozen at 40% for minutes. Map the native 0..100
            // callback onto the 40..75% slice of the overall job and throttle to
            // every 1% native change to prevent Binder IPC rate-limiting and make progress smooth.
            var lastPercent = -1
            val tickerJob = launch {
                while (isActive) {
                    delay(2000L) // Smooth tick every 2 seconds
                    val current = transcriptionProgress.value[jobId] ?: 40
                    if (current in 40..73) {
                        val next = current + 1
                        updateProgress(jobId, next)
                        val nativePct = ((next - 40) * 100 / 35).coerceIn(0, 100)
                        TranscriptionNotificationHelper.showProgressNotification(
                            context, jobId, title, next,
                            if (isKo) "음성 필기 분석 중... ($nativePct%)"
                            else "Running Whisper transcription... ($nativePct%)"
                        )
                    }
                }
            }

            val rawTranscript = try {
                engine.transcribe(audioFile, effectiveModelKey) { nativePercent ->
                    when (nativePercent) {
                        -1 -> {
                            updateProgress(jobId, 25)
                            TranscriptionNotificationHelper.showProgressNotification(
                                context, jobId, title, 25,
                                if (isKo) "오디오 디코딩 중..." else "Decoding audio..."
                            )
                        }
                        -2 -> {
                            updateProgress(jobId, 35)
                            TranscriptionNotificationHelper.showProgressNotification(
                                context, jobId, title, 35,
                                if (isKo) "필기 엔진 모델 로딩 중..." else "Loading transcription model..."
                            )
                        }
                        else -> {
                            val coerced = nativePercent.coerceIn(0, 100)
                            if (coerced != lastPercent) {
                                lastPercent = coerced
                                val overall = 40 + (coerced * 35 / 100)
                                updateProgress(jobId, overall)
                                TranscriptionNotificationHelper.showProgressNotification(
                                    context, jobId, title, overall,
                                    if (isKo) "음성 필기 분석 중... ($coerced%)"
                                    else "Running Whisper transcription... ($coerced%)"
                                )
                            }
                        }
                    }
                }
            } finally {
                tickerJob.cancel()
            }

            updateProgress(jobId, 75)
            TranscriptionNotificationHelper.showProgressNotification(
                context, jobId, title, 75,
                if (isKo) "맞춤법 교정 및 요약/아웃라인 가공 중..." else "Proofreading & structuring outline..."
            )

            val cleanedTranscript = TranscriptCleaner.clean(rawTranscript)
            val correctedTranscript = OfflineTextProcessor.correctTranscript(cleanedTranscript)

            // Empty-transcript path: Whisper found no speech (silent/very short
            // recording). Treat as a successful no-op with a placeholder note so the
            // sermon list shows "Done", not a red "Failed" badge.
            if (correctedTranscript.isBlank()) {
                withContext(NonCancellable) {
                    // IMPORTANT: update the job row via `@Update` (not `@Insert REPLACE`),
                    // otherwise the row is delete-and-reinserted and the CASCADE foreign
                    // key on sermon_segments would wipe out the segments we just wrote.
                    sermonDao.updateJob(
                        job.copy(
                            status = "Done",
                            errorMessage = null,
                            summary = if (isKo) "음성에서 인식된 내용이 없어 요약을 생성하지 않았습니다." else "No speech was recognized; nothing to summarize.",
                            outline = null,
                            bibleRefs = null,
                            properNouns = null,
                            keywords = null
                        )
                    )
                    writePlaceholderSegment(jobId, job.durationSec, isKo)
                }
                updateProgress(jobId, 100)
                TranscriptionNotificationHelper.showProgressNotification(
                    context, jobId, title, 100,
                    if (isKo) "음성 인식 결과 없음" else "No speech detected"
                )
                return@withContext true
            }

            val finalTitle = computeOfflineTitle(job.title, correctedTranscript)
            val summary = OfflineTextProcessor.generateSummary(finalTitle, correctedTranscript)
            val refs = OfflineTextProcessor.extractBibleReferences(correctedTranscript)
            val outline = OfflineTextProcessor.generateSermonOutline(finalTitle, correctedTranscript, refs)
            val nouns = OfflineTextProcessor.extractProperNouns(correctedTranscript)
            val keywords = OfflineTextProcessor.extractKeywords(correctedTranscript)

            // Persist final state under NonCancellable so a late scope cancellation can't
            // leave the job stuck at Transcribing with completed text already in memory.
            // Order matters: update the parent row FIRST via `@Update`, then write child
            // segments. `@Insert REPLACE` would delete-and-reinsert the parent, which
            // CASCADEs to wipe the segments we'd just written (foreign key on
            // sermon_segments.jobId). Using `@Update` keeps the row's primary key stable.
            withContext(NonCancellable) {
                sermonDao.updateJob(
                    job.copy(
                        title = finalTitle,
                        status = "Done",
                        errorMessage = null,
                        summary = summary,
                        outline = outline,
                        bibleRefs = refs,
                        properNouns = nouns,
                        keywords = keywords
                    )
                )
                writeSegments(jobId, job.durationSec, correctedTranscript)
            }

            updateProgress(jobId, 100)
            TranscriptionNotificationHelper.showProgressNotification(
                context, jobId, finalTitle, 100,
                if (isKo) "가공 완료!" else "Transcription successful!"
            )
            return@withContext true
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception: OutOfMemoryError (the most common
            // failure on long recordings / low-memory devices) is an Error, so a
            // `catch (Exception)` would silently miss it — leaving the ongoing progress
            // notification frozen in the status bar and never marking the job failed here.
            val isCancelled = t is kotlinx.coroutines.CancellationException
            val errorMsg = when {
                isCancelled ->
                    if (isKo) "사용자가 필기 분석을 중단했습니다." else "Transcription was cancelled by user."
                t is OutOfMemoryError ->
                    if (isKo) "기기 메모리가 부족해 전사를 완료하지 못했습니다. 녹음이 너무 길거나 다른 앱이 메모리를 많이 쓰고 있을 수 있어요. 다른 앱을 닫고 다시 시도해 주세요."
                    else "Ran out of memory while transcribing. The recording may be too long or other apps are using too much memory. Close other apps and try again."
                else -> t.localizedMessage ?: "Transcription failed"
            }

            withContext(NonCancellable) {
                markJobFailed(jobId, errorMsg)
            }

            // On any terminal failure/cancellation, REMOVE the status-bar progress
            // notification rather than leaving a stuck/ongoing entry behind. The failure
            // is already surfaced in-app on the detail screen.
            TranscriptionNotificationHelper.dismissNotification(context, jobId)

            if (t is kotlinx.coroutines.CancellationException) throw t
            return@withContext false
        } finally {
            clearProgress(jobId)
        }
    }

    /**
     * Append a single live "preview" segment as a chunk finishes transcribing during
     * incremental recording, so the detail screen fills in progressively. These preview
     * segments are replaced by the properly-structured ones in [finalizeLiveTranscription].
     */
    suspend fun appendLiveSegment(jobId: String, startSec: Double, endSec: Double, text: String) {
        if (text.isBlank()) return
        val isKo = Locale.getDefault().language == "ko"
        sermonDao.insertSegments(
            listOf(
                SermonSegment(
                    jobId = jobId,
                    startSec = startSec,
                    endSec = endSec,
                    text = text.trim(),
                    speaker = if (isKo) "설교자" else "Preacher"
                )
            )
        )
    }

    /**
     * Finalize an incrementally-transcribed recording: clean + correct the accumulated raw
     * transcript, run the offline NLP (title/summary/outline/refs/nouns/keywords) once, and
     * persist the final job + structured segments. Mirrors the tail of [transcribeJob] so
     * live and post-hoc transcription converge on an identical final result. Runs
     * NonCancellable so a late teardown can't strand the job mid-write.
     */
    suspend fun finalizeLiveTranscription(jobId: String, rawTranscript: String): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val job = sermonDao.getJobById(jobId) ?: return@withContext false
            val isKo = Locale.getDefault().language == "ko"

            val cleaned = TranscriptCleaner.clean(rawTranscript)
            val corrected = OfflineTextProcessor.correctTranscript(cleaned)

            if (corrected.isBlank()) {
                sermonDao.updateJob(
                    job.copy(
                        status = "Done",
                        errorMessage = null,
                        summary = if (isKo) "음성에서 인식된 내용이 없어 요약을 생성하지 않았습니다." else "No speech was recognized; nothing to summarize.",
                        outline = null, bibleRefs = null, properNouns = null, keywords = null
                    )
                )
                writePlaceholderSegment(jobId, job.durationSec, isKo)
                return@withContext true
            }

            val finalTitle = computeOfflineTitle(job.title, corrected)
            val summary = OfflineTextProcessor.generateSummary(finalTitle, corrected)
            val refs = OfflineTextProcessor.extractBibleReferences(corrected)
            val outline = OfflineTextProcessor.generateSermonOutline(finalTitle, corrected, refs)
            val nouns = OfflineTextProcessor.extractProperNouns(corrected)
            val keywords = OfflineTextProcessor.extractKeywords(corrected)

            sermonDao.updateJob(
                job.copy(
                    title = finalTitle,
                    status = "Done",
                    errorMessage = null,
                    summary = summary,
                    outline = outline,
                    bibleRefs = refs,
                    properNouns = nouns,
                    keywords = keywords
                )
            )
            // Replaces the live preview segments with the final paragraph-structured ones.
            writeSegments(jobId, job.durationSec, corrected)
            true
        }

    /**
     * Update only a job's audio path + duration (used after the live recorder transcodes
     * the temporary WAV to the final .m4a on stop).
     */
    suspend fun updateJobAudio(jobId: String, audioPath: String, durationSec: Double) {
        withContext(NonCancellable) {
            val job = sermonDao.getJobById(jobId) ?: return@withContext
            sermonDao.updateJob(job.copy(audioPath = audioPath, durationSec = durationSec))
        }
    }

    /**
     * Re-run correction on the existing transcript segments entirely offline.
     */
    suspend fun runAutoCorrect(jobId: String): Boolean = withContext(Dispatchers.IO) {
        val job = sermonDao.getJobById(jobId) ?: return@withContext false
        val segments = sermonDao.getSegmentsByJobId(jobId)
        if (segments.isEmpty()) return@withContext false

        sermonDao.updateJob(job.copy(status = "Transcribing"))
        try {
            val rawFulltext = segments.joinToString("\n\n") { it.text }

            val cleaned = TranscriptCleaner.clean(rawFulltext)
            val corrected = OfflineTextProcessor.correctTranscript(cleaned)

            val summary = OfflineTextProcessor.generateSummary(job.title, corrected)
            val refs = OfflineTextProcessor.extractBibleReferences(corrected)
            val outline = OfflineTextProcessor.generateSermonOutline(job.title, corrected, refs)
            val nouns = OfflineTextProcessor.extractProperNouns(corrected)
            val keywords = OfflineTextProcessor.extractKeywords(corrected)

            // Update parent row first, then rewrite child segments — see transcribeJob
            // for the CASCADE/REPLACE rationale.
            sermonDao.updateJob(
                job.copy(
                    status = "Done",
                    summary = summary,
                    outline = outline,
                    bibleRefs = refs,
                    properNouns = nouns,
                    keywords = keywords
                )
            )
            writeSegments(jobId, job.durationSec, corrected)
            true
        } catch (e: Exception) {
            sermonDao.updateJob(job.copy(status = "Done", errorMessage = e.localizedMessage))
            false
        }
    }

    /**
     * Regenerate outline + summary from existing segments entirely offline.
     */
    suspend fun regenerateOutline(jobId: String): Boolean = withContext(Dispatchers.IO) {
        val job = sermonDao.getJobById(jobId) ?: return@withContext false
        val segments = sermonDao.getSegmentsByJobId(jobId)
        if (segments.isEmpty()) return@withContext false

        try {
            val text = segments.joinToString("\n\n") { it.text }
            val refs = OfflineTextProcessor.extractBibleReferences(text)
            val newOutline = OfflineTextProcessor.generateSermonOutline(job.title, text, refs)
            val newSummary = OfflineTextProcessor.generateSummary(job.title, text)
            sermonDao.updateJob(
                job.copy(
                    outline = newOutline,
                    summary = newSummary,
                    bibleRefs = refs
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /* ---------------------- helpers ---------------------- */

    private suspend fun writeSegments(jobId: String, totalDurationSec: Double, transcript: String) {
        val paragraphs = transcript
            .split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(transcript.trim()).filter { it.isNotEmpty() } }

        if (paragraphs.isEmpty()) return

        val perParagraph = if (totalDurationSec > 0) totalDurationSec / paragraphs.size else 0.0
        val isKo = Locale.getDefault().language == "ko"
        val speakerLabel = if (isKo) "설교자" else "Preacher"

        val segments = paragraphs.mapIndexed { index, paragraph ->
            SermonSegment(
                jobId = jobId,
                startSec = index * perParagraph,
                endSec = (index + 1) * perParagraph,
                text = paragraph,
                speaker = speakerLabel
            )
        }
        sermonDao.deleteSegmentsByJobId(jobId)
        sermonDao.insertSegments(segments)
    }

    /**
     * Write a single placeholder segment for jobs where Whisper recognized no speech.
     * Without this, the detail screen would say "no transcript records yet" which is
     * indistinguishable from a job that never ran.
     */
    private suspend fun writePlaceholderSegment(jobId: String, totalDurationSec: Double, isKo: Boolean) {
        val text = if (isKo)
            "음성에서 인식된 내용이 없습니다. 녹음이 너무 짧거나 무음일 수 있어요."
        else
            "No speech was recognized. The recording may be too short or silent."
        val speakerLabel = if (isKo) "설교자" else "Preacher"
        val segment = SermonSegment(
            jobId = jobId,
            startSec = 0.0,
            endSec = totalDurationSec.coerceAtLeast(0.0),
            text = text,
            speaker = speakerLabel
        )
        sermonDao.deleteSegmentsByJobId(jobId)
        sermonDao.insertSegments(listOf(segment))
    }

    private fun computeOfflineTitle(currentTitle: String, transcript: String): String {
        val placeholderTitles = setOf("Untitled Sermon", "무제 설교", "")
        val needsAutoTitle = currentTitle.trim() in placeholderTitles ||
            currentTitle.startsWith("Sermon_Rec_")
        return if (needsAutoTitle) fallbackTitleFromTranscript(transcript) else currentTitle
    }

    private fun fallbackTitleFromTranscript(transcript: String): String {
        val firstSentence = transcript
            .split(Regex("[.。\\n]"))
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: transcript.take(40)
        return firstSentence.take(40)
    }

    companion object {
        private val _transcriptionProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
        val transcriptionProgress: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _transcriptionProgress

        fun updateProgress(jobId: String, progress: Int) {
            val current = _transcriptionProgress.value.toMutableMap()
            current[jobId] = progress
            _transcriptionProgress.value = current
        }

        fun clearProgress(jobId: String) {
            val current = _transcriptionProgress.value.toMutableMap()
            current.remove(jobId)
            _transcriptionProgress.value = current
        }
    }
}
