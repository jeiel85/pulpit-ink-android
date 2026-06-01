package io.pulpit.ink.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes compressed audio files (like .m4a AAC) into 16kHz mono Float PCM arrays.
 * Applies software-based DSP filters (IIR highpass, lowpass, and windowed RMS speech compressor)
 * matching the user's desktop audio enhancement presets to maximize Whisper transcription accuracy.
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000

    /** Default chunk length (in source-time seconds) for streaming decode. */
    const val DEFAULT_CHUNK_SECONDS = 300

    /**
     * Decode an audio file in bounded time-slices, invoking [onChunk] with each
     * fully-processed 16kHz mono float chunk (range [-1.0f, 1.0f], with the selected
     * DSP preset already applied).
     *
     * Streaming — rather than holding the entire decoded PCM in RAM — keeps peak memory
     * flat regardless of total duration. A 1-hour recording would otherwise need a
     * ~300MB source ShortArray plus a ~220MB resampled FloatArray simultaneously, which
     * OOMs even with a large heap. Here only one [chunkSeconds]-long slice is resident
     * at a time (~65MB for a 5-minute slice).
     *
     * Exceptions (including [kotlinx.coroutines.CancellationException] thrown by the
     * caller's [onChunk]) propagate out so the caller can fail/cancel cleanly; the codec
     * and extractor are always released in `finally`.
     *
     * @param chunkSeconds approximate length, in source-time seconds, of each emitted chunk.
     * @param onChunk receives (chunkPcm16k, processedSec, totalSec): the processed chunk,
     *   the end timestamp of this chunk in seconds, and the full clip duration in seconds
     *   (0.0 if the container did not report a duration). Never called with an empty chunk.
     */
    fun decodeToPcmStreaming(
        audioFile: File,
        context: Context,
        chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
        onChunk: (chunkPcm: FloatArray, processedSec: Double, totalSec: Double) -> Unit
    ) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file is empty or missing: ${audioFile.absolutePath}")
            return
        }

        val prefs = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
        val preset = prefs.getString("selected_audio_preset", "sermon") ?: "sermon"

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(audioFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found in: ${audioFile.name}")
                return
            }

            extractor.selectTrack(trackIndex)

            val totalSec = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else 0.0

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            // Accumulate decoded mono samples for the CURRENT chunk only, in a PRIMITIVE
            // growable buffer. A boxed ArrayList<Short> would cost ~24 bytes per sample
            // (object header + Object[] reference) instead of 2, and its doubling backing
            // array forces huge contiguous Object[] allocations that OOM on long audio.
            val pcmBuffer = ShortPcmBuffer()
            var isInputEOS = false
            var isDecoderEOS = false

            var decodedChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var decodedSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            Log.i(TAG, "Streaming decode. File: ${audioFile.name}, channels: $decodedChannels, rate: $decodedSampleRate, totalSec: $totalSec, chunkSeconds: $chunkSeconds")

            // Total mono samples flushed to chunks so far — used to compute the running
            // timestamp without relying on container timestamps.
            var emittedSourceSamples = 0L

            while (!isDecoderEOS) {
                if (!isInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.order(ByteOrder.nativeOrder())

                        // Copy samples based on layout
                        val count = info.size / 2 // 16-bit short samples
                        val pcmShorts = ShortArray(count)
                        outputBuffer.asShortBuffer().get(pcmShorts)

                        // Downmix dynamically based on active decoded channels in the PCM stream.
                        // Bound the frame loop to full frames only — a trailing partial frame
                        // (count not divisible by channel count) would index out of bounds.
                        if (decodedChannels > 1) {
                            val lastFrameStart = count - decodedChannels
                            var i = 0
                            while (i <= lastFrameStart) {
                                var sum = 0
                                for (c in 0 until decodedChannels) {
                                    sum += pcmShorts[i + c]
                                }
                                pcmBuffer.add((sum / decodedChannels).toShort())
                                i += decodedChannels
                            }
                        } else {
                            pcmBuffer.addAll(pcmShorts, count)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Decoder output EOS reached.")
                        isDecoderEOS = true
                    }

                    // Flush a chunk once enough source samples have accumulated. Reusing the
                    // buffer (reset, not realloc) keeps capacity for the next chunk.
                    val chunkThreshold = chunkSeconds.toLong() * decodedSampleRate
                    if (chunkThreshold > 0 && pcmBuffer.size >= chunkThreshold) {
                        emittedSourceSamples += pcmBuffer.size
                        val processedSec = if (decodedSampleRate > 0)
                            emittedSourceSamples.toDouble() / decodedSampleRate else 0.0
                        val chunk = processChunk(pcmBuffer, decodedSampleRate, preset)
                        pcmBuffer.reset()
                        if (chunk.isNotEmpty()) onChunk(chunk, processedSec, totalSec)
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        decodedChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        decodedSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    Log.d(TAG, "Decoder output format changed: $newFormat, channels: $decodedChannels, rate: $decodedSampleRate")
                }
            }

            // Flush the trailing partial chunk.
            if (pcmBuffer.size > 0) {
                emittedSourceSamples += pcmBuffer.size
                val processedSec = if (totalSec > 0) totalSec
                    else if (decodedSampleRate > 0) emittedSourceSamples.toDouble() / decodedSampleRate
                    else 0.0
                val chunk = processChunk(pcmBuffer, decodedSampleRate, preset)
                pcmBuffer.reset()
                if (chunk.isNotEmpty()) onChunk(chunk, processedSec, totalSec)
            }
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Resample one accumulated source-rate mono chunk to 16kHz and apply the selected
     * DSP preset. The IIR high/low-pass stages run in-place on the resampled array.
     */
    private fun processChunk(buffer: ShortPcmBuffer, sourceRate: Int, preset: String): FloatArray {
        val rawPcm = resampleAndNormalize(buffer.backing, buffer.size, sourceRate)
        if (rawPcm.isEmpty()) return rawPcm
        val rate = TARGET_SAMPLE_RATE.toFloat()
        return when (preset) {
            "stt_basic" -> {
                val hp = applyHighPassFilter(rawPcm, 80f, rate)
                applyLowPassFilter(hp, 7800f, rate)
            }
            "sermon" -> {
                val hp = applyHighPassFilter(rawPcm, 80f, rate)
                val lp = applyLowPassFilter(hp, 7500f, rate)
                applyDynamicGain(lp, rate, maxGain = 4.0f, minRms = 0.01f)
            }
            "noisy" -> {
                val hp = applyHighPassFilter(rawPcm, 100f, rate)
                val lp = applyLowPassFilter(hp, 6500f, rate)
                applyDynamicGain(lp, rate, maxGain = 5.0f, minRms = 0.02f)
            }
            else -> rawPcm // "none"
        }
    }

    /**
     * Resamples raw mono PCM from source rate to 16kHz, and normalizes it to [-1.0f, 1.0f].
     * Reads only the first [length] entries of [inputSamples] (the backing array may carry
     * unused trailing capacity).
     */
    private fun resampleAndNormalize(inputSamples: ShortArray, length: Int, sourceRate: Int): FloatArray {
        if (length <= 0) return FloatArray(0)

        val outputSamples: FloatArray
        if (sourceRate == TARGET_SAMPLE_RATE) {
            // Already 16000Hz, only normalize
            outputSamples = FloatArray(length)
            for (i in 0 until length) {
                outputSamples[i] = inputSamples[i] / 32768.0f
            }
        } else {
            // Resample from sourceRate to 16000Hz using linear interpolation
            val ratio = sourceRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            val outputSize = (length / ratio).toInt()
            outputSamples = FloatArray(outputSize)

            for (i in 0 until outputSize) {
                val sourceIndex = i * ratio
                val leftIndex = sourceIndex.toInt()
                val rightIndex = (leftIndex + 1).coerceAtMost(length - 1)
                val weight = sourceIndex - leftIndex

                val leftSample = inputSamples[leftIndex].toFloat()
                val rightSample = inputSamples[rightIndex].toFloat()

                // Interpolate
                val interpolated = leftSample + weight.toFloat() * (rightSample - leftSample)
                // Normalize
                outputSamples[i] = interpolated / 32768.0f
            }
        }
        return outputSamples
    }

    /**
     * 2nd-order IIR Butterworth Highpass Filter to remove sub-bass rumble (< 80Hz/100Hz).
     */
    private fun applyHighPassFilter(pcm: FloatArray, cutoff: Float, sampleRate: Float): FloatArray {
        val omega = (2.0 * Math.PI * cutoff / sampleRate).toFloat()
        val gamma = Math.tan(omega / 2.0).toFloat()
        val sqrt2 = Math.sqrt(2.0).toFloat()
        
        val d = gamma * gamma + sqrt2 * gamma + 1f
        val b0 = 1f / d
        val b1 = -2f / d
        val b2 = 1f / d
        val a1 = 2f * (gamma * gamma - 1f) / d
        val a2 = (gamma * gamma - sqrt2 * gamma + 1f) / d
        
        // In-place: each output sample depends only on PAST inputs/outputs, and we read
        // pcm[i] before overwriting it, so writing back into the same array is safe and
        // avoids allocating another full-length FloatArray for every filter stage.
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f

        for (i in pcm.indices) {
            val x = pcm[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

            x2 = x1
            x1 = x
            y2 = y1
            y1 = y

            pcm[i] = y
        }
        return pcm
    }

    /**
     * 2nd-order IIR Butterworth Lowpass Filter to remove high-frequency static hiss (> 6.5kHz/7.5kHz).
     */
    private fun applyLowPassFilter(pcm: FloatArray, cutoff: Float, sampleRate: Float): FloatArray {
        val omega = (2.0 * Math.PI * cutoff / sampleRate).toFloat()
        val gamma = Math.tan(omega / 2.0).toFloat()
        val sqrt2 = Math.sqrt(2.0).toFloat()
        
        val d = gamma * gamma + sqrt2 * gamma + 1f
        val b0 = (gamma * gamma) / d
        val b1 = (2f * gamma * gamma) / d
        val b2 = (gamma * gamma) / d
        val a1 = 2f * (gamma * gamma - 1f) / d
        val a2 = (gamma * gamma - sqrt2 * gamma + 1f) / d
        
        // In-place: each output sample depends only on PAST inputs/outputs, and we read
        // pcm[i] before overwriting it, so writing back into the same array is safe and
        // avoids allocating another full-length FloatArray for every filter stage.
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f

        for (i in pcm.indices) {
            val x = pcm[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

            x2 = x1
            x1 = x
            y2 = y1
            y1 = y

            pcm[i] = y
        }
        return pcm
    }

    /**
     * Sliding window Speech Gain dynamic compressor/limiter to balance volume dynamically.
     */
    private fun applyDynamicGain(
        pcm: FloatArray, 
        sampleRate: Float, 
        maxGain: Float, 
        minRms: Float
    ): FloatArray {
        val output = FloatArray(pcm.size)
        val windowSize = (sampleRate * 0.5f).toInt() // 500ms sliding window
        if (pcm.size < windowSize) return pcm.clone()
        
        var smoothGain = 1.0f
        val targetRms = 0.15f // target RMS level for speech
        val alpha = 0.05f     // gain smoothing factor
        
        for (i in pcm.indices step (windowSize / 2)) {
            val end = (i + windowSize).coerceAtMost(pcm.size)
            val len = end - i
            if (len <= 0) break
            
            var sumSquare = 0f
            for (j in i until end) {
                sumSquare += pcm[j] * pcm[j]
            }
            val rms = Math.sqrt((sumSquare / len).toDouble()).toFloat()
            
            val targetGain = when {
                rms < minRms -> 1.0f // don't amplify silence noise
                else -> (targetRms / rms).coerceIn(1.0f, maxGain)
            }
            
            for (j in i until end) {
                smoothGain += alpha * (targetGain - smoothGain)
                output[j] = (pcm[j] * smoothGain).coerceIn(-1.0f, 1.0f)
            }
        }

        return output
    }

    /**
     * Growable primitive Short buffer used to accumulate decoded PCM without the ~12x
     * memory blow-up of a boxed ArrayList<Short>. Doubling a primitive ShortArray costs
     * 2 bytes per sample and avoids the giant contiguous Object[] reallocations that
     * triggered OutOfMemoryError on multi-minute recordings.
     */
    private class ShortPcmBuffer(initialCapacity: Int = 1 shl 18) {
        var backing: ShortArray = ShortArray(initialCapacity.coerceAtLeast(16))
            private set
        var size: Int = 0
            private set

        fun add(value: Short) {
            ensureCapacity(size + 1)
            backing[size++] = value
        }

        fun addAll(src: ShortArray, count: Int) {
            if (count <= 0) return
            ensureCapacity(size + count)
            System.arraycopy(src, 0, backing, size, count)
            size += count
        }

        /** Reset length to zero while KEEPING the backing capacity for chunk reuse. */
        fun reset() {
            size = 0
        }

        private fun ensureCapacity(min: Int) {
            if (min <= backing.size) return
            var newCap = backing.size
            while (newCap < min) {
                // Guard against int overflow on very long recordings.
                newCap = if (newCap > Int.MAX_VALUE / 2) Int.MAX_VALUE else newCap * 2
            }
            backing = backing.copyOf(newCap)
        }
    }
}
