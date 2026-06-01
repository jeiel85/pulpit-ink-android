package io.pulpit.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulpit.ink.data.api.WhisperLib
import io.pulpit.ink.ui.audio.AudioDecoder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the chunked streaming decode + transcription pipeline.
 *
 * These tests exercise the real failure mode that produced the original OutOfMemoryError
 * (decoding a multi-minute recording held entirely in the Java heap) against ACTUAL
 * long sermon recordings, plus the multi-chunk transcription/concatenation path.
 *
 * Test assets are pushed to /data/local/tmp before running (see verification notes):
 *   - sermon_long_58m.mp3 / sermon_long_35m.mp3  (full sermons, the OOM repro)
 *   - test_sermon_30s.mp3                         (short clip, fast e2e)
 *   - ggml-tiny.bin                               (whisper model)
 * Any test whose assets are missing is skipped via assumeTrue rather than failing.
 */
@RunWith(AndroidJUnit4::class)
class StreamingTranscriptionTest {

    private val TAG = "StreamingTxTest"
    private val TARGET_RATE = 16000

    private fun tmp(name: String) = File("/data/local/tmp/$name")

    private fun copyToSandbox(srcName: String, dstName: String = srcName): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dst = File(ctx.filesDir, dstName)
        val src = tmp(srcName)
        if (src.exists()) {
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        }
        return dst
    }

    private fun logMem(tag: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        android.util.Log.i(TAG, "[$tag] heap used=${usedMb}MB / max=${maxMb}MB")
    }

    /**
     * Decode a full-length sermon with the streaming decoder and assert that:
     *  - it completes without OutOfMemoryError,
     *  - chunks are emitted and each chunk stays within the configured bound,
     *  - total decoded samples roughly match the clip duration.
     * This is the direct regression test for the reported OOM.
     */
    private fun assertStreamingDecodeBounded(fileName: String, chunkSeconds: Int) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = tmp(fileName)
        assumeTrue("Missing test asset $fileName — skipping", audio.exists() && audio.length() > 0)

        logMem("before decode $fileName")
        val maxChunkBound = (chunkSeconds.toLong() * TARGET_RATE * 1.25).toLong() // tolerance for resample rounding
        var chunkCount = 0
        var totalSamples = 0L
        var maxChunkSamples = 0
        var lastProcessedSec = 0.0
        var totalSec = 0.0

        AudioDecoder.decodeToPcmStreaming(audio, ctx, chunkSeconds) { chunk, processedSec, total ->
            chunkCount++
            totalSamples += chunk.size
            if (chunk.size > maxChunkSamples) maxChunkSamples = chunk.size
            lastProcessedSec = processedSec
            totalSec = total
            assertTrue(
                "Chunk $chunkCount of $fileName has ${chunk.size} samples, exceeding bound $maxChunkBound — memory not actually streamed",
                chunk.size <= maxChunkBound
            )
        }

        logMem("after decode $fileName")
        android.util.Log.i(
            TAG,
            "DECODE $fileName: chunks=$chunkCount, maxChunkSamples=$maxChunkSamples, " +
                "totalSamples=$totalSamples (~${totalSamples / TARGET_RATE}s), containerTotalSec=$totalSec"
        )

        assertTrue("No chunks produced for $fileName", chunkCount > 0)
        // Total decoded 16kHz samples should be within ~5% of the reported duration.
        if (totalSec > 0) {
            val expected = totalSec * TARGET_RATE
            val ratio = totalSamples / expected
            assertTrue(
                "Decoded sample count off: got $totalSamples vs expected ~$expected (ratio=$ratio)",
                ratio in 0.90..1.10
            )
        }
    }

    @Test
    fun streamingDecode_58minSermon_staysBounded() {
        // 5-minute chunks over a ~58 minute file => ~12 chunks, peak heap ~65MB.
        assertStreamingDecodeBounded("sermon_long_58m.mp3", chunkSeconds = 300)
    }

    @Test
    fun streamingDecode_35minSermon_staysBounded() {
        assertStreamingDecodeBounded("sermon_long_35m.mp3", chunkSeconds = 300)
    }

    /**
     * End-to-end multi-chunk transcription on a single native context: decode the 30s clip
     * in 10-second chunks (=> 3 chunks), transcribe each on the SAME reused whisper context,
     * and concatenate. Mirrors OfflineWhisperEngine's loop and proves that repeated
     * whisper_full calls return per-chunk text that concatenates into a coherent transcript.
     */
    @Test
    fun multiChunkTranscription_producesText() {
        assumeTrue("Whisper native lib not loaded", WhisperLib.isLibraryLoaded)

        val audio = copyToSandbox("test_sermon_30s.mp3")
        assumeTrue("Missing test_sermon_30s.mp3", audio.exists() && audio.length() > 0)
        val model = copyToSandbox("ggml-tiny.bin")
        assumeTrue("Missing ggml-tiny.bin", model.exists() && model.length() > 0)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val contextPtr = WhisperLib.initContext(model.absolutePath)
        assertNotEquals("Whisper context failed to init", 0L, contextPtr)

        try {
            val sb = StringBuilder()
            var chunkCount = 0
            AudioDecoder.decodeToPcmStreaming(audio, ctx, chunkSeconds = 10) { chunk, _, _ ->
                chunkCount++
                val text = WhisperLib.transcribeAudio(contextPtr, chunk, "ko", null)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(text.trim())
                }
            }
            android.util.Log.i(TAG, "MULTICHUNK chunks=$chunkCount, transcript=\n$sb")
            assertTrue("Expected multiple chunks for a 30s clip at 10s chunks, got $chunkCount", chunkCount >= 2)
            assertTrue("Concatenated transcript should not be blank", sb.isNotBlank())
        } finally {
            WhisperLib.freeContext(contextPtr)
        }
    }
}
