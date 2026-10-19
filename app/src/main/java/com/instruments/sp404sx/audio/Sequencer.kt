package com.instruments.sp404sx.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.instruments.sp404sx.model.BankId
import com.instruments.sp404sx.model.Pattern
import com.instruments.sp404sx.model.SequenceEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pattern sequencer that records real-time pad triggers, quantizes them to musical grid steps,
 * and loops playback in sync with the audio engine.
 */
class Sequencer(private val audioEngine: AudioEngine) {

    private val mainHandler = Handler(Looper.getMainLooper())

    var onStateChanged: (() -> Unit)? = null
    var onBeatTick: ((Int) -> Unit)? = null

    private val pattern = Pattern()
    private val recording = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)

    private var recordStartTimeMs: Long = 0L
    private var sequencerThread: Thread? = null

    /**
     * Starts recording pad events at the specified BPM.
     */
    fun startRecording(bpm: Int = 120) {
        if (recording.get()) return

        if (playing.get()) {
            stopPlayback()
        }

        pattern.bpm = bpm.coerceIn(40, 300)
        pattern.events.clear()
        pattern.lengthMs = 0L

        recordStartTimeMs = SystemClock.uptimeMillis()
        recording.set(true)

        // Start metronome ticker thread
        startTickerThread()

        notifyStateChanged()
    }

    /**
     * Stops the active recording session and calculates bar-quantized pattern length.
     */
    fun stopRecording() {
        if (!recording.get()) return

        recording.set(false)
        stopWorkerThread()

        val recordedDuration = SystemClock.uptimeMillis() - recordStartTimeMs
        val barMs = (240000.0 / pattern.bpm).toLong() // 4 beats per bar
        val bars = maxOf(1, Math.round(recordedDuration.toDouble() / barMs).toInt())
        pattern.lengthMs = bars * barMs

        // Sort events chronologically and discard events beyond pattern boundary
        synchronized(pattern.events) {
            pattern.events.sortBy { it.timeMs }
            pattern.events.removeAll { it.timeMs >= pattern.lengthMs }
        }

        notifyStateChanged()
    }

    /**
     * Starts looping playback of the recorded pattern.
     */
    fun startPlayback() {
        if (playing.get() || pattern.events.isEmpty()) return

        if (recording.get()) {
            stopRecording()
        }

        if (pattern.lengthMs <= 0L) {
            val barMs = (240000.0 / pattern.bpm).toLong()
            val maxEventTime = pattern.events.maxOfOrNull { it.timeMs } ?: 0L
            val bars = maxOf(1, Math.round((maxEventTime + barMs / 2).toDouble() / barMs).toInt())
            pattern.lengthMs = bars * barMs
        }

        playing.set(true)

        sequencerThread = Thread({
            var loopStartTime = SystemClock.uptimeMillis()
            var nextEventIndex = 0
            var lastBeat = -1

            val beatIntervalMs = 60000.0 / pattern.bpm

            while (playing.get()) {
                val now = SystemClock.uptimeMillis()
                var elapsedInLoop = now - loopStartTime

                // Pattern wrap-around
                if (elapsedInLoop >= pattern.lengthMs) {
                    loopStartTime += pattern.lengthMs
                    elapsedInLoop = now - loopStartTime
                    nextEventIndex = 0
                    lastBeat = -1
                }

                // Beat ticker callback
                val currentBeat = (elapsedInLoop / beatIntervalMs).toInt()
                if (currentBeat != lastBeat) {
                    lastBeat = currentBeat
                    val beatNumber = (currentBeat % 4) + 1
                    mainHandler.post {
                        onBeatTick?.invoke(beatNumber)
                    }
                }

                // Trigger sequence events matching current time
                synchronized(pattern.events) {
                    while (nextEventIndex < pattern.events.size &&
                        pattern.events[nextEventIndex].timeMs <= elapsedInLoop
                    ) {
                        val event = pattern.events[nextEventIndex]
                        audioEngine.triggerPad(event.bankId, event.padIndex)
                        nextEventIndex++
                    }
                }

                try {
                    Thread.sleep(4) // High timing resolution without CPU thrashing
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "SP404SX-SequencerPlaybackThread")

        sequencerThread?.start()
        notifyStateChanged()
    }

    /**
     * Stops pattern playback.
     */
    fun stopPlayback() {
        if (!playing.get()) return

        playing.set(false)
        stopWorkerThread()
        notifyStateChanged()
    }

    /**
     * Clears all recorded events and resets pattern length.
     */
    fun clear() {
        stopRecording()
        stopPlayback()

        synchronized(pattern.events) {
            pattern.events.clear()
        }
        pattern.lengthMs = 0L
        notifyStateChanged()
    }

    fun isRecording(): Boolean = recording.get()

    fun isPlaying(): Boolean = playing.get()

    fun setBpm(bpm: Int) {
        pattern.bpm = bpm.coerceIn(40, 300)
    }

    fun getBpm(): Int = pattern.bpm

    fun setQuantize(enabled: Boolean) {
        pattern.quantize = enabled
    }

    fun isQuantizeEnabled(): Boolean = pattern.quantize

    /**
     * Records a pad trigger event at the current time offset.
     */
    fun recordEvent(bankId: BankId, padIndex: Int) {
        if (!recording.get()) return

        val rawTimeMs = SystemClock.uptimeMillis() - recordStartTimeMs

        val eventTime = if (pattern.quantize) {
            // 16th note quantization
            val stepMs = 60000.0 / pattern.bpm / 4.0
            (Math.round(rawTimeMs / stepMs) * stepMs).toLong().coerceAtLeast(0L)
        } else {
            rawTimeMs
        }

        val event = SequenceEvent(
            timeMs = eventTime,
            bankId = bankId,
            padIndex = padIndex,
            velocity = 1.0f
        )

        synchronized(pattern.events) {
            pattern.events.add(event)
        }
    }

    fun getPattern(): Pattern = pattern

    fun hasPattern(): Boolean = pattern.events.isNotEmpty()

    /**
     * Runs metronome beat ticker during recording mode.
     */
    private fun startTickerThread() {
        sequencerThread = Thread({
            var lastBeat = -1
            val beatIntervalMs = 60000.0 / pattern.bpm

            while (recording.get()) {
                val elapsed = SystemClock.uptimeMillis() - recordStartTimeMs
                val currentBeat = (elapsed / beatIntervalMs).toInt()

                if (currentBeat != lastBeat) {
                    lastBeat = currentBeat
                    val beatNumber = (currentBeat % 4) + 1
                    mainHandler.post {
                        onBeatTick?.invoke(beatNumber)
                    }
                }

                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "SP404SX-SequencerTickerThread")

        sequencerThread?.start()
    }

    private fun stopWorkerThread() {
        try {
            sequencerThread?.interrupt()
            sequencerThread?.join(500)
        } catch (e: InterruptedException) {
            // Ignore
        }
        sequencerThread = null
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            onStateChanged?.invoke()
        }
    }
}
