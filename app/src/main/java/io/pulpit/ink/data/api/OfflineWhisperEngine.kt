package io.pulpit.ink.data.api

import android.content.Context
import android.util.Log
import io.pulpit.ink.ui.audio.AudioDecoder
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * SpeechToTextEngine implementation that transcribes audio fully offline
 * using Whisper.cpp native JNI bindings and the downloaded local model binary.
 */
class OfflineWhisperEngine(private val context: Context) : SpeechToTextEngine {
    private val TAG = "OfflineWhisperEngine"
    private val modelManager = WhisperModelManager(context)

    override suspend fun transcribe(
        audioFile: File,
        modelKey: String,
        onInferenceProgress: ((Int) -> Unit)?
    ): String {
        Log.i(TAG, "Starting offline transcription for ${audioFile.name} using model: $modelKey")

        // 1. Verify JNI library is loaded
        if (!WhisperLib.isLibraryLoaded) {
            throw IllegalStateException(
                "Offline Whisper engine native library (libwhisper.so) is not loaded. " +
                "Please place the pre-built native libraries in your project's jniLibs directory."
            )
        }

        // 2. Locate the model file — PAD-bundled copy (base) first, then any
        // copy downloaded into app storage.
        val config = WhisperModelConfig.fromKey(modelKey)
        val modelFile = modelManager.resolveModelFile(config)
            ?: throw IllegalStateException(
                "Local model file for '$modelKey' is not found or corrupted. " +
                "Please download the model from settings first."
            )

        // 3. Initialize the native Whisper context ONCE and reuse it across all chunks.
        // The JNI transcribeAudio runs whisper_full with no_context=true, so each chunk is
        // decoded independently and returns only its own text — safe to call repeatedly.
        Log.d(TAG, "Initializing Whisper native context with model: ${modelFile.name}")
        onInferenceProgress?.invoke(-1) // "decoding audio" stage hint
        onInferenceProgress?.invoke(-2) // "loading model" stage hint
        val contextPtr = WhisperLib.initContext(modelFile.absolutePath)
        if (contextPtr == 0L) {
            throw IllegalStateException("Failed to initialize native Whisper context with model: ${modelFile.absolutePath}")
        }

        val systemLanguage = Locale.getDefault().language
        val effectiveLanguage = if (systemLanguage.isNotBlank()) systemLanguage else "auto"
        // Capture the calling coroutine context so we can honor cancellation between
        // chunks from inside the (non-suspend) decode callback.
        val cc = coroutineContext
        val transcript = StringBuilder()

        // 4. Stream-decode the audio in bounded chunks, transcribing each as it arrives.
        // This caps peak memory regardless of recording length (see AudioDecoder).
        try {
            AudioDecoder.decodeToPcmStreaming(audioFile, context) { chunkPcm, processedSec, totalSec ->
                cc.ensureActive() // throws CancellationException if the job was cancelled

                val chunkLenSec = chunkPcm.size / 16000.0
                val chunkStartSec = (processedSec - chunkLenSec).coerceAtLeast(0.0)

                val callback = onInferenceProgress?.let { sink ->
                    object : WhisperLib.ProgressCallback {
                        override fun onProgress(percent: Int) {
                            // Map this chunk's 0..100 onto the overall clip timeline so the
                            // notification progresses smoothly across the whole recording.
                            val overall = if (totalSec > 0.0) {
                                val sec = chunkStartSec + (percent / 100.0) * chunkLenSec
                                ((sec / totalSec) * 100.0).toInt().coerceIn(0, 100)
                            } else {
                                percent.coerceIn(0, 100)
                            }
                            sink(overall)
                        }
                    }
                }

                val chunkText = WhisperLib.transcribeAudio(contextPtr, chunkPcm, effectiveLanguage, callback)
                if (chunkText.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append("\n\n")
                    transcript.append(chunkText.trim())
                }
            }
            Log.i(TAG, "Offline transcription completed. Total transcript length: ${transcript.length}")
            // A blank transcript means no speech was recognized (silent/very short clip);
            // the repository treats that as a successful no-op rather than a failure.
            return transcript.toString()
        } finally {
            Log.d(TAG, "Safely releasing native Whisper context pointer: $contextPtr")
            WhisperLib.freeContext(contextPtr)
        }
    }
}
