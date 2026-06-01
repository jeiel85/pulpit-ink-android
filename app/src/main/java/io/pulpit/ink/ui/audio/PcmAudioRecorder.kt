package io.pulpit.ink.ui.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Captures microphone audio as raw 16-bit PCM via [AudioRecord], streaming it to a
 * temporary WAV file (crash-safe — samples hit disk continuously) while also handing each
 * freshly-read buffer to [onPcm] so a live transcriber can consume audio AS it is recorded.
 *
 * The WAV header is written with placeholder sizes up front and patched on [stop]. Capture
 * is intentionally independent of transcription: even if the [onPcm] consumer or whisper
 * fails, the WAV on disk remains a complete recording.
 */
class PcmAudioRecorder(
    val sampleRate: Int = 44_100,
    val channels: Int = 1
) {
    private val TAG = "PcmAudioRecorder"

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var recording = false

    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L
    private var outFile: File? = null

    val isRecording: Boolean get() = recording

    /**
     * Begin capture into [outWav]. [onPcm] is invoked on the capture thread with each read
     * buffer (a [ShortArray] and a valid sample count); it MUST return quickly — hand the
     * samples off to another thread rather than doing heavy work inline, or the recorder
     * will fall behind and drop audio.
     *
     * @return true if capture started, false if AudioRecord could not be initialized.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is enforced by the caller / manifest.
    fun start(outWav: File, onPcm: (samples: ShortArray, count: Int) -> Unit): Boolean {
        if (recording) return false

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioRecord min buffer size: $minBuf")
            return false
        }
        // Generous buffer (4x min, >= ~0.5s) to tolerate scheduling jitter without overrun.
        val bufferBytes = maxOf(minBuf * 4, sampleRate * 2 / 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${record.state})")
            record.release()
            return false
        }

        try {
            outWav.parentFile?.mkdirs()
            val file = RandomAccessFile(outWav, "rw")
            file.setLength(0)
            writePlaceholderHeader(file)
            raf = file
            outFile = outWav
            dataBytes = 0L
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open WAV for writing", t)
            record.release()
            return false
        }

        audioRecord = record
        recording = true
        record.startRecording()

        val readChunk = ShortArray(bufferBytes / 2)
        readThread = Thread({
            val byteBuf = ByteArray(readChunk.size * 2)
            while (recording) {
                val n = record.read(readChunk, 0, readChunk.size)
                if (n <= 0) continue
                // Persist to WAV first (source of truth), then notify the live consumer.
                try {
                    var bi = 0
                    for (i in 0 until n) {
                        val s = readChunk[i].toInt()
                        byteBuf[bi++] = (s and 0xFF).toByte()
                        byteBuf[bi++] = ((s shr 8) and 0xFF).toByte()
                    }
                    raf?.write(byteBuf, 0, n * 2)
                    dataBytes += n * 2
                } catch (t: Throwable) {
                    Log.e(TAG, "WAV write failed", t)
                }
                try {
                    onPcm(readChunk, n)
                } catch (t: Throwable) {
                    // A transcription-side failure must never break capture.
                    Log.e(TAG, "onPcm consumer threw (ignored to protect capture)", t)
                }
            }
        }, "PcmAudioRecorder-read").apply { start() }

        Log.i(TAG, "Capture started: $sampleRate Hz, $channels ch -> ${outWav.name}")
        return true
    }

    /**
     * Stop capture, finalize the WAV header, and return the recorded duration in seconds.
     * Safe to call once; subsequent calls return 0.
     */
    fun stop(): Double {
        if (!recording) return 0.0
        recording = false
        try { readThread?.join(2000) } catch (_: InterruptedException) {}
        readThread = null

        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.stop failed", t)
        }
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        val durationSec = if (sampleRate > 0 && channels > 0)
            dataBytes.toDouble() / (sampleRate * channels * 2) else 0.0

        try {
            raf?.let { patchHeader(it, dataBytes) }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to patch WAV header", t)
        } finally {
            try { raf?.close() } catch (_: Throwable) {}
            raf = null
        }

        Log.i(TAG, "Capture stopped: ${"%.2f".format(durationSec)}s, $dataBytes data bytes -> ${outFile?.name}")
        return durationSec
    }

    private fun writePlaceholderHeader(file: RandomAccessFile) {
        // 44-byte canonical PCM WAV header; sizes patched on stop.
        file.seek(0)
        file.write(buildHeader(0L))
    }

    private fun patchHeader(file: RandomAccessFile, dataLen: Long) {
        file.seek(0)
        file.write(buildHeader(dataLen))
    }

    private fun buildHeader(dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val riffSize = 36 + dataLen
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) { for (i in s.indices) h[off + i] = s[i].code.toByte() }
        fun put32(off: Int, v: Long) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF"); put32(4, riffSize); putStr(8, "WAVE")
        putStr(12, "fmt "); put32(16, 16); put16(20, 1) /*PCM*/; put16(22, channels)
        put32(24, sampleRate.toLong()); put32(28, byteRate.toLong()); put16(32, blockAlign); put16(34, 16)
        putStr(36, "data"); put32(40, dataLen)
        return h
    }
}
