package io.pulpit.ink.data.repository

import android.content.Context
import android.util.Log
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.data.api.WhisperModelConfig
import io.pulpit.ink.data.api.WhisperModelManager
import io.pulpit.ink.data.db.AppDatabase
import io.pulpit.ink.ui.audio.AudioDecoder
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Drives INCREMENTAL ("live") transcription: consumes microphone PCM as it is captured,
 * transcribes it in bounded time-slices on a persistent whisper context as recording
 * proceeds, and writes progressive preview segments to the DB. On [stop] it finishes the
 * trailing slice and runs the offline NLP once, so the final result is ready almost
 * immediately after the user stops recording — without ever holding the whole recording
 * in memory.
 *
 * Important guarantees:
 *  - Audio capture (PcmAudioRecorder) is independent: if anything here fails, the WAV on
 *    disk is still a complete recording and the caller can fall back to post-hoc transcribe.
 *  - Peak memory is bounded to ~one chunk via [PcmChunkQueue] (front-draining), not the
 *    whole session.
 */
class LiveTranscriptionController(
    appContext: Context,
    private val jobId: String,
    private val modelKey: String,
    private val sourceSampleRate: Int,
    private val chunkSeconds: Int = 120
) {
    private val TAG = "LiveTranscription"

    private val context = appContext.applicationContext
    private val repository = SermonRepository(AppDatabase.getDatabase(context).sermonDao())
    private val queue = PcmChunkQueue()

    @Volatile private var running = false
    @Volatile private var finishing = false
    @Volatile private var aborted = false
    @Volatile var failed = false
        private set

    private var contextPtr = 0L
    private var modelPath: String? = null
    private var worker: Thread? = null
    private val doneLatch = CountDownLatch(1)

    private val transcript = StringBuilder()
    private var processedSamples = 0L
    private var chunkStartSec = 0.0

    private val preset = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
        .getString("selected_audio_preset", "sermon") ?: "sermon"
    private val language = Locale.getDefault().language.ifBlank { "auto" }

    /**
     * Initialize the whisper context and start the worker thread. Returns false if the
     * model can't be loaded — the caller should then fall back to recording-only +
     * post-hoc transcription rather than failing the recording.
     */
    fun start(): Boolean {
        if (!WhisperLib.isLibraryLoaded) {
            Log.w(TAG, "Native whisper not loaded — live transcription unavailable")
            return false
        }
        // Only a cheap existence check here — the actual (potentially second-long) model
        // load happens on the worker thread so we never block the caller (recording start).
        val mgr = WhisperModelManager(context)
        val config = WhisperModelConfig.fromKey(modelKey)
        val modelFile = mgr.resolveModelFile(config)
            ?: WhisperModelConfig.values().firstNotNullOfOrNull { mgr.resolveModelFile(it) }
        if (modelFile == null) {
            Log.w(TAG, "No downloaded model available — live transcription unavailable")
            return false
        }
        modelPath = modelFile.absolutePath

        running = true
        worker = Thread({ workerLoop() }, "LiveTranscription-worker").apply { start() }
        Log.i(TAG, "Live transcription started: model=$modelKey rate=$sourceSampleRate chunkSec=$chunkSeconds preset=$preset")
        return true
    }

    /** Feed freshly-captured mono PCM. Cheap & non-blocking — safe to call from the capture thread. */
    fun feed(samples: ShortArray, count: Int) {
        if (running && count > 0) queue.add(samples, count)
    }

    /** Signal end of capture. The worker drains the tail, finalizes, and frees the context. */
    fun stop() {
        finishing = true
    }

    /**
     * Abort without finalizing (used when the user discards a recording). The worker stops
     * promptly and frees the whisper context; no NLP/persist is performed.
     */
    fun abort() {
        aborted = true
        finishing = true
    }

    /** Block until the worker has finished finalizing (or timed out). */
    fun awaitCompletion(timeoutMs: Long): Boolean =
        doneLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun workerLoop() {
        val chunkThreshold = chunkSeconds.toLong() * sourceSampleRate
        try {
            // Load the model here (not on the caller's thread) so recording start is never
            // blocked by the multi-hundred-millisecond context init.
            contextPtr = WhisperLib.initContext(modelPath ?: "")
            if (contextPtr == 0L) {
                throw IllegalStateException("Failed to initialize whisper context for live transcription")
            }
            while (true) {
                if (aborted) break
                when {
                    queue.available() >= chunkThreshold -> {
                        queue.pull(chunkThreshold.toInt())?.let { if (!aborted) processChunk(it) }
                    }
                    finishing -> {
                        if (!aborted) {
                            val rest = queue.drainAll()
                            if (rest.isNotEmpty()) processChunk(rest)
                        }
                        break
                    }
                    else -> Thread.sleep(150)
                }
            }
            if (aborted) {
                Log.i(TAG, "Live transcription aborted — skipping finalize")
            } else {
                // Final NLP + persist. Done even if no chunks produced text (handled as silent).
                runBlocking { repository.finalizeLiveTranscription(jobId, transcript.toString()) }
                Log.i(TAG, "Live transcription finalized. transcriptLen=${transcript.length}")
            }
        } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "Live transcription worker failed", t)
            try {
                runBlocking {
                    repository.markJobFailed(
                        jobId,
                        if (Locale.getDefault().language == "ko")
                            "실시간 전사 중 문제가 발생했습니다. 녹음은 저장되었으니 다시 시도해 주세요."
                        else "Live transcription failed. Your recording was saved — please retry."
                    )
                }
            } catch (_: Throwable) {}
        } finally {
            if (contextPtr != 0L) {
                try { WhisperLib.freeContext(contextPtr) } catch (_: Throwable) {}
                contextPtr = 0L
            }
            running = false
            doneLatch.countDown()
        }
    }

    private fun processChunk(srcShorts: ShortArray) {
        val pcm16 = AudioDecoder.processPcmChunk(srcShorts, srcShorts.size, sourceSampleRate, preset)
        val chunkDurSec = srcShorts.size.toDouble() / sourceSampleRate
        processedSamples += srcShorts.size
        val chunkEndSec = if (sourceSampleRate > 0) processedSamples.toDouble() / sourceSampleRate else chunkStartSec + chunkDurSec

        if (pcm16.isNotEmpty()) {
            val text = WhisperLib.transcribeAudio(contextPtr, pcm16, language, null)
            if (text.isNotBlank()) {
                val trimmed = text.trim()
                if (transcript.isNotEmpty()) transcript.append("\n\n")
                transcript.append(trimmed)
                runBlocking { repository.appendLiveSegment(jobId, chunkStartSec, chunkEndSec, trimmed) }
            }
        }
        chunkStartSec = chunkEndSec
    }

    /**
     * Front-drainable PCM accumulator. Holds queued capture buffers and lets the worker pull
     * fixed-size chunks off the front, bounding resident memory to roughly one chunk plus a
     * small backlog (never the whole recording).
     */
    private class PcmChunkQueue {
        private val deque = ArrayDeque<ShortArray>()
        private var availableCount = 0L

        @Synchronized fun add(src: ShortArray, count: Int) {
            if (count <= 0) return
            deque.addLast(src.copyOf(count))
            availableCount += count
        }

        @Synchronized fun available(): Long = availableCount

        /** Pull exactly [target] samples off the front, or null if fewer are available. */
        @Synchronized fun pull(target: Int): ShortArray? {
            if (target <= 0 || availableCount < target) return null
            val out = ShortArray(target)
            var filled = 0
            while (filled < target) {
                val head = deque.first()
                val need = target - filled
                if (head.size <= need) {
                    System.arraycopy(head, 0, out, filled, head.size)
                    filled += head.size
                    deque.removeFirst()
                } else {
                    System.arraycopy(head, 0, out, filled, need)
                    filled += need
                    deque[0] = head.copyOfRange(need, head.size)
                }
            }
            availableCount -= target
            return out
        }

        /** Pull everything remaining (used to flush the trailing partial chunk on stop). */
        @Synchronized fun drainAll(): ShortArray {
            if (availableCount <= 0L) return ShortArray(0)
            val total = availableCount.toInt()
            val out = ShortArray(total)
            var filled = 0
            while (deque.isNotEmpty()) {
                val head = deque.removeFirst()
                System.arraycopy(head, 0, out, filled, head.size)
                filled += head.size
            }
            availableCount = 0L
            return out
        }
    }
}
