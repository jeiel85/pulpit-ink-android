package io.pulpit.ink.data.repository

import android.content.Context
import android.util.Log
import io.pulpit.ink.data.db.AppDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide scope that owns long-running transcription work so it survives the
 * AudioRecordingService stopping and the hosting Activity/ViewModel being destroyed.
 * Supports thread-safe dynamic cancellation of active running transcription jobs.
 */
object TranscriptionRunner {
    private const val TAG = "TranscriptionRunner"

    private val handler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught exception in transcription runner", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    // Serialize transcriptions: Whisper inference is heavy and concurrent jobs would
    // thrash CPU/memory on mid-range Android devices.
    private val runMutex = Mutex()

    // Thread-safe active job tracking for dynamic cancellation
    private var activeJobId: String? = null
    private var activeJob: kotlinx.coroutines.Job? = null

    /**
     * Enqueue a transcription job. Returns immediately; the caller must not assume
     * completion timing. Uses the application context so callers (services, activities)
     * cannot accidentally pin themselves alive.
     */
    fun submit(
        context: Context,
        jobId: String,
        topicHint: String? = null,
        modelKey: String
    ) {
        val appContext = context.applicationContext
        synchronized(this) {
            // Cancel any existing run if it is for the same job (safeguard)
            if (activeJobId == jobId) {
                activeJob?.cancel()
                activeJobId = null
                activeJob = null
            }
        }

        val coroutineJob = scope.launch {
            runMutex.withLock {
                synchronized(this@TranscriptionRunner) {
                    activeJobId = jobId
                    activeJob = coroutineContext[kotlinx.coroutines.Job]
                }

                val repository = SermonRepository(AppDatabase.getDatabase(appContext).sermonDao())
                try {
                    repository.transcribeJob(appContext, jobId, topicHint, modelKey)
                } catch (t: Throwable) {
                    Log.e(TAG, "Transcription crashed for job $jobId", t)
                    withContext(NonCancellable) {
                        repository.markJobFailed(jobId, t.localizedMessage ?: "Transcription crashed")
                        // Backstop: if a Throwable escaped transcribeJob's own handler
                        // (e.g. failure during pre-flight, or an OOM inside that handler),
                        // make sure no stuck progress notification is left behind.
                        io.pulpit.ink.data.text.TranscriptionNotificationHelper
                            .dismissNotification(appContext, jobId)
                    }
                } finally {
                    synchronized(this@TranscriptionRunner) {
                        if (activeJobId == jobId) {
                            activeJobId = null
                            activeJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancel an active running transcription job.
     */
    fun cancel(jobId: String) {
        synchronized(this) {
            if (activeJobId == jobId) {
                Log.i(TAG, "Dynamic cancellation requested for active job: $jobId")
                activeJob?.cancel()
                activeJobId = null
                activeJob = null
            }
        }
    }

    /**
     * Check if a specific job is currently actively transcribing.
     */
    fun isTranscribing(jobId: String): Boolean {
        synchronized(this) {
            return activeJobId == jobId
        }
    }
}
