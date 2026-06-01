package io.pulpit.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulpit.ink.ui.audio.AudioDecoder
import io.pulpit.ink.ui.audio.AudioFileEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifies the incremental-recording storage path: raw PCM WAV -> AAC/M4A transcode
 * (AudioFileEncoder) -> decodes back to roughly the same audio (AudioDecoder). This is the
 * round-trip the recorder relies on to store a compact, playable file on stop.
 */
@RunWith(AndroidJUnit4::class)
class AudioEncoderTest {

    private val TAG = "AudioEncoderTest"

    private fun writeSineWav(file: File, seconds: Int, sampleRate: Int = 44100, freq: Double = 440.0) {
        val n = seconds * sampleRate
        val dataBytes = n * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            fun str(s: String) = s.map { it.code.toByte() }.toByteArray()
            fun i32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
            fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
            raf.write(str("RIFF")); raf.write(i32(36 + dataBytes)); raf.write(str("WAVE"))
            raf.write(str("fmt ")); raf.write(i32(16)); raf.write(i16(1)); raf.write(i16(1))
            raf.write(i32(sampleRate)); raf.write(i32(sampleRate * 2)); raf.write(i16(2)); raf.write(i16(16))
            raf.write(str("data")); raf.write(i32(dataBytes))
            val buf = ByteArray(dataBytes)
            var bi = 0
            for (i in 0 until n) {
                val sample = (sin(2.0 * PI * freq * i / sampleRate) * 12000).toInt()
                buf[bi++] = (sample and 0xFF).toByte()
                buf[bi++] = ((sample shr 8) and 0xFF).toByte()
            }
            raf.write(buf)
        }
    }

    @Test
    fun wavToM4aRoundTrip() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val seconds = 3
        val wav = File(ctx.filesDir, "enc_test.wav")
        val m4a = File(ctx.filesDir, "enc_test.m4a")
        m4a.delete()

        writeSineWav(wav, seconds)

        // Sanity: our own WAV parser reads the header back.
        val info = AudioFileEncoder.readWavInfo(wav)
        assertNotNull("WAV header should parse", info)
        assertEquals(44100, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)

        val ok = AudioFileEncoder.encodeWavToM4a(wav, m4a)
        assertTrue("Encoding should succeed", ok)
        assertTrue("M4A should be non-empty", m4a.exists() && m4a.length() > 0)
        // AAC at 96kbps should be far smaller than the raw WAV.
        assertTrue("M4A should be smaller than WAV", m4a.length() < wav.length())

        // Decode the produced m4a back and confirm we get ~3s of 16kHz samples.
        var totalSamples = 0L
        AudioDecoder.decodeToPcmStreaming(m4a, ctx, chunkSeconds = 60) { chunk, _, _ ->
            totalSamples += chunk.size
        }
        android.util.Log.i(TAG, "round-trip: wav=${wav.length()} m4a=${m4a.length()} decodedSamples=$totalSamples")
        val expected = seconds * 16000
        val ratio = totalSamples.toDouble() / expected
        assertTrue("Decoded length off (got $totalSamples, expected ~$expected, ratio=$ratio)", ratio in 0.80..1.20)
    }
}
