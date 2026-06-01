package io.pulpit.ink.ui.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcodes a finished 16-bit PCM WAV file into an AAC-in-MP4 (.m4a) file using the
 * platform MediaCodec encoder + MediaMuxer.
 *
 * Why this exists: the incremental-transcription recording path captures raw PCM to a
 * temporary WAV (crash-safe — the samples are on disk even if the process dies), and on a
 * clean stop we transcode to the same compact .m4a format the rest of the app already
 * uses for storage and playback. Live-muxing AAC during recording would leave an
 * unfinalized, unplayable MP4 if the app crashed mid-session — losing the whole sermon —
 * so we deliberately encode only from a complete WAV.
 */
object AudioFileEncoder {
    private const val TAG = "AudioFileEncoder"
    private const val MIME = "audio/mp4a-latm"
    private const val BIT_RATE = 96_000
    private const val TIMEOUT_US = 10_000L

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    /**
     * Parse a canonical PCM WAV header. Returns null if the file is not a PCM WAV we can read.
     */
    fun readWavInfo(wav: File): WavInfo? {
        if (!wav.exists() || wav.length() < 44) return null
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                Log.e(TAG, "Not a RIFF/WAVE file: ${wav.name}")
                return null
            }
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            // Walk chunks until we find 'fmt ' and 'data'.
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4)
                val size = le32(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                val chunkStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.coerceAtMost(64L).toInt())
                        raf.readFully(fmt)
                        channels = le16(fmt, 2)
                        sampleRate = le32(fmt, 4)
                        bitsPerSample = le16(fmt, 14)
                        raf.seek(chunkStart + size + (size and 1)) // chunks are word-aligned
                    }
                    "data" -> {
                        return WavInfo(sampleRate, channels, bitsPerSample, chunkStart, size)
                    }
                    else -> raf.seek(chunkStart + size + (size and 1))
                }
            }
        }
        Log.e(TAG, "No data chunk found in WAV: ${wav.name}")
        return null
    }

    /**
     * Encode [wav] (16-bit PCM) into AAC/M4A at [out]. Returns true on success.
     * On failure the partial output file is deleted.
     */
    fun encodeWavToM4a(wav: File, out: File): Boolean {
        val info = readWavInfo(wav)
        if (info == null || info.bitsPerSample != 16 || info.channels < 1 || info.sampleRate <= 0) {
            Log.e(TAG, "Unsupported WAV for encoding: $info")
            return false
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var raf: RandomAccessFile? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            val format = MediaFormat.createAudioFormat(MIME, info.sampleRate, info.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            raf = RandomAccessFile(wav, "r")
            raf.seek(info.dataOffset)
            var remaining = info.dataSize
            val bytesPerSecondPerChannel = info.sampleRate.toLong() * 2 // 16-bit
            val bytesPerFrame = 2 * info.channels
            var totalBytesRead = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val capacity = inBuf.capacity()
                        val toRead = minOf(capacity.toLong(), remaining).toInt()
                        val ptsUs = (totalBytesRead / bytesPerFrame) * 1_000_000L / info.sampleRate
                        if (toRead <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val tmp = ByteArray(toRead)
                            val read = raf.read(tmp)
                            if (read <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.put(tmp, 0, read)
                                codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                                totalBytesRead += read
                                remaining -= read
                            }
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                while (outIndex >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break // handled below
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config consumed by the muxer via the output format; ignore here.
                        bufInfo.size = 0
                    }
                    if (bufInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufInfo.offset)
                        outBuf.limit(bufInfo.offset + bufInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outIndex = -1
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                if (inputDone && (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }

            Log.i(TAG, "Encoded ${wav.name} -> ${out.name} (${out.length()} bytes, ${bytesPerSecondPerChannel}B/s/ch)")
            return out.exists() && out.length() > 0
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to encode ${wav.name} to m4a", t)
            if (out.exists()) out.delete()
            return false
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { raf?.close() } catch (_: Throwable) {}
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    @Suppress("unused")
    private fun ByteBuffer.asLE(): ByteBuffer = this.order(ByteOrder.LITTLE_ENDIAN)
}
