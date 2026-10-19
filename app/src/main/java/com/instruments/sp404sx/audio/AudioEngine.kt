package com.instruments.sp404sx.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.instruments.sp404sx.model.BankId
import com.instruments.sp404sx.model.EffectType
import com.instruments.sp404sx.model.PadState
import com.instruments.sp404sx.model.PlayMode
import com.instruments.sp404sx.model.Sample
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core real-time audio playback engine for the SP-404SX sampler.
 * Uses a single streaming AudioTrack mixer thread at 44.1kHz 16-bit stereo,
 * multi-pad polyphonic mixing, pitch shifting, pad linking, and an in-line DSP effects processor.
 */
class AudioEngine(private val context: Context) {

    private val tag = "AudioEngine"
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val SAMPLE_RATE = 44100
        const val NUM_PADS_PER_BANK = 12
        const val FRAMES_PER_BUFFER = 512 // 512 stereo frames = 1024 shorts
        const val SAMPLES_PER_BUFFER = FRAMES_PER_BUFFER * 2
    }

    /**
     * Internal playback state for an individual pad.
     */
    private class PadPlayer(
        val bankId: BankId,
        val padIndex: Int
    ) {
        @Volatile var audioData: ShortArray? = null
        @Volatile var reversedAudioData: ShortArray? = null
        @Volatile var position: Double = 0.0
        @Volatile var isPlaying: Boolean = false
        @Volatile var volume: Float = 1.0f
        @Volatile var pitch: Float = 1.0f
        @Volatile var playMode: PlayMode = PlayMode.TRIGGER
        @Volatile var startPoint: Float = 0f
        @Volatile var endPoint: Float = 1.0f
        @Volatile var isReversed: Boolean = false
    }

    // Audio mixing and output state
    private var audioTrack: AudioTrack? = null
    private var mixerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile private var masterVolume: Float = 1.0f

    // Effects processor
    private val effectsProcessor = EffectsProcessor(SAMPLE_RATE)

    // Pad state and player maps
    private val padStates = mutableMapOf<BankId, Array<PadState>>()
    private val padPlayers = mutableMapOf<BankId, Array<PadPlayer>>()

    /** Callback invoked whenever a pad starts or stops playback */
    var onPadStateChanged: ((BankId, Int, Boolean) -> Unit)? = null

    init {
        // Initialize states for Banks A, B, C with 12 pads each
        for (bank in BankId.values()) {
            val states = Array(NUM_PADS_PER_BANK) { index ->
                PadState(padIndex = index, bankId = bank)
            }
            val players = Array(NUM_PADS_PER_BANK) { index ->
                PadPlayer(bankId = bank, padIndex = index)
            }
            padStates[bank] = states
            padPlayers[bank] = players
        }
    }

    /**
     * Initializes the AudioTrack mixer and starts the background mixing thread.
     */
    @Synchronized
    fun initialize() {
        if (isRunning.get()) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeBytes = maxOf(minBufferSize, SAMPLES_PER_BUFFER * 2 * 4)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSizeBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack?.play()
        isRunning.set(true)

        startMixerThread()
        Log.i(tag, "AudioEngine initialized successfully.")
    }

    /**
     * Background audio mixing thread.
     * Continuously mixes playing pads into a 512-frame stereo buffer, applies effects,
     * and streams to AudioTrack.
     */
    private fun startMixerThread() {
        mixerThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val floatMixBuffer = FloatArray(SAMPLES_PER_BUFFER)
            val outputShortBuffer = ShortArray(SAMPLES_PER_BUFFER)

            while (isRunning.get()) {
                // Clear mix accumulator
                floatMixBuffer.fill(0f)

                var activeVoices = 0

                // Mix all active pad players
                for (bank in BankId.values()) {
                    val players = padPlayers[bank] ?: continue
                    for (i in 0 until NUM_PADS_PER_BANK) {
                        val player = players[i]
                        if (!player.isPlaying) continue

                        val pcm = if (player.isReversed && player.reversedAudioData != null) {
                            player.reversedAudioData!!
                        } else {
                            player.audioData
                        }

                        if (pcm == null || pcm.isEmpty()) {
                            stopPadInternal(bank, i)
                            continue
                        }

                        val totalFrames = pcm.size / 2
                        val startFrame = (player.startPoint * totalFrames).toInt().coerceIn(0, totalFrames - 1)
                        val endFrame = (player.endPoint * totalFrames).toInt().coerceIn(startFrame + 1, totalFrames)
                        val rangeFrames = endFrame - startFrame

                        if (rangeFrames <= 0) {
                            stopPadInternal(bank, i)
                            continue
                        }

                        activeVoices++
                        val padVol = player.volume
                        val padPitch = player.pitch
                        var currentPos = player.position

                        for (frame in 0 until FRAMES_PER_BUFFER) {
                            val frameIndex = currentPos.toInt()

                            if (frameIndex >= rangeFrames) {
                                if (player.playMode == PlayMode.LOOP) {
                                    // Wrap loop
                                    currentPos %= rangeFrames
                                } else {
                                    // Sample reached end
                                    stopPadInternal(bank, i)
                                    break
                                }
                            }

                            val idx = currentPos.toInt()
                            val actualFrame = startFrame + idx
                            val nextFrame = if (actualFrame + 1 < endFrame) {
                                actualFrame + 1
                            } else if (player.playMode == PlayMode.LOOP) {
                                startFrame
                            } else {
                                actualFrame
                            }

                            val frac = (currentPos - idx).toFloat()

                            // Linear interpolation for pitch shifting
                            val s0L = pcm[actualFrame * 2].toFloat()
                            val s1L = pcm[nextFrame * 2].toFloat()
                            val sampleL = s0L + frac * (s1L - s0L)

                            val s0R = pcm[actualFrame * 2 + 1].toFloat()
                            val s1R = pcm[nextFrame * 2 + 1].toFloat()
                            val sampleR = s0R + frac * (s1R - s0R)

                            floatMixBuffer[frame * 2] += sampleL * padVol
                            floatMixBuffer[frame * 2 + 1] += sampleR * padVol

                            currentPos += padPitch
                        }

                        player.position = currentPos
                    }
                }

                // Apply master volume and convert to 16-bit PCM
                val mVol = masterVolume
                for (s in 0 until SAMPLES_PER_BUFFER) {
                    val mixedSample = floatMixBuffer[s] * mVol
                    outputShortBuffer[s] = mixedSample.coerceIn(-32768f, 32767f).toInt().toShort()
                }

                // Process in-line DSP effects
                effectsProcessor.process(outputShortBuffer, FRAMES_PER_BUFFER)

                // Write mixed stereo frames to AudioTrack
                val track = audioTrack
                if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(outputShortBuffer, 0, SAMPLES_PER_BUFFER)
                }
            }
        }, "SP404SX-AudioMixerThread")

        mixerThread?.start()
    }

    /**
     * Loads a sample from a file path into the designated pad.
     */
    fun loadSample(bankId: BankId, padIndex: Int, filePath: String): Boolean {
        if (padIndex !in 0 until NUM_PADS_PER_BANK) return false

        try {
            // First attempt native RIFF WAV parsing
            var audioInfo = SampleEditor.readWav(filePath)

            // If WAV reader returned null, fallback to Android MediaCodec decoding (supports MP3, OGG, AAC, etc.)
            if (audioInfo == null) {
                audioInfo = decodeWithMediaCodec { extractor ->
                    extractor.setDataSource(filePath)
                }
            }

            if (audioInfo == null) {
                Log.e(tag, "Failed to decode audio file: $filePath")
                return false
            }

            var (pcmData, srcSampleRate, srcChannels) = audioInfo

            // Resample to 44100 Hz if necessary
            if (srcSampleRate != SAMPLE_RATE && srcSampleRate > 0) {
                val ratio = srcSampleRate.toFloat() / SAMPLE_RATE.toFloat()
                pcmData = SampleEditor.changePitch(pcmData, srcChannels, ratio)
            }

            // Convert mono to stereo if necessary
            val stereoPcm = if (srcChannels == 1) {
                val stereo = ShortArray(pcmData.size * 2)
                for (i in pcmData.indices) {
                    stereo[i * 2] = pcmData[i]
                    stereo[i * 2 + 1] = pcmData[i]
                }
                stereo
            } else {
                pcmData
            }

            val file = File(filePath)
            val durationMs = ((stereoPcm.size / 2).toDouble() / SAMPLE_RATE * 1000.0).toLong()

            val sample = Sample(
                id = UUID.randomUUID().toString(),
                name = file.nameWithoutExtension,
                filePath = filePath,
                durationMs = durationMs,
                sampleRate = SAMPLE_RATE,
                channels = 2,
                playMode = PlayMode.TRIGGER,
                volume = 1.0f,
                pitch = 1.0f,
                startPoint = 0f,
                endPoint = 1.0f,
                isReversed = false
            )

            val reversedData = SampleEditor.reverse(stereoPcm)

            val state = padStates[bankId]?.get(padIndex) ?: return false
            val player = padPlayers[bankId]?.get(padIndex) ?: return false

            state.sample = sample
            player.audioData = stereoPcm
            player.reversedAudioData = reversedData
            player.volume = sample.volume
            player.pitch = sample.pitch
            player.playMode = sample.playMode
            player.startPoint = sample.startPoint
            player.endPoint = sample.endPoint
            player.isReversed = sample.isReversed
            player.position = 0.0

            Log.i(tag, "Loaded sample '${sample.name}' into Bank ${bankId.displayName} Pad ${padIndex + 1}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error loading sample from file: ${e.message}", e)
            return false
        }
    }

    /**
     * Loads a sample from an Android raw resource into the designated pad.
     */
    fun loadSampleFromResource(bankId: BankId, padIndex: Int, resId: Int): Boolean {
        if (padIndex !in 0 until NUM_PADS_PER_BANK) return false

        try {
            // First try reading as standard WAV stream
            var audioInfo: Triple<ShortArray, Int, Int>? = null
            try {
                context.resources.openRawResource(resId).use { stream ->
                    audioInfo = SampleEditor.readWavFromStream(BufferedInputStream(stream))
                }
            } catch (e: Exception) {
                // Stream decode failed, fallback to MediaExtractor
            }

            // Fallback to MediaExtractor with AssetFileDescriptor
            if (audioInfo == null) {
                audioInfo = decodeWithMediaCodec { extractor ->
                    val afd: AssetFileDescriptor = context.resources.openRawResourceFd(resId)
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
            }

            if (audioInfo == null) {
                Log.e(tag, "Failed to decode resource audio: $resId")
                return false
            }

            var (pcmData, srcSampleRate, srcChannels) = audioInfo!!

            // Resample to 44100 Hz
            if (srcSampleRate != SAMPLE_RATE && srcSampleRate > 0) {
                val ratio = srcSampleRate.toFloat() / SAMPLE_RATE.toFloat()
                pcmData = SampleEditor.changePitch(pcmData, srcChannels, ratio)
            }

            // Convert to stereo
            val stereoPcm = if (srcChannels == 1) {
                val stereo = ShortArray(pcmData.size * 2)
                for (i in pcmData.indices) {
                    stereo[i * 2] = pcmData[i]
                    stereo[i * 2 + 1] = pcmData[i]
                }
                stereo
            } else {
                pcmData
            }

            val entryName = try {
                context.resources.getResourceEntryName(resId)
            } catch (e: Exception) {
                "sample_$resId"
            }

            val durationMs = ((stereoPcm.size / 2).toDouble() / SAMPLE_RATE * 1000.0).toLong()

            val sample = Sample(
                id = UUID.randomUUID().toString(),
                name = entryName,
                filePath = "raw://$entryName",
                durationMs = durationMs,
                sampleRate = SAMPLE_RATE,
                channels = 2,
                playMode = PlayMode.TRIGGER,
                volume = 1.0f,
                pitch = 1.0f,
                startPoint = 0f,
                endPoint = 1.0f,
                isReversed = false
            )

            val reversedData = SampleEditor.reverse(stereoPcm)

            val state = padStates[bankId]?.get(padIndex) ?: return false
            val player = padPlayers[bankId]?.get(padIndex) ?: return false

            state.sample = sample
            player.audioData = stereoPcm
            player.reversedAudioData = reversedData
            player.volume = sample.volume
            player.pitch = sample.pitch
            player.playMode = sample.playMode
            player.startPoint = sample.startPoint
            player.endPoint = sample.endPoint
            player.isReversed = sample.isReversed
            player.position = 0.0

            Log.i(tag, "Loaded resource sample '$entryName' into Bank ${bankId.displayName} Pad ${padIndex + 1}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error loading sample from resource: ${e.message}", e)
            return false
        }
    }

    /**
     * Triggers pad playback. Called when pad is pressed.
     */
    fun triggerPad(bankId: BankId, padIndex: Int) {
        triggerPadInternal(bankId, padIndex, triggerLinked = true)
    }

    private fun triggerPadInternal(bankId: BankId, padIndex: Int, triggerLinked: Boolean) {
        val state = getPadState(bankId, padIndex) ?: return
        val player = getPlayer(bankId, padIndex) ?: return

        if (player.audioData == null) return

        when (player.playMode) {
            PlayMode.TRIGGER -> {
                player.position = 0.0
                player.isPlaying = true
                state.isPlaying = true
                dispatchPadState(bankId, padIndex, true)
            }
            PlayMode.LOOP -> {
                if (player.isPlaying) {
                    player.isPlaying = false
                    state.isPlaying = false
                    dispatchPadState(bankId, padIndex, false)
                } else {
                    player.position = 0.0
                    player.isPlaying = true
                    state.isPlaying = true
                    dispatchPadState(bankId, padIndex, true)
                }
            }
            PlayMode.GATE -> {
                player.position = 0.0
                player.isPlaying = true
                state.isPlaying = true
                dispatchPadState(bankId, padIndex, true)
            }
            PlayMode.ONE_SHOT -> {
                // If already playing, 1SHOT ignores subsequent triggers
                if (!player.isPlaying) {
                    player.position = 0.0
                    player.isPlaying = true
                    state.isPlaying = true
                    dispatchPadState(bankId, padIndex, true)
                }
            }
        }

        // Trigger linked pad simultaneously
        if (triggerLinked && state.isLinked && state.linkedPadIndex in 0 until NUM_PADS_PER_BANK &&
            state.linkedPadIndex != padIndex
        ) {
            triggerPadInternal(bankId, state.linkedPadIndex, triggerLinked = false)
        }
    }

    /**
     * Releases pad. Relevant for GATE mode playback.
     */
    fun releasePad(bankId: BankId, padIndex: Int) {
        val state = getPadState(bankId, padIndex) ?: return
        val player = getPlayer(bankId, padIndex) ?: return

        if (player.playMode == PlayMode.GATE && player.isPlaying) {
            player.isPlaying = false
            state.isPlaying = false
            dispatchPadState(bankId, padIndex, false)

            if (state.isLinked && state.linkedPadIndex in 0 until NUM_PADS_PER_BANK &&
                state.linkedPadIndex != padIndex
            ) {
                val linkedPlayer = getPlayer(bankId, state.linkedPadIndex)
                val linkedState = getPadState(bankId, state.linkedPadIndex)
                if (linkedPlayer?.playMode == PlayMode.GATE && linkedPlayer.isPlaying) {
                    linkedPlayer.isPlaying = false
                    linkedState?.isPlaying = false
                    dispatchPadState(bankId, state.linkedPadIndex, false)
                }
            }
        }
    }

    /**
     * Forces immediate stop of a pad's playback.
     */
    fun stopPad(bankId: BankId, padIndex: Int) {
        stopPadInternal(bankId, padIndex)
    }

    private fun stopPadInternal(bankId: BankId, padIndex: Int) {
        val state = getPadState(bankId, padIndex)
        val player = getPlayer(bankId, padIndex)

        if (player != null && player.isPlaying) {
            player.isPlaying = false
            state?.isPlaying = false
            dispatchPadState(bankId, padIndex, false)
        }
    }

    /**
     * Stops playback of all pads across all banks.
     */
    fun stopAll() {
        for (bank in BankId.values()) {
            for (i in 0 until NUM_PADS_PER_BANK) {
                stopPadInternal(bank, i)
            }
        }
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0.0f, 1.0f)
    }

    fun setPadVolume(bankId: BankId, padIndex: Int, volume: Float) {
        val vol = volume.coerceIn(0.0f, 1.0f)
        getPlayer(bankId, padIndex)?.volume = vol
        getPadState(bankId, padIndex)?.sample?.volume = vol
    }

    fun setPadPitch(bankId: BankId, padIndex: Int, pitch: Float) {
        val p = pitch.coerceIn(0.5f, 2.0f)
        getPlayer(bankId, padIndex)?.pitch = p
        getPadState(bankId, padIndex)?.sample?.pitch = p
    }

    fun setPadPlayMode(bankId: BankId, padIndex: Int, mode: PlayMode) {
        getPlayer(bankId, padIndex)?.playMode = mode
        getPadState(bankId, padIndex)?.sample?.playMode = mode
    }

    fun setPadStartPoint(bankId: BankId, padIndex: Int, startPoint: Float) {
        val sp = startPoint.coerceIn(0.0f, 1.0f)
        getPlayer(bankId, padIndex)?.startPoint = sp
        getPadState(bankId, padIndex)?.sample?.startPoint = sp
    }

    fun setPadEndPoint(bankId: BankId, padIndex: Int, endPoint: Float) {
        val ep = endPoint.coerceIn(0.0f, 1.0f)
        getPlayer(bankId, padIndex)?.endPoint = ep
        getPadState(bankId, padIndex)?.sample?.endPoint = ep
    }

    fun setPadReverse(bankId: BankId, padIndex: Int, isReversed: Boolean) {
        getPlayer(bankId, padIndex)?.isReversed = isReversed
        getPadState(bankId, padIndex)?.sample?.isReversed = isReversed
    }


    // Effects control
    fun setActiveEffect(effectType: EffectType?) {
        effectsProcessor.activeEffect = effectType
        effectsProcessor.reset()
    }

    fun getActiveEffect(): EffectType? = effectsProcessor.activeEffect

    fun setEffectParam(paramIndex: Int, value: Float) {
        if (paramIndex in 0..2) {
            effectsProcessor.params[paramIndex] = value.coerceIn(0.0f, 1.0f)
        }
    }

    fun getEffectParams(): FloatArray = effectsProcessor.params.clone()

    fun isPadPlaying(bankId: BankId, padIndex: Int): Boolean {
        return getPlayer(bankId, padIndex)?.isPlaying ?: false
    }

    fun getPadState(bankId: BankId, padIndex: Int): PadState? {
        if (padIndex !in 0 until NUM_PADS_PER_BANK) return null
        return padStates[bankId]?.get(padIndex)
    }

    fun getAllPadStates(bankId: BankId): List<PadState> {
        return padStates[bankId]?.toList() ?: emptyList()
    }

    /**
     * Links two pads together so triggering either triggers both.
     */
    fun linkPads(bankId: BankId, padIndex1: Int, padIndex2: Int) {
        if (padIndex1 == padIndex2) return
        val s1 = getPadState(bankId, padIndex1) ?: return
        val s2 = getPadState(bankId, padIndex2) ?: return

        s1.isLinked = true
        s1.linkedPadIndex = padIndex2

        s2.isLinked = true
        s2.linkedPadIndex = padIndex1
    }

    /**
     * Unlinks a pad from its partner pad.
     */
    fun unlinkPad(bankId: BankId, padIndex: Int) {
        val s1 = getPadState(bankId, padIndex) ?: return
        val partner = s1.linkedPadIndex
        s1.isLinked = false
        s1.linkedPadIndex = -1

        if (partner in 0 until NUM_PADS_PER_BANK) {
            val s2 = getPadState(bankId, partner)
            if (s2?.linkedPadIndex == padIndex) {
                s2.isLinked = false
                s2.linkedPadIndex = -1
            }
        }
    }

    /**
     * Releases audio resources and stops background threads.
     */
    @Synchronized
    fun release() {
        stopAll()
        isRunning.set(false)

        try {
            mixerThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(tag, "Interrupted while stopping mixer thread.")
        }
        mixerThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null

        effectsProcessor.reset()
        Log.i(tag, "AudioEngine released.")
    }

    private fun getPlayer(bankId: BankId, padIndex: Int): PadPlayer? {
        if (padIndex !in 0 until NUM_PADS_PER_BANK) return null
        return padPlayers[bankId]?.get(padIndex)
    }

    private fun dispatchPadState(bankId: BankId, padIndex: Int, isPlaying: Boolean) {
        mainHandler.post {
            onPadStateChanged?.invoke(bankId, padIndex, isPlaying)
        }
    }

    /**
     * Decodes compressed audio formats (MP3, AAC, OGG, FLAC) into 16-bit PCM using Android's MediaCodec.
     */
    private fun decodeWithMediaCodec(setSource: (MediaExtractor) -> Unit): Triple<ShortArray, Int, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            setSource(extractor)

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

            if (trackIndex < 0 || format == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var isEos = false

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                SAMPLE_RATE
            }

            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            val timeoutUs = 5000L

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outputBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                    }
                }
            }

            val rawBytes = pcmOut.toByteArray()
            val shortCount = rawBytes.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

            Triple(shorts, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(tag, "MediaCodec decoding exception: ${e.message}", e)
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
