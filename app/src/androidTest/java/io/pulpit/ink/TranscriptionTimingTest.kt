package io.pulpit.ink

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.data.db.AppDatabase
import io.pulpit.ink.data.repository.SermonRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Wall-clock timing of a FULL transcription (decode + chunked whisper + NLP) on the real
 * device, with the real model the user runs. Logs elapsed time and the real-time factor
 * (elapsed / audio-duration) so we know how long a real sermon actually takes.
 *
 * Configurable via instrumentation args (defaults in parentheses):
 *   -Pandroid.testInstrumentationRunnerArguments.audio=sermon_long_35m.mp3
 *   -Pandroid.testInstrumentationRunnerArguments.model=base
 *   -Pandroid.testInstrumentationRunnerArguments.dur=2100        (audio seconds, for RTF)
 *
 * Assets must be pushed to /data/local/tmp first: <audio> and ggml-<model>.bin.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionTimingTest {

    private val TAG = "TxTiming"

    @Test
    fun timeFullTranscription() {
        assumeTrue("Whisper native lib not loaded", WhisperLib.isLibraryLoaded)

        val args = InstrumentationRegistry.getArguments()
        val audioName = args.getString("audio") ?: "sermon_long_35m.mp3"
        val modelKey = args.getString("model") ?: "base"
        val argDur = args.getString("dur")?.toDoubleOrNull() ?: 0.0

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Stage the audio file into the app sandbox.
        val srcAudio = File("/data/local/tmp/$audioName")
        assumeTrue("Missing /data/local/tmp/$audioName", srcAudio.exists() && srcAudio.length() > 0)
        val audio = File(ctx.filesDir, audioName)
        srcAudio.inputStream().use { i -> audio.outputStream().use { o -> i.copyTo(o) } }

        // Stage the model into filesDir where WhisperModelManager expects it (ggml-<key>.bin).
        val modelName = "ggml-$modelKey.bin"
        val srcModel = File("/data/local/tmp/$modelName")
        assumeTrue("Missing /data/local/tmp/$modelName", srcModel.exists() && srcModel.length() > 0)
        val model = File(ctx.filesDir, modelName)
        srcModel.inputStream().use { i -> model.outputStream().use { o -> i.copyTo(o) } }

        // Determine audio duration for the real-time factor.
        val durationSec = if (argDur > 0) argDur else run {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(audio.absolutePath)
                (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            } catch (e: Exception) { 0.0 } finally { mmr.release() }
        }

        android.util.Log.i(TAG, "START transcription: audio=$audioName model=$modelKey durationSec=$durationSec")

        val db = AppDatabase.getDatabase(ctx)
        val repo = SermonRepository(db.sermonDao())

        runBlocking {
            val jobId = repo.createNewJob("Timing: $audioName", audio.absolutePath, durationSec)
            val startMs = System.currentTimeMillis()
            val ok = repo.transcribeJob(ctx, jobId, topicHint = null, selectedModelKey = modelKey)
            val elapsedMs = System.currentTimeMillis() - startMs
            val elapsedSec = elapsedMs / 1000.0
            val rtf = if (durationSec > 0) elapsedSec / durationSec else -1.0

            val job = db.sermonDao().getJobById(jobId)
            val segCount = db.sermonDao().getSegmentsByJobId(jobId).size

            android.util.Log.i(TAG, "=========== TRANSCRIPTION TIMING RESULT ===========")
            android.util.Log.i(TAG, "audio=$audioName  model=$modelKey")
            android.util.Log.i(TAG, "audioDuration = ${"%.1f".format(durationSec)} s (${"%.1f".format(durationSec / 60)} min)")
            android.util.Log.i(TAG, "elapsed       = ${"%.1f".format(elapsedSec)} s (${"%.1f".format(elapsedSec / 60)} min)")
            android.util.Log.i(TAG, "realtimeFactor= ${"%.2f".format(rtf)}x  (elapsed / audio)")
            android.util.Log.i(TAG, "status=${job?.status}  segments=$segCount  summaryLen=${job?.summary?.length ?: 0}")
            android.util.Log.i(TAG, "summary: ${job?.summary?.take(200)}")
            android.util.Log.i(TAG, "===================================================")

            assertTrue("Transcription did not succeed (status=${job?.status}, err=${job?.errorMessage})", ok)
            assertEquals("Done", job?.status)
        }
    }
}
