package com.instruments.sp404sx.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Microphone audio recorder capturing 16-bit PCM audio at 44.1kHz.
 * Converts input to standard stereo WAV and delivers real-time amplitude level updates.
 */
class MicRecorder(private val context: Context) {

    private val tag = "MicRecorder"
    private val mainHandler = Handler(Looper.getMainLooper())

    var onRecordingComplete: ((String) -> Unit)? = null
    var onLevelUpdate: ((Float) -> Unit)? = null

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    private var startTimeMs: Long = 0L
    private var durationMs: Long = 0L

    private var currentOutputPath: String? = null
    private var tempPcmFile: File? = null

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val LEVEL_UPDATE_INTERVAL_MS = 40L
    }

    /**
     * Starts microphone recording to the specified WAV output file path.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(outputPath: String) {
        if (recording.get()) {
            Log.w(tag, "Recording is already in progress.")
            return
        }

        currentOutputPath = outputPath
        durationMs = 0L
        startTimeMs = SystemClock.uptimeMillis()

        // Prepare temporary PCM file
        val tempFile = File(context.cacheDir, "rec_temp_${System.currentTimeMillis()}.pcm")
        tempPcmFile = tempFile

        // Determine channel configuration (try stereo first, fallback to mono)
        var channelConfig = AudioFormat.CHANNEL_IN_STEREO
        var numChannels = 2
        var minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        if (minBufferSize <= 0) {
            channelConfig = AudioFormat.CHANNEL_IN_MONO
            numChannels = 1
            minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        }

        val bufferSize = maxOf(minBufferSize, 4096)

        val audioRecord: AudioRecord
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to instantiate AudioRecord: ${e.message}", e)
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(tag, "AudioRecord failed to initialize.")
            audioRecord.release()
            return
        }

        recording.set(true)

        recordThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val shortBuffer = ShortArray(bufferSize / 2)
            var lastLevelUpdate = 0L

            try {
                audioRecord.startRecording()
                FileOutputStream(tempFile).use { fos ->
                    BufferedOutputStream(fos).use { bos ->
                        val byteBuffer = ByteBuffer.allocate(shortBuffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)

                        while (recording.get()) {
                            val shortsRead = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                            if (shortsRead > 0) {
                                // Calculate peak amplitude for level meter
                                var peak = 0
                                for (i in 0 until shortsRead) {
                                    val absVal = abs(shortBuffer[i].toInt())
                                    if (absVal > peak) peak = absVal
                                }

                                val now = SystemClock.uptimeMillis()
                                if (now - lastLevelUpdate >= LEVEL_UPDATE_INTERVAL_MS) {
                                    lastLevelUpdate = now
                                    val normalizedLevel = (peak / 32767.0f).coerceIn(0f, 1f)
                                    mainHandler.post {
                                        onLevelUpdate?.invoke(normalizedLevel)
                                    }
                                }

                                // Write raw PCM bytes to temp file
                                byteBuffer.clear()
                                for (i in 0 until shortsRead) {
                                    byteBuffer.putShort(shortBuffer[i])
                                }
                                bos.write(byteBuffer.array(), 0, shortsRead * 2)
                            } else if (shortsRead < 0) {
                                Log.e(tag, "AudioRecord read error code: $shortsRead")
                                break
                            }
                        }
                        bos.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during audio recording: ${e.message}", e)
            } finally {
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Error stopping AudioRecord: ${e.message}")
                }
                audioRecord.release()

                // Finalize WAV packaging
                finalizeRecording(tempFile, outputPath, numChannels)
            }
        }, "SP404SX-MicRecorderThread")

        recordThread?.start()
    }

    /**
     * Stops the active microphone recording session.
     */
    fun stopRecording() {
        if (!recording.get()) return

        recording.set(false)
        durationMs = SystemClock.uptimeMillis() - startTimeMs

        // Send a final 0 level to reset meters
        mainHandler.post {
            onLevelUpdate?.invoke(0f)
        }

        try {
            recordThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(tag, "Interrupted while waiting for record thread to stop.")
        }
        recordThread = null
    }

    /**
     * Checks if recording is actively running.
     */
    fun isRecording(): Boolean = recording.get()

    /**
     * Returns the duration of the current or last recording in milliseconds.
     */
    fun getRecordingDurationMs(): Long {
        return if (recording.get()) {
            SystemClock.uptimeMillis() - startTimeMs
        } else {
            durationMs
        }
    }

    /**
     * Converts recorded PCM file to a standard 44.1kHz 16-bit stereo WAV file.
     */
    private fun finalizeRecording(pcmFile: File, outputPath: String, inputChannels: Int) {
        try {
            if (!pcmFile.exists() || pcmFile.length() <= 0L) {
                Log.e(tag, "PCM recording file is missing or empty.")
                return
            }

            val totalBytes = pcmFile.length()
            val totalShorts = (totalBytes / 2).toInt()
            val recordedData = ShortArray(totalShorts)

            FileInputStream(pcmFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                    val rawChunk = ByteArray(4096)
                    var shortsWritten = 0

                    while (shortsWritten < totalShorts) {
                        val bytesToRead = minOf(rawChunk.size, (totalShorts - shortsWritten) * 2)
                        val bytesRead = bis.read(rawChunk, 0, bytesToRead)
                        if (bytesRead <= 0) break

                        bb.clear()
                        bb.put(rawChunk, 0, bytesRead)
                        bb.flip()

                        while (bb.hasRemaining() && shortsWritten < totalShorts) {
                            recordedData[shortsWritten++] = bb.short
                        }
                    }
                }
            }

            // If recorded mono, upmix to stereo by duplicating each sample
            val stereoData = if (inputChannels == 1) {
                val stereo = ShortArray(recordedData.size * 2)
                for (i in recordedData.indices) {
                    val s = recordedData[i]
                    stereo[i * 2] = s
                    stereo[i * 2 + 1] = s
                }
                stereo
            } else {
                recordedData
            }

            // Write final WAV file
            val success = SampleEditor.writeWav(outputPath, stereoData, SAMPLE_RATE, 2)
            if (success) {
                mainHandler.post {
                    onRecordingComplete?.invoke(outputPath)
                }
            } else {
                Log.e(tag, "Failed to write WAV output file.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to finalize recording: ${e.message}", e)
        } finally {
            pcmFile.delete()
        }
    }
}
