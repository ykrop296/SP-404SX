package com.instruments.sp404sx.audio

import com.instruments.sp404sx.model.EffectType
import kotlin.math.*

/**
 * Real-time stereo DSP effects processor implementing the 12 classic SP-404SX effects.
 * Processes 16-bit stereo interleaved PCM buffers in-place.
 */
class EffectsProcessor(private val sampleRate: Int = 44100) {

    var activeEffect: EffectType? = null
    val params = FloatArray(3) { 0.5f }

    // Pre-allocated random generator for noise/crackle
    private var randomSeed = 123456789L

    // ----------------------------------------------------
    // FILTER STATE (State Variable Filter)
    // ----------------------------------------------------
    private var filterLpL = 0f
    private var filterBpL = 0f
    private var filterLpR = 0f
    private var filterBpR = 0f

    // ----------------------------------------------------
    // DELAY STATE
    // ----------------------------------------------------
    private val delayBufferSize = sampleRate * 2 // 2 seconds max
    private val delayBufferL = FloatArray(delayBufferSize)
    private val delayBufferR = FloatArray(delayBufferSize)
    private var delayWritePos = 0

    // ----------------------------------------------------
    // REVERB STATE (Schroeder: 4 parallel combs + 2 series allpass)
    // ----------------------------------------------------
    private class CombFilter(val size: Int) {
        val buffer = FloatArray(size)
        var pos = 0
        var store = 0f

        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[pos]
            store = output * (1f - damp) + store * damp
            buffer[pos] = input + store * feedback
            pos = (pos + 1) % size
            return output
        }

        fun reset() {
            buffer.fill(0f)
            pos = 0
            store = 0f
        }
    }

    private class AllPassFilter(val size: Int) {
        val buffer = FloatArray(size)
        var pos = 0

        fun process(input: Float, feedback: Float = 0.5f): Float {
            val bufOut = buffer[pos]
            val output = -input + bufOut
            buffer[pos] = input + bufOut * feedback
            pos = (pos + 1) % size
            return output
        }

        fun reset() {
            buffer.fill(0f)
            pos = 0
        }
    }

    private val combL = arrayOf(CombFilter(1116), CombFilter(1188), CombFilter(1277), CombFilter(1356))
    private val combR = arrayOf(CombFilter(1139), CombFilter(1211), CombFilter(1300), CombFilter(1379))
    private val allPassL = arrayOf(AllPassFilter(225), AllPassFilter(556))
    private val allPassR = arrayOf(AllPassFilter(231), AllPassFilter(561))

    // ----------------------------------------------------
    // VINYL SIM STATE
    // ----------------------------------------------------
    private val vinylBufferSize = 4096
    private val vinylBufferL = FloatArray(vinylBufferSize)
    private val vinylBufferR = FloatArray(vinylBufferSize)
    private var vinylWritePos = 0
    private var vinylLfoPhase = 0.0
    private var vinylCrackleDecay = 0f
    private var vinylNoiseLp = 0f
    private var vinylToneLpL = 0f
    private var vinylToneLpR = 0f

    // ----------------------------------------------------
    // COMPRESSOR STATE
    // ----------------------------------------------------
    private var compEnvelope = 0f

    // ----------------------------------------------------
    // RING MOD STATE
    // ----------------------------------------------------
    private var ringModPhase = 0.0

    // ----------------------------------------------------
    // FLANGER STATE
    // ----------------------------------------------------
    private val flangerBufferSize = 2048
    private val flangerBufferL = FloatArray(flangerBufferSize)
    private val flangerBufferR = FloatArray(flangerBufferSize)
    private var flangerWritePos = 0
    private var flangerLfoPhase = 0.0

    // ----------------------------------------------------
    // PHASER STATE (4-stage allpass cascade)
    // ----------------------------------------------------
    private val phaserStagesL = FloatArray(4)
    private val phaserStagesR = FloatArray(4)
    private var phaserFeedbackL = 0f
    private var phaserFeedbackR = 0f
    private var phaserLfoPhase = 0.0

    // ----------------------------------------------------
    // ISOLATOR STATE (3-band crossover filters)
    // ----------------------------------------------------
    private var isoLowLp1L = 0f
    private var isoLowLp2L = 0f
    private var isoHighHp1L = 0f
    private var isoHighHp2L = 0f

    private var isoLowLp1R = 0f
    private var isoLowLp2R = 0f
    private var isoHighHp1R = 0f
    private var isoHighHp2R = 0f

    // ----------------------------------------------------
    // LOFI STATE
    // ----------------------------------------------------
    private var lofiHoldCounter = 0
    private var lofiHeldSampleL = 0f
    private var lofiHeldSampleR = 0f

    // ----------------------------------------------------
    // DJFX LOOPER STATE
    // ----------------------------------------------------
    private val djfxMaxFrames = sampleRate * 2
    private val djfxBufferL = FloatArray(djfxMaxFrames)
    private val djfxBufferR = FloatArray(djfxMaxFrames)
    private var djfxWritePos = 0
    private var djfxReadPos = 0.0
    private var djfxIsLocked = false
    private var djfxLoopLength = sampleRate / 2

    // ----------------------------------------------------
    // TAPE ECHO STATE
    // ----------------------------------------------------
    private val tapeEchoBufferSize = sampleRate * 2
    private val tapeEchoBufferL = FloatArray(tapeEchoBufferSize)
    private val tapeEchoBufferR = FloatArray(tapeEchoBufferSize)
    private var tapeEchoWritePos = 0
    private var tapeEchoFilterL = 0f
    private var tapeEchoFilterR = 0f
    private var tapeEchoLfoPhase = 0.0

    /**
     * Process a stereo 16-bit interleaved PCM buffer in-place.
     * @param buffer Interleaved PCM samples [L0, R0, L1, R1, ...]
     * @param numFrames Number of stereo frames (buffer.size >= numFrames * 2)
     */
    fun process(buffer: ShortArray, numFrames: Int) {
        val effect = activeEffect ?: return
        if (numFrames <= 0) return

        when (effect) {
            EffectType.FILTER -> processFilter(buffer, numFrames)
            EffectType.DELAY -> processDelay(buffer, numFrames)
            EffectType.REVERB -> processReverb(buffer, numFrames)
            EffectType.VINYL_SIM -> processVinylSim(buffer, numFrames)
            EffectType.COMPRESSOR -> processCompressor(buffer, numFrames)
            EffectType.RING_MOD -> processRingMod(buffer, numFrames)
            EffectType.FLANGER -> processFlanger(buffer, numFrames)
            EffectType.PHASER -> processPhaser(buffer, numFrames)
            EffectType.ISOLATOR -> processIsolator(buffer, numFrames)
            EffectType.LOFI -> processLofi(buffer, numFrames)
            EffectType.DJFX_LOOPER -> processDjfxLooper(buffer, numFrames)
            EffectType.TAPE_ECHO -> processTapeEcho(buffer, numFrames)
        }
    }

    /**
     * Resets all internal DSP buffers, phases, and filter states.
     */
    fun reset() {
        filterLpL = 0f
        filterBpL = 0f
        filterLpR = 0f
        filterBpR = 0f

        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        delayWritePos = 0

        combL.forEach { it.reset() }
        combR.forEach { it.reset() }
        allPassL.forEach { it.reset() }
        allPassR.forEach { it.reset() }

        vinylBufferL.fill(0f)
        vinylBufferR.fill(0f)
        vinylWritePos = 0
        vinylLfoPhase = 0.0
        vinylCrackleDecay = 0f
        vinylNoiseLp = 0f
        vinylToneLpL = 0f
        vinylToneLpR = 0f

        compEnvelope = 0f
        ringModPhase = 0.0

        flangerBufferL.fill(0f)
        flangerBufferR.fill(0f)
        flangerWritePos = 0
        flangerLfoPhase = 0.0

        phaserStagesL.fill(0f)
        phaserStagesR.fill(0f)
        phaserFeedbackL = 0f
        phaserFeedbackR = 0f
        phaserLfoPhase = 0.0

        isoLowLp1L = 0f
        isoLowLp2L = 0f
        isoHighHp1L = 0f
        isoHighHp2L = 0f
        isoLowLp1R = 0f
        isoLowLp2R = 0f
        isoHighHp1R = 0f
        isoHighHp2R = 0f

        lofiHoldCounter = 0
        lofiHeldSampleL = 0f
        lofiHeldSampleR = 0f

        djfxBufferL.fill(0f)
        djfxBufferR.fill(0f)
        djfxWritePos = 0
        djfxReadPos = 0.0
        djfxIsLocked = false

        tapeEchoBufferL.fill(0f)
        tapeEchoBufferR.fill(0f)
        tapeEchoWritePos = 0
        tapeEchoFilterL = 0f
        tapeEchoFilterR = 0f
        tapeEchoLfoPhase = 0.0
    }

    // ------------------------------------------------------------------------
    // 1. FILTER: Chamberlin State Variable Filter (SVF)
    // Param 0: Cutoff frequency (40 Hz - 18,000 Hz)
    // Param 1: Resonance (Q factor 0.5 - 9.0)
    // Param 2: Filter mode (0.0 = LP, 0.5 = BP, 1.0 = HP)
    // ------------------------------------------------------------------------
    private fun processFilter(buffer: ShortArray, numFrames: Int) {
        val cutoff = (40.0 * 450.0.pow(params[0].toDouble())).coerceIn(40.0, sampleRate * 0.45)
        val q = 0.5 + params[1] * 8.5
        val damping = (1.0 / q).toFloat()
        val f = (2.0 * sin(PI * cutoff / sampleRate)).toFloat().coerceIn(0.001f, 0.999f)
        val mode = params[2]

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            // Left SVF
            val hpL = inL - filterLpL - damping * filterBpL
            filterBpL += f * hpL
            filterLpL += f * filterBpL

            // Right SVF
            val hpR = inR - filterLpR - damping * filterBpR
            filterBpR += f * hpR
            filterLpR += f * filterBpR

            val outL = when {
                mode <= 0.5f -> {
                    val t = mode * 2f
                    (1f - t) * filterLpL + t * filterBpL
                }
                else -> {
                    val t = (mode - 0.5f) * 2f
                    (1f - t) * filterBpL + t * hpL
                }
            }

            val outR = when {
                mode <= 0.5f -> {
                    val t = mode * 2f
                    (1f - t) * filterLpR + t * filterBpR
                }
                else -> {
                    val t = (mode - 0.5f) * 2f
                    (1f - t) * filterBpR + t * hpR
                }
            }

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 2. DELAY: Circular stereo delay
    // Param 0: Delay time (10ms - 1200ms)
    // Param 1: Feedback (0.0 - 0.88)
    // Param 2: Wet / Dry balance (0.0 - 1.0)
    // ------------------------------------------------------------------------
    private fun processDelay(buffer: ShortArray, numFrames: Int) {
        val delayTimeSec = 0.010f + params[0] * 1.190f
        val delaySamples = (delayTimeSec * sampleRate).toInt().coerceIn(1, delayBufferSize - 1)
        val feedback = (params[1] * 0.88f).coerceIn(0f, 0.92f)
        val wet = params[2]
        val dry = 1.0f - wet * 0.5f

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            var readPos = delayWritePos - delaySamples
            if (readPos < 0) readPos += delayBufferSize

            val delayedL = delayBufferL[readPos]
            val delayedR = delayBufferR[readPos]

            // Write input + feedback to circular delay buffer
            delayBufferL[delayWritePos] = softClip(inL + delayedL * feedback)
            delayBufferR[delayWritePos] = softClip(inR + delayedR * feedback)

            delayWritePos = (delayWritePos + 1) % delayBufferSize

            val outL = inL * dry + delayedL * wet
            val outR = inR * dry + delayedR * wet

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 3. REVERB: Schroeder reverb (4 combs + 2 allpasses)
    // Param 0: Room size / decay (0.70 - 0.95)
    // Param 1: High frequency damping (0.05 - 0.75)
    // Param 2: Wet / Dry balance (0.0 - 1.0)
    // ------------------------------------------------------------------------
    private fun processReverb(buffer: ShortArray, numFrames: Int) {
        val roomSize = (0.70f + params[0] * 0.25f).coerceIn(0.6f, 0.97f)
        val damp = (0.05f + params[1] * 0.70f).coerceIn(0.01f, 0.9f)
        val wet = params[2]
        val dry = 1.0f - wet * 0.5f

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            // 4 parallel combs
            var combOutL = 0f
            for (comb in combL) {
                combOutL += comb.process(inL, roomSize, damp)
            }
            combOutL *= 0.25f

            var combOutR = 0f
            for (comb in combR) {
                combOutR += comb.process(inR, roomSize, damp)
            }
            combOutR *= 0.25f

            // 2 allpass stages in series
            var apL = combOutL
            for (ap in allPassL) {
                apL = ap.process(apL, 0.5f)
            }

            var apR = combOutR
            for (ap in allPassR) {
                apR = ap.process(apR, 0.5f)
            }

            val outL = inL * dry + apL * wet
            val outR = inR * dry + apR * wet

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 4. VINYL SIM: Authentically emulates turntable character
    // Param 0: Noise / hiss level
    // Param 1: Wow & flutter pitch wobble depth
    // Param 2: Vinyl crackle / pop intensity
    // ------------------------------------------------------------------------
    private fun processVinylSim(buffer: ShortArray, numFrames: Int) {
        val noiseLevel = params[0] * 1200f
        val wowDepth = params[1] * 35f // Delay tap wobble in samples
        val crackleChance = params[2] * 0.003f
        val crackleGain = params[2] * 18000f

        val baseDelay = 60.0

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            // Store in circular buffer for pitch modulation
            vinylBufferL[vinylWritePos] = inL
            vinylBufferR[vinylWritePos] = inR

            // Low-frequency wow (0.8Hz) + faster flutter (5.5Hz)
            vinylLfoPhase += (2.0 * PI * 0.8) / sampleRate
            if (vinylLfoPhase > 2.0 * PI) vinylLfoPhase -= 2.0 * PI
            val flutterPhase = vinylLfoPhase * 6.875

            val wobble = (sin(vinylLfoPhase) * 0.8 + sin(flutterPhase) * 0.2) * wowDepth
            val readOffset = (baseDelay + wobble).coerceIn(1.0, (vinylBufferSize - 2).toDouble())

            var readIdx = vinylWritePos - readOffset
            while (readIdx < 0) readIdx += vinylBufferSize
            val idx0 = readIdx.toInt() % vinylBufferSize
            val idx1 = (idx0 + 1) % vinylBufferSize
            val frac = (readIdx - readIdx.toInt()).toFloat()

            // Interpolated pitch-wobbled audio
            var wL = vinylBufferL[idx0] * (1f - frac) + vinylBufferL[idx1] * frac
            var wR = vinylBufferR[idx0] * (1f - frac) + vinylBufferR[idx1] * frac

            vinylWritePos = (vinylWritePos + 1) % vinylBufferSize

            // Vintage lowpass rolloff (~7.5 kHz)
            vinylToneLpL += 0.35f * (wL - vinylToneLpL)
            vinylToneLpR += 0.35f * (wR - vinylToneLpR)
            wL = vinylToneLpL
            wR = vinylToneLpR

            // Vinyl surface noise (filtered pink/bandpass noise)
            val whiteNoise = (nextPseudoRandom() - 0.5f) * 2f
            vinylNoiseLp += 0.15f * (whiteNoise - vinylNoiseLp)
            val noise = (vinylNoiseLp) * noiseLevel

            // Vinyl crackle and pops
            if (nextPseudoRandom() < crackleChance) {
                vinylCrackleDecay = (nextPseudoRandom() * 2f - 1f) * crackleGain
            }
            val crackle = vinylCrackleDecay
            vinylCrackleDecay *= 0.85f

            buffer[i * 2] = clampShort(wL + noise + crackle)
            buffer[i * 2 + 1] = clampShort(wR + noise + crackle)
        }
    }

    // ------------------------------------------------------------------------
    // 5. COMPRESSOR: Dynamic range compression
    // Param 0: Threshold (0.05 to 0.70)
    // Param 1: Ratio (1.0 to 14.0)
    // Param 2: Makeup Gain (1.0 to 3.5)
    // ------------------------------------------------------------------------
    private fun processCompressor(buffer: ShortArray, numFrames: Int) {
        val threshold = (0.05f + params[0] * 0.65f) * 32767f
        val ratio = 1.0f + params[1] * 13.0f
        val makeup = 1.0f + params[2] * 2.5f

        val attackCoeff = 0.08f
        val releaseCoeff = 0.002f

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()
            val peak = max(abs(inL), abs(inR))

            // Peak envelope follower
            val targetCoeff = if (peak > compEnvelope) attackCoeff else releaseCoeff
            compEnvelope += targetCoeff * (peak - compEnvelope)

            // Calculate gain reduction
            var gain = 1.0f
            if (compEnvelope > threshold) {
                val excess = compEnvelope - threshold
                val compressedExcess = excess / ratio
                gain = (threshold + compressedExcess) / compEnvelope
            }

            val totalGain = gain * makeup
            buffer[i * 2] = clampShort(softClip(inL * totalGain))
            buffer[i * 2 + 1] = clampShort(softClip(inR * totalGain))
        }
    }

    // ------------------------------------------------------------------------
    // 6. RING MOD: Carrier multiplication
    // Param 0: Carrier frequency (30 Hz to 2400 Hz)
    // Param 1: Depth / LFO FM (0.0 to 1.0)
    // Param 2: Wet / Dry balance (0.0 to 1.0)
    // ------------------------------------------------------------------------
    private fun processRingMod(buffer: ShortArray, numFrames: Int) {
        val baseFreq = 30.0 * 80.0.pow(params[0].toDouble())
        val wet = params[2]
        val dry = 1.0f - wet

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            val carrier = sin(ringModPhase).toFloat()
            val freqPhaseDelta = (2.0 * PI * baseFreq) / sampleRate
            ringModPhase += freqPhaseDelta
            if (ringModPhase > 2.0 * PI) ringModPhase -= 2.0 * PI

            val modL = inL * carrier
            val modR = inR * carrier

            buffer[i * 2] = clampShort(inL * dry + modL * wet)
            buffer[i * 2 + 1] = clampShort(inR * dry + modR * wet)
        }
    }

    // ------------------------------------------------------------------------
    // 7. FLANGER: Modulated short delay with feedback
    // Param 0: LFO Rate (0.1 Hz to 4.0 Hz)
    // Param 1: Depth & feedback (0.0 to 0.85)
    // Param 2: Wet / Dry balance (0.0 to 1.0)
    // ------------------------------------------------------------------------
    private fun processFlanger(buffer: ShortArray, numFrames: Int) {
        val rate = 0.1 + params[0] * 3.9
        val feedback = (params[1] * 0.85f).coerceIn(0f, 0.90f)
        val wet = params[2]
        val dry = 1.0f - wet * 0.5f

        val minDelay = 0.0008f * sampleRate // 0.8ms
        val maxDelay = 0.0055f * sampleRate // 5.5ms
        val sweep = (maxDelay - minDelay) * 0.5f
        val midDelay = minDelay + sweep

        val phaseInc = (2.0 * PI * rate) / sampleRate

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            flangerLfoPhase += phaseInc
            if (flangerLfoPhase > 2.0 * PI) flangerLfoPhase -= 2.0 * PI

            val currentDelay = midDelay + (sin(flangerLfoPhase).toFloat() * sweep)

            var readIdx = flangerWritePos - currentDelay
            while (readIdx < 0) readIdx += flangerBufferSize
            val idx0 = readIdx.toInt() % flangerBufferSize
            val idx1 = (idx0 + 1) % flangerBufferSize
            val frac = readIdx - readIdx.toInt()

            val flangedL = flangerBufferL[idx0] * (1f - frac) + flangerBufferL[idx1] * frac
            val flangedR = flangerBufferR[idx0] * (1f - frac) + flangerBufferR[idx1] * frac

            flangerBufferL[flangerWritePos] = softClip(inL + flangedL * feedback)
            flangerBufferR[flangerWritePos] = softClip(inR + flangedR * feedback)

            flangerWritePos = (flangerWritePos + 1) % flangerBufferSize

            val outL = inL * dry + flangedL * wet
            val outR = inR * dry + flangedR * wet

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 8. PHASER: 4-stage allpass cascade
    // Param 0: LFO Rate (0.1 Hz to 3.5 Hz)
    // Param 1: Feedback (0.0 to 0.8)
    // Param 2: Wet / Dry balance (0.0 to 1.0)
    // ------------------------------------------------------------------------
    private fun processPhaser(buffer: ShortArray, numFrames: Int) {
        val rate = 0.1 + params[0] * 3.4
        val feedback = (params[1] * 0.80f).coerceIn(0f, 0.85f)
        val wet = params[2]
        val dry = 1.0f - wet * 0.5f

        val phaseInc = (2.0 * PI * rate) / sampleRate

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            phaserLfoPhase += phaseInc
            if (phaserLfoPhase > 2.0 * PI) phaserLfoPhase -= 2.0 * PI

            // Sweep cutoff between 250 Hz and 3800 Hz
            val lfo = (sin(phaserLfoPhase) + 1.0) * 0.5
            val cutoff = 250.0 + lfo * 3550.0
            val allpassCoeff = ((tan(PI * cutoff / sampleRate) - 1.0) / (tan(PI * cutoff / sampleRate) + 1.0)).toFloat()

            // Process Left through 4 allpass stages
            var apL = inL + phaserFeedbackL * feedback
            for (s in 0 until 4) {
                val nextAp = allpassCoeff * apL + phaserStagesL[s]
                phaserStagesL[s] = apL - allpassCoeff * nextAp
                apL = nextAp
            }
            phaserFeedbackL = apL

            // Process Right through 4 allpass stages
            var apR = inR + phaserFeedbackR * feedback
            for (s in 0 until 4) {
                val nextAp = allpassCoeff * apR + phaserStagesR[s]
                phaserStagesR[s] = apR - allpassCoeff * nextAp
                apR = nextAp
            }
            phaserFeedbackR = apR

            val outL = inL * dry + apL * wet
            val outR = inR * dry + apR * wet

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 9. ISOLATOR: 3-band DJ Isolator (Low, Mid, High)
    // Param 0: Low gain (0.0 = kill, 0.5 = neutral, 1.0 = boost +10dB)
    // Param 1: Mid gain (0.0 = kill, 0.5 = neutral, 1.0 = boost +10dB)
    // Param 2: High gain (0.0 = kill, 0.5 = neutral, 1.0 = boost +10dB)
    // ------------------------------------------------------------------------
    private fun processIsolator(buffer: ShortArray, numFrames: Int) {
        val lowGain = if (params[0] <= 0.5f) params[0] * 2f else 1.0f + (params[0] - 0.5f) * 3f
        val midGain = if (params[1] <= 0.5f) params[1] * 2f else 1.0f + (params[1] - 0.5f) * 3f
        val highGain = if (params[2] <= 0.5f) params[2] * 2f else 1.0f + (params[2] - 0.5f) * 3f

        // Crossover cutoff coefficients
        val lowCut = 320.0
        val highCut = 3600.0
        val lowAlpha = (2.0 * PI * lowCut / sampleRate).toFloat().coerceIn(0.001f, 0.99f)
        val highAlpha = (2.0 * PI * highCut / sampleRate).toFloat().coerceIn(0.001f, 0.99f)

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            // Left crossovers: 2-pole cascaded filters
            isoLowLp1L += lowAlpha * (inL - isoLowLp1L)
            isoLowLp2L += lowAlpha * (isoLowLp1L - isoLowLp2L)
            val bandLowL = isoLowLp2L

            isoHighHp1L += highAlpha * (inL - isoHighHp1L)
            isoHighHp2L += highAlpha * (isoHighHp1L - isoHighHp2L)
            val bandHighL = inL - isoHighHp2L

            val bandMidL = inL - bandLowL - bandHighL

            // Right crossovers
            isoLowLp1R += lowAlpha * (inR - isoLowLp1R)
            isoLowLp2R += lowAlpha * (isoLowLp1R - isoLowLp2R)
            val bandLowR = isoLowLp2R

            isoHighHp1R += highAlpha * (inR - isoHighHp1R)
            isoHighHp2R += highAlpha * (isoHighHp1R - isoHighHp2R)
            val bandHighR = inR - isoHighHp2R

            val bandMidR = inR - bandLowR - bandHighR

            val outL = bandLowL * lowGain + bandMidL * midGain + bandHighL * highGain
            val outR = bandLowR * lowGain + bandMidR * midGain + bandHighR * highGain

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // 10. LOFI: Bitcrushing, sample rate downsampling, drive
    // Param 0: Bit depth reduction (from 16 bits down to 3 bits)
    // Param 1: Downsampling hold factor (1 to 24 samples)
    // Param 2: Drive / saturation (1.0 to 6.0)
    // ------------------------------------------------------------------------
    private fun processLofi(buffer: ShortArray, numFrames: Int) {
        val bits = (16.0 - params[0] * 13.0).coerceIn(3.0, 16.0)
        val levels = 2.0.pow(bits).toFloat()
        val step = 65536f / levels

        val downsampleFactor = (1 + (params[1] * 23f).toInt()).coerceIn(1, 24)
        val drive = 1.0f + params[2] * 4.0f

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            if (lofiHoldCounter % downsampleFactor == 0) {
                // Apply drive
                var drivenL = inL * drive
                var drivenR = inR * drive

                // Quantize to bit depth
                drivenL = floor((drivenL / step) + 0.5f) * step
                drivenR = floor((drivenR / step) + 0.5f) * step

                lofiHeldSampleL = softClip(drivenL)
                lofiHeldSampleR = softClip(drivenR)
            }
            lofiHoldCounter++

            buffer[i * 2] = clampShort(lofiHeldSampleL)
            buffer[i * 2 + 1] = clampShort(lofiHeldSampleR)
        }
    }

    // ------------------------------------------------------------------------
    // 11. DJFX LOOPER: Iconic SP-404 DJFX looper
    // Param 0: Loop division length (40ms - 500ms)
    // Param 1: Speed & direction (0.0..0.45 rev, 0.45..0.55 stop, 0.55..1.0 fwd)
    // Param 2: Wet / engaged (if > 0.05 freezes loop, otherwise continuous capture)
    // ------------------------------------------------------------------------
    private fun processDjfxLooper(buffer: ShortArray, numFrames: Int) {
        val loopSec = 0.040f + params[0] * 0.460f
        djfxLoopLength = (loopSec * sampleRate).toInt().coerceIn(256, djfxMaxFrames - 1)

        val speedParam = params[1]
        val speed = when {
            speedParam < 0.45f -> -((0.45f - speedParam) / 0.45f * 1.5f + 0.2f) // Reverse
            speedParam > 0.55f -> ((speedParam - 0.55f) / 0.45f * 1.5f + 0.2f) // Forward
            else -> 0.0f // Scratch / tape stop
        }

        val isEngaged = params[2] > 0.05f

        if (!isEngaged) {
            djfxIsLocked = false
        } else if (!djfxIsLocked) {
            djfxIsLocked = true
            djfxReadPos = djfxWritePos.toDouble()
        }

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            if (!djfxIsLocked) {
                // Continuous ring buffer capture
                djfxBufferL[djfxWritePos] = inL
                djfxBufferR[djfxWritePos] = inR
                djfxWritePos = (djfxWritePos + 1) % djfxMaxFrames

                // Pass-through
                continue
            } else {
                // Read from locked buffer loop
                val loopStart = (djfxWritePos - djfxLoopLength + djfxMaxFrames) % djfxMaxFrames
                val currentOffset = (djfxReadPos - loopStart + djfxMaxFrames) % djfxLoopLength
                val actualRead = (loopStart + currentOffset).toInt() % djfxMaxFrames

                val loopL = djfxBufferL[actualRead]
                val loopR = djfxBufferR[actualRead]

                djfxReadPos += speed
                while (djfxReadPos < 0) djfxReadPos += djfxMaxFrames
                while (djfxReadPos >= djfxMaxFrames) djfxReadPos -= djfxMaxFrames

                buffer[i * 2] = clampShort(loopL)
                buffer[i * 2 + 1] = clampShort(loopR)
            }
        }
    }

    // ------------------------------------------------------------------------
    // 12. TAPE ECHO: Tape delay with warmth, saturation & flutter
    // Param 0: Echo delay time (40ms - 800ms)
    // Param 1: Feedback gain with tape saturation (0.0 - 0.90)
    // Param 2: Tone & flutter (HF roll-off + subtle pitch flutter)
    // ------------------------------------------------------------------------
    private fun processTapeEcho(buffer: ShortArray, numFrames: Int) {
        val delaySec = 0.040f + params[0] * 0.760f
        val baseDelay = delaySec * sampleRate
        val feedback = (params[1] * 0.90f).coerceIn(0f, 0.94f)
        val toneDamp = 0.15f + params[2] * 0.55f

        val flutterDepth = params[2] * 8f

        for (i in 0 until numFrames) {
            val inL = buffer[i * 2].toFloat()
            val inR = buffer[i * 2 + 1].toFloat()

            tapeEchoLfoPhase += (2.0 * PI * 3.2) / sampleRate
            if (tapeEchoLfoPhase > 2.0 * PI) tapeEchoLfoPhase -= 2.0 * PI

            val flutter = sin(tapeEchoLfoPhase).toFloat() * flutterDepth
            val readOffset = (baseDelay + flutter).coerceIn(1f, (tapeEchoBufferSize - 2).toFloat())

            var readIdx = tapeEchoWritePos - readOffset
            while (readIdx < 0) readIdx += tapeEchoBufferSize
            val idx0 = readIdx.toInt() % tapeEchoBufferSize
            val idx1 = (idx0 + 1) % tapeEchoBufferSize
            val frac = readIdx - readIdx.toInt()

            val delayedL = tapeEchoBufferL[idx0] * (1f - frac) + tapeEchoBufferL[idx1] * frac
            val delayedR = tapeEchoBufferR[idx0] * (1f - frac) + tapeEchoBufferR[idx1] * frac

            // Lowpass filter in tape feedback loop (warm analog roll-off)
            tapeEchoFilterL += toneDamp * (delayedL - tapeEchoFilterL)
            tapeEchoFilterR += toneDamp * (delayedR - tapeEchoFilterR)

            // Analog tape saturation soft clipping
            val satL = tapeSaturate(inL + tapeEchoFilterL * feedback)
            val satR = tapeSaturate(inR + tapeEchoFilterR * feedback)

            tapeEchoBufferL[tapeEchoWritePos] = satL
            tapeEchoBufferR[tapeEchoWritePos] = satR
            tapeEchoWritePos = (tapeEchoWritePos + 1) % tapeEchoBufferSize

            val outL = inL + tapeEchoFilterL * 0.8f
            val outR = inR + tapeEchoFilterR * 0.8f

            buffer[i * 2] = clampShort(outL)
            buffer[i * 2 + 1] = clampShort(outR)
        }
    }

    // ------------------------------------------------------------------------
    // DSP HELPER FUNCTIONS
    // ------------------------------------------------------------------------
    private fun clampShort(value: Float): Short {
        return value.coerceIn(-32768f, 32767f).toInt().toShort()
    }

    private fun softClip(x: Float): Float {
        val norm = x / 32767f
        val clipped = if (norm > 1f) {
            1f
        } else if (norm < -1f) {
            -1f
        } else {
            norm - (norm * norm * norm) / 3f
        }
        return clipped * 32767f
    }

    private fun tapeSaturate(x: Float): Float {
        val norm = x / 32767f
        val sat = tanh(norm * 1.2).toFloat()
        return sat * 32767f
    }

    private fun nextPseudoRandom(): Float {
        randomSeed = (randomSeed * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return (randomSeed and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat()
    }
}
