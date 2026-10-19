package com.instruments.sp404sx.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Utility class providing DSP sample editing functions:
 * trimming, normalization, pitch shift, time stretching, fading, and RIFF WAV I/O.
 */
class SampleEditor {

    companion object {

        /**
         * Trims audio data to the given start and end fractions [0.0..1.0].
         * Assumes stereo interleaved samples (2 samples per frame).
         */
        fun trim(audioData: ShortArray, startFraction: Float, endFraction: Float): ShortArray {
            if (audioData.isEmpty()) return ShortArray(0)

            val totalFrames = audioData.size / 2
            val start = startFraction.coerceIn(0f, 1f)
            val end = endFraction.coerceIn(start, 1f)

            val startFrame = (start * totalFrames).toInt().coerceIn(0, totalFrames)
            val endFrame = (end * totalFrames).toInt().coerceIn(startFrame, totalFrames)

            val startIndex = startFrame * 2
            val endIndex = endFrame * 2

            return audioData.copyOfRange(startIndex, endIndex)
        }

        /**
         * Normalizes audio data so the peak amplitude reaches 0 dBFS (32767).
         */
        fun normalize(audioData: ShortArray): ShortArray {
            if (audioData.isEmpty()) return ShortArray(0)

            var peak = 0
            for (sample in audioData) {
                val absVal = abs(sample.toInt())
                if (absVal > peak) peak = absVal
            }

            if (peak == 0 || peak >= 32767) {
                return audioData.clone()
            }

            val gain = 32767.0f / peak
            val result = ShortArray(audioData.size)
            for (i in audioData.indices) {
                val normalized = (audioData[i] * gain).coerceIn(-32768f, 32767f)
                result[i] = normalized.toInt().toShort()
            }
            return result
        }

        /**
         * Reverses audio data preserving stereo frame channel alignment [L0, R0, L1, R1, ...].
         */
        fun reverse(audioData: ShortArray): ShortArray {
            if (audioData.isEmpty()) return ShortArray(0)

            val totalFrames = audioData.size / 2
            val result = ShortArray(audioData.size)

            for (frame in 0 until totalFrames) {
                val srcFrame = totalFrames - 1 - frame
                result[frame * 2] = audioData[srcFrame * 2]
                result[frame * 2 + 1] = audioData[srcFrame * 2 + 1]
            }

            // Handle odd single sample if any
            if (audioData.size % 2 != 0) {
                result[audioData.size - 1] = audioData[audioData.size - 1]
            }

            return result
        }

        /**
         * Changes pitch by resampling (linear interpolation).
         * @param ratio 0.5 = 1 octave down (doubles duration), 2.0 = 1 octave up (halves duration).
         */
        fun changePitch(audioData: ShortArray, channels: Int, ratio: Float): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val r = ratio.coerceIn(0.1f, 10.0f)
            val srcFrames = audioData.size / ch
            if (srcFrames <= 1) return audioData.clone()

            val dstFrames = (srcFrames / r).toInt().coerceAtLeast(1)
            val result = ShortArray(dstFrames * ch)

            for (dstFrame in 0 until dstFrames) {
                val srcPos = dstFrame * r
                val idx0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
                val idx1 = minOf(idx0 + 1, srcFrames - 1)
                val frac = (srcPos - idx0).toFloat()

                for (c in 0 until ch) {
                    val s0 = audioData[idx0 * ch + c].toFloat()
                    val s1 = audioData[idx1 * ch + c].toFloat()
                    val interpolated = s0 + frac * (s1 - s0)
                    result[dstFrame * ch + c] = interpolated.coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }

            return result
        }

        /**
         * Time-stretch audio without altering pitch using Overlap-Add (OLA) with a Hanning window.
         * @param ratio Stretch factor: 1.5 = 50% longer duration, 0.75 = 25% shorter duration.
         */
        fun timeStretch(audioData: ShortArray, channels: Int, ratio: Float): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val r = ratio.coerceIn(0.25f, 4.0f)
            val srcFrames = audioData.size / ch
            if (srcFrames < 1024) return audioData.clone()

            val windowSize = 1024
            val hopA = windowSize / 4 // 256 frames
            val hopS = (hopA * r).roundToInt().coerceAtLeast(1)

            val dstFrames = (srcFrames * r).toInt().coerceAtLeast(windowSize)
            val outBuffer = FloatArray(dstFrames * ch)
            val weightBuffer = FloatArray(dstFrames)

            // Precompute Hanning window
            val window = FloatArray(windowSize) { n ->
                (0.5 * (1.0 - cos(2.0 * PI * n / (windowSize - 1)))).toFloat()
            }

            var inPos = 0
            var outPos = 0

            while (inPos + windowSize <= srcFrames && outPos + windowSize <= dstFrames) {
                for (n in 0 until windowSize) {
                    val w = window[n]
                    val oFrame = outPos + n
                    weightBuffer[oFrame] += w
                    for (c in 0 until ch) {
                        val sample = audioData[(inPos + n) * ch + c].toFloat()
                        outBuffer[oFrame * ch + c] += sample * w
                    }
                }
                inPos += hopA
                outPos += hopS
            }

            // Normalize overlapping frames
            val result = ShortArray(dstFrames * ch)
            for (f in 0 until dstFrames) {
                val weight = weightBuffer[f]
                val normFactor = if (weight > 0.001f) 1.0f / weight else 1.0f
                for (c in 0 until ch) {
                    val sample = outBuffer[f * ch + c] * normFactor
                    result[f * ch + c] = sample.coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }

            return result
        }

        /**
         * Applies a smooth cosine fade-in over [durationSamples] frames.
         */
        fun fadeIn(audioData: ShortArray, channels: Int, durationSamples: Int): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val totalFrames = audioData.size / ch
            val fadeFrames = minOf(durationSamples, totalFrames)
            val result = audioData.clone()

            for (frame in 0 until fadeFrames) {
                val progress = frame.toFloat() / fadeFrames
                val gain = 0.5f * (1.0f - cos(PI.toFloat() * progress))
                for (c in 0 until ch) {
                    val idx = frame * ch + c
                    result[idx] = (result[idx] * gain).toInt().toShort()
                }
            }

            return result
        }

        /**
         * Applies a smooth cosine fade-out over [durationSamples] frames.
         */
        fun fadeOut(audioData: ShortArray, channels: Int, durationSamples: Int): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val totalFrames = audioData.size / ch
            val fadeFrames = minOf(durationSamples, totalFrames)
            val startFrame = totalFrames - fadeFrames
            val result = audioData.clone()

            for (frame in 0 until fadeFrames) {
                val progress = frame.toFloat() / fadeFrames
                val gain = 0.5f * (1.0f + cos(PI.toFloat() * progress))
                val targetFrame = startFrame + frame
                for (c in 0 until ch) {
                    val idx = targetFrame * ch + c
                    result[idx] = (result[idx] * gain).toInt().toShort()
                }
            }

            return result
        }

        /**
         * Writes 16-bit PCM audio data to a standard RIFF/WAVE file.
         */
        fun writeWav(filePath: String, audioData: ShortArray, sampleRate: Int, channels: Int): Boolean {
            return try {
                val file = File(filePath)
                file.parentFile?.mkdirs()

                val dataSize = audioData.size * 2
                val totalDataLen = dataSize + 36
                val byteRate = sampleRate * channels * 2
                val blockAlign = channels * 2

                FileOutputStream(file).use { fos ->
                    BufferedOutputStream(fos).use { out ->
                        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

                        // RIFF header
                        header.put("RIFF".toByteArray(Charsets.US_ASCII))
                        header.putInt(totalDataLen)
                        header.put("WAVE".toByteArray(Charsets.US_ASCII))

                        // fmt subchunk
                        header.put("fmt ".toByteArray(Charsets.US_ASCII))
                        header.putInt(16) // Subchunk1Size for PCM
                        header.putShort(1.toShort()) // AudioFormat 1 = PCM
                        header.putShort(channels.toShort())
                        header.putInt(sampleRate)
                        header.putInt(byteRate)
                        header.putShort(blockAlign.toShort())
                        header.putShort(16.toShort()) // BitsPerSample

                        // data subchunk
                        header.put("data".toByteArray(Charsets.US_ASCII))
                        header.putInt(dataSize)

                        out.write(header.array())

                        // Write PCM 16-bit samples
                        val pcmBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                        for (sample in audioData) {
                            if (!pcmBuffer.hasRemaining()) {
                                out.write(pcmBuffer.array(), 0, pcmBuffer.position())
                                pcmBuffer.clear()
                            }
                            pcmBuffer.putShort(sample)
                        }
                        if (pcmBuffer.position() > 0) {
                            out.write(pcmBuffer.array(), 0, pcmBuffer.position())
                        }
                        out.flush()
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        /**
         * Reads a WAV file from the filesystem.
         * Supports 8-bit, 16-bit, 24-bit, 32-bit float PCM, mono/stereo, and any sample rate.
         * @return Triple containing (audioData, sampleRate, channels) or null if decoding fails.
         */
        fun readWav(filePath: String): Triple<ShortArray, Int, Int>? {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) return null

            return try {
                FileInputStream(file).use { fis ->
                    readWavFromStream(BufferedInputStream(fis))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * Parses RIFF/WAVE stream data directly.
         */
        fun readWavFromStream(inputStream: InputStream): Triple<ShortArray, Int, Int>? {
            return try {
                val headerBytes = ByteArray(12)
                var bytesRead = inputStream.read(headerBytes)
                if (bytesRead < 12) return null

                val riff = String(headerBytes, 0, 4, Charsets.US_ASCII)
                val wave = String(headerBytes, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return null

                var audioFormat = 1
                var channels = 2
                var sampleRate = 44100
                var bitsPerSample = 16
                var audioBytes: ByteArray? = null

                val chunkHeader = ByteArray(8)
                while (inputStream.read(chunkHeader) == 8) {
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                    if (chunkSize < 0) break

                    when (chunkId) {
                        "fmt " -> {
                            val fmtData = ByteArray(chunkSize)
                            inputStream.read(fmtData)
                            val bb = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat = bb.short.toInt() and 0xFFFF
                            channels = bb.short.toInt() and 0xFFFF
                            sampleRate = bb.int
                            bb.int // byteRate
                            bb.short // blockAlign
                            bitsPerSample = bb.short.toInt() and 0xFFFF
                        }
                        "data" -> {
                            val data = ByteArray(chunkSize)
                            var readTotal = 0
                            while (readTotal < chunkSize) {
                                val r = inputStream.read(data, readTotal, chunkSize - readTotal)
                                if (r <= 0) break
                                readTotal += r
                            }
                            audioBytes = data
                            break // Reached data chunk
                        }
                        else -> {
                            // Skip non-data chunks (e.g., LIST, ID3, cue)
                            var skipped = 0L
                            while (skipped < chunkSize) {
                                val s = inputStream.skip(chunkSize - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }
                    }
                }

                val rawBytes = audioBytes ?: return null
                val samples = convertBytesToShorts(rawBytes, audioFormat, bitsPerSample)

                Triple(samples, sampleRate, channels)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        /**
         * Converts raw byte array from WAV data chunk to signed 16-bit PCM ShortArray.
         */
        private fun convertBytesToShorts(bytes: ByteArray, format: Int, bitsPerSample: Int): ShortArray {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            return when (bitsPerSample) {
                8 -> {
                    // 8-bit unsigned PCM: 0..255 with 128 as silence
                    val count = bytes.size
                    val result = ShortArray(count)
                    for (i in 0 until count) {
                        val unsignedVal = bytes[i].toInt() and 0xFF
                        result[i] = ((unsignedVal - 128) shl 8).toShort()
                    }
                    result
                }
                16 -> {
                    // Standard 16-bit signed PCM
                    val count = bytes.size / 2
                    val result = ShortArray(count)
                    for (i in 0 until count) {
                        result[i] = bb.short
                    }
                    result
                }
                24 -> {
                    // 24-bit signed PCM (3 bytes per sample)
                    val count = bytes.size / 3
                    val result = ShortArray(count)
                    for (i in 0 until count) {
                        val b0 = bytes[i * 3].toInt() and 0xFF
                        val b1 = bytes[i * 3 + 1].toInt() and 0xFF
                        val b2 = bytes[i * 3 + 2].toInt() // signed high byte
                        val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                        result[i] = (sample24 shr 8).toShort()
                    }
                    result
                }
                32 -> {
                    val count = bytes.size / 4
                    val result = ShortArray(count)
                    if (format == 3) {
                        // 32-bit IEEE float PCM
                        for (i in 0 until count) {
                            val f = bb.float
                            result[i] = (f * 32767.0f).coerceIn(-32768f, 32767f).toInt().toShort()
                        }
                    } else {
                        // 32-bit integer PCM
                        for (i in 0 until count) {
                            val v = bb.int
                            result[i] = (v shr 16).toShort()
                        }
                    }
                    result
                }
                else -> {
                    // Fallback to 16-bit assumption
                    val count = bytes.size / 2
                    val result = ShortArray(count)
                    for (i in 0 until count) {
                        result[i] = bb.short
                    }
                    result
                }
            }
        }
    }
}
