package io.pulpit.ink

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.data.db.AppDatabase
import io.pulpit.ink.data.repository.LiveTranscriptionController
import io.pulpit.ink.data.repository.SermonRepository
import io.pulpit.ink.ui.audio.AudioFileEncoder
import io.pulpit.ink.ui.audio.PcmAudioRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-device end-to-end test of the INCREMENTAL recording pipeline using the actual
 * components wired exactly as AudioRecordingService.stopIncrementalAndSave does — but
 * driven directly (no foreground service, which can't be legally started from a background
 * instrumentation process on Android 12+).
 *
 * Validates, on real hardware: live AudioRecord mic capture -> WAV, PCM streamed to the
 * LiveTranscriptionController as captured, WAV->m4a transcode on stop, NLP finalize, and DB
 * persistence to "Done". The captured audio is whatever the room provides (usually
 * silence), so this exercises the PIPELINE; real-speech accuracy is covered by
 * LiveTranscriptionTest (which injects decoded speech PCM).
 *
 * Requires RECORD_AUDIO granted to io.pulpit.ink and ggml-tiny.bin at /data/local/tmp.
 */
@RunWith(AndroidJUnit4::class)
class IncrementalServiceTest {

    private val TAG = "IncrServiceTest"

    @Test
    fun incrementalPipeline_realMicToDone() {
        assumeTrue("Whisper native lib not loaded", WhisperLib.isLibraryLoaded)
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext

        // Grant RECORD_AUDIO at test time (a fresh test install resets runtime grants).
        try {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.RECORD_AUDIO)
        } catch (_: Throwable) {}

        val model = File(ctx.filesDir, "ggml-tiny.bin")
        val src = File("/data/local/tmp/ggml-tiny.bin")
        assumeTrue("Missing /data/local/tmp/ggml-tiny.bin", src.exists())
        if (!model.exists() || model.length() != src.length()) src.copyTo(model, overwrite = true)

        ctx.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_audio_preset", "sermon")
            .commit()

        val db = AppDatabase.getDatabase(ctx)
        val repo = SermonRepository(db.sermonDao())

        val wav = File(ctx.cacheDir, "live_mic_test.wav")
        val m4a = File(ctx.cacheDir, "live_mic_test.m4a")
        wav.delete(); m4a.delete()

        // 1) Create the job up front (as the service does at record start).
        val jobId = runBlocking {
            val id = repo.createNewJob("live mic test", wav.absolutePath, 0.0)
            repo.markJobTranscribing(id)
            id
        }

        // 2) Start the live controller + real mic capture, wiring PCM -> controller.
        val controller = LiveTranscriptionController(ctx, jobId, modelKey = "tiny", sourceSampleRate = 44_100, chunkSeconds = 120)
        assertTrue("Live controller failed to start", controller.start())

        val recorder = PcmAudioRecorder(44_100, 1)
        val started = recorder.start(wav) { samples, count -> controller.feed(samples, count) }
        assertTrue("AudioRecord mic capture failed to start (permission?)", started)

        // 3) Capture ~6 seconds of real microphone audio.
        Thread.sleep(6000)
        val dur = recorder.stop()
        android.util.Log.i(TAG, "captured dur=$dur s, wav=${wav.length()} bytes")
        assertTrue("Recorded duration should be > 0", dur > 0.0)

        // 4) Stop flow: transcode WAV -> m4a, set audio path, finalize (NLP).
        val wavInfo = AudioFileEncoder.readWavInfo(wav)
        assertNotNull("Captured WAV header should parse", wavInfo)
        assertTrue("WAV should contain PCM data", (wavInfo?.dataSize ?: 0) > 0)

        val encoded = AudioFileEncoder.encodeWavToM4a(wav, m4a)
        assertTrue("WAV->m4a transcode should succeed", encoded)
        runBlocking { repo.updateJobAudio(jobId, m4a.absolutePath, dur) }

        controller.stop()
        assertTrue("Live finalize did not complete in time", controller.awaitCompletion(180_000))
        assertFalse("Controller reported failure", controller.failed)

        // 5) Verify final persisted state.
        val job = runBlocking { db.sermonDao().getJobById(jobId) }
        val segs = runBlocking { db.sermonDao().getSegmentsByJobId(jobId) }
        android.util.Log.i(TAG, "RESULT status=${job?.status} audio=${job?.audioPath} segs=${segs.size}")
        assertEquals("Job should finalize to Done", "Done", job?.status)
        assertTrue("Audio path should be the transcoded .m4a", job?.audioPath?.endsWith(".m4a") == true)
        assertTrue("Final m4a should exist", File(job!!.audioPath).exists())
        assertFalse("Should have at least a (placeholder) segment", segs.isEmpty())
    }
}
