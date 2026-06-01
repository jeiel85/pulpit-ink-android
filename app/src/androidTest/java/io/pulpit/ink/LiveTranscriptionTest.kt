package io.pulpit.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.data.db.AppDatabase
import io.pulpit.ink.data.repository.LiveTranscriptionController
import io.pulpit.ink.data.repository.SermonRepository
import io.pulpit.ink.ui.audio.AudioDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the full LIVE incremental transcription path end-to-end WITHOUT a microphone:
 * real speech audio is decoded to 16kHz PCM and fed into LiveTranscriptionController in
 * small bursts (as the recorder would), then we stop and assert the job finalized with
 * segments — validating PCM queue draining, per-chunk whisper, concatenation, and the NLP
 * finalize step.
 */
@RunWith(AndroidJUnit4::class)
class LiveTranscriptionTest {

    private val TAG = "LiveTxTest"

    @Test
    fun liveFeed_producesFinalizedTranscript() {
        assumeTrue("Whisper native lib not loaded", WhisperLib.isLibraryLoaded)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Use 'none' preset so decoding yields plain resampled 16k PCM and the controller
        // doesn't double-apply DSP when we feed that PCM back in at 16kHz.
        ctx.getSharedPreferences("whisper_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_audio_preset", "none").commit()

        // Stage model + audio into the sandbox.
        val model = File(ctx.filesDir, "ggml-tiny.bin")
        File("/data/local/tmp/ggml-tiny.bin").let { src ->
            assumeTrue("Missing ggml-tiny.bin", src.exists())
            if (!model.exists() || model.length() != src.length()) src.copyTo(model, overwrite = true)
        }
        val audioSrc = File("/data/local/tmp/test_sermon_30s.mp3")
        assumeTrue("Missing test_sermon_30s.mp3", audioSrc.exists())
        val audio = File(ctx.filesDir, "test_sermon_30s.mp3")
        audioSrc.copyTo(audio, overwrite = true)

        // Decode the clip to plain 16kHz PCM (no DSP, preset=none) and convert to shorts.
        val floatChunks = ArrayList<FloatArray>()
        AudioDecoder.decodeToPcmStreaming(audio, ctx, chunkSeconds = 60) { c, _, _ -> floatChunks.add(c) }
        val total = floatChunks.sumOf { it.size }
        assumeTrue("No PCM decoded", total > 0)
        val pcm = ShortArray(total)
        var idx = 0
        for (fc in floatChunks) for (f in fc) {
            pcm[idx++] = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        android.util.Log.i(TAG, "decoded 16k samples=$total (~${total / 16000}s)")

        val db = AppDatabase.getDatabase(ctx)
        val repo = SermonRepository(db.sermonDao())

        val jobId = runBlocking {
            repo.createNewJob("Live test", audio.absolutePath, total / 16000.0)
        }

        // Feed at 16kHz so resample is a no-op. 15s chunks => 2 chunks over the 30s clip
        // (whisper pads short chunks to 30s internally, so sub-30s chunks waste compute;
        // this still validates multi-chunk draining + concatenation).
        val controller = LiveTranscriptionController(
            ctx, jobId, modelKey = "tiny", sourceSampleRate = 16000, chunkSeconds = 15
        )
        assertTrue("Controller failed to start (model/context)", controller.start())

        // Push PCM in ~0.5s bursts, like the capture thread would.
        val burst = 8000
        var off = 0
        while (off < pcm.size) {
            val n = minOf(burst, pcm.size - off)
            controller.feed(pcm.copyOfRange(off, off + n), n)
            off += n
        }
        controller.stop()
        assertTrue("Live transcription did not finish in time", controller.awaitCompletion(300_000))
        assertFalse("Controller reported failure", controller.failed)

        val job = runBlocking { db.sermonDao().getJobById(jobId) }
        val segs = runBlocking { db.sermonDao().getSegmentsByJobId(jobId) }
        android.util.Log.i(TAG, "FINAL status=${job?.status} segments=${segs.size} summaryLen=${job?.summary?.length}")
        segs.take(3).forEach { android.util.Log.i(TAG, "seg: ${it.text.take(80)}") }

        assertEquals("Done", job?.status)
        assertFalse("Expected non-empty segments", segs.isEmpty())
    }
}
