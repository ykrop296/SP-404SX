package com.instruments.sp404sx

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.instruments.sp404sx.audio.AudioEngine
import com.instruments.sp404sx.audio.MicRecorder
import com.instruments.sp404sx.audio.Sequencer
import com.instruments.sp404sx.model.BankId
import com.instruments.sp404sx.model.EffectType
import com.instruments.sp404sx.model.PlayMode
import com.instruments.sp404sx.model.Sample
import com.instruments.sp404sx.storage.FileImporter
import com.instruments.sp404sx.storage.SampleStorage
import com.instruments.sp404sx.ui.ControlButtonView
import com.instruments.sp404sx.ui.DisplayView
import com.instruments.sp404sx.ui.KnobView
import com.instruments.sp404sx.ui.PadView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Main Activity integrating all SP-404SX components:
 * AudioEngine, Sequencer, MicRecorder, SampleStorage, FileImporter, and custom hardware UI controls.
 */
class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // Core Audio & Storage Components
    private lateinit var audioEngine: AudioEngine
    private lateinit var sequencer: Sequencer
    private lateinit var micRecorder: MicRecorder
    private lateinit var sampleStorage: SampleStorage
    private lateinit var fileImporter: FileImporter

    // UI Controls
    private lateinit var displayView: DisplayView
    private lateinit var knobVolume: KnobView
    private lateinit var knobCtrl1: KnobView
    private lateinit var knobCtrl2: KnobView
    private lateinit var knobCtrl3: KnobView

    private lateinit var btnBankA: ControlButtonView
    private lateinit var btnBankB: ControlButtonView
    private lateinit var btnBankC: ControlButtonView
    private lateinit var btnImport: ControlButtonView

    private lateinit var btnRec: ControlButtonView
    private lateinit var btnPattern: ControlButtonView
    private lateinit var btnFx: ControlButtonView
    private lateinit var btnHold: ControlButtonView
    private lateinit var btnRoll: ControlButtonView

    private lateinit var btnPlay: ControlButtonView
    private lateinit var btnStop: ControlButtonView
    private lateinit var btnCopy: ControlButtonView
    private lateinit var btnDel: ControlButtonView
    private lateinit var btnStartEnd: ControlButtonView
    private lateinit var btnPadLink: ControlButtonView

    private lateinit var pads: Array<PadView>

    // Operating States
    private var currentBank: BankId = BankId.A
    private var selectedEditingPad: Int = -1

    private var isFxMode: Boolean = false
    private var isHoldMode: Boolean = false
    private var isRollMode: Boolean = false
    private var isRecordingStandby: Boolean = false
    private var recordingTargetPad: Int = -1

    private var isCopyMode: Boolean = false
    private var copySourcePad: Int = -1

    private var isDeleteMode: Boolean = false
    private var selectedDeletePad: Int = -1

    private var isStartEndMode: Boolean = false

    private var isPadLinkMode: Boolean = false
    private var padLinkFirstPad: Int = -1

    // Timers & Runnables
    private var recBlinkRunnable: Runnable? = null
    private var recBlinkState: Boolean = false

    private var rollRunnable: Runnable? = null
    private var rollingPadIndex: Int = -1

    private var vuRunnable: Runnable? = null

    private var patternLongPressTriggered: Boolean = false
    private val patternLongPressRunnable = Runnable {
        patternLongPressTriggered = true
        sequencer.clear()
        btnPattern.isActive = false
        btnPlay.isActive = false
        displayView.showMessage("PTN CLEARED")
    }

    // Permission Request Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordGranted) {
            displayView.showMessage("MIC PERM OFF")
        }
    }

    // File Picker Document Launcher
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importAudioFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Core Services
        audioEngine = AudioEngine(this)
        audioEngine.initialize()

        sequencer = Sequencer(audioEngine)
        micRecorder = MicRecorder(this)
        sampleStorage = SampleStorage(this)
        fileImporter = FileImporter(this)

        // 2. Bind Views & UI Listeners
        initViews()
        setupListeners()
        setupAudioCallbacks()

        // 3. Request Runtime Permissions
        requestRequiredPermissions()

        // 4. Load Saved and Factory Samples
        loadBankSamples()
    }

    /**
     * Finds and assigns references to all UI views defined in activity_main.xml.
     */
    private fun initViews() {
        displayView = findViewById(R.id.displayView)
        knobVolume = findViewById(R.id.knobVolume)
        knobCtrl1 = findViewById(R.id.knobCtrl1)
        knobCtrl2 = findViewById(R.id.knobCtrl2)
        knobCtrl3 = findViewById(R.id.knobCtrl3)

        btnBankA = findViewById(R.id.btnBankA)
        btnBankB = findViewById(R.id.btnBankB)
        btnBankC = findViewById(R.id.btnBankC)
        btnImport = findViewById(R.id.btnImport)

        btnRec = findViewById(R.id.btnRec)
        btnPattern = findViewById(R.id.btnPattern)
        btnFx = findViewById(R.id.btnFx)
        btnHold = findViewById(R.id.btnHold)
        btnRoll = findViewById(R.id.btnRoll)

        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnCopy = findViewById(R.id.btnCopy)
        btnDel = findViewById(R.id.btnDel)
        btnStartEnd = findViewById(R.id.btnStartEnd)
        btnPadLink = findViewById(R.id.btnPadLink)

        pads = arrayOf(
            findViewById(R.id.pad1),
            findViewById(R.id.pad2),
            findViewById(R.id.pad3),
            findViewById(R.id.pad4),
            findViewById(R.id.pad5),
            findViewById(R.id.pad6),
            findViewById(R.id.pad7),
            findViewById(R.id.pad8),
            findViewById(R.id.pad9),
            findViewById(R.id.pad10),
            findViewById(R.id.pad11),
            findViewById(R.id.pad12)
        )

        // Set static button labels and LED colors
        btnBankA.text = "BANK A"
        btnBankB.text = "BANK B"
        btnBankC.text = "BANK C"
        btnImport.text = "IMPORT"

        btnRec.text = "REC"
        btnRec.ledColor = 0xFFFF2222.toInt()

        btnPattern.text = "PTN"
        btnPattern.ledColor = 0xFFFFB347.toInt()

        btnFx.text = "FX"
        btnFx.ledColor = 0xFFFF9900.toInt()

        btnHold.text = "HOLD"
        btnHold.ledColor = 0xFFFF6B35.toInt()

        btnRoll.text = "ROLL"
        btnRoll.ledColor = 0xFFFF6B35.toInt()

        btnPlay.text = "PLAY"
        btnPlay.ledColor = 0xFF22FF44.toInt()

        btnStop.text = "STOP"
        btnStop.ledColor = 0xFFFF4444.toInt()

        btnCopy.text = "COPY"
        btnDel.text = "DEL"
        btnStartEnd.text = "START/END"
        btnPadLink.text = "PAD LINK"

        // Initialize Pad index and numbers
        for (i in pads.indices) {
            pads[i].padIndex = i
            pads[i].padNumber = i + 1
        }

        // Initialize Knobs
        knobVolume.label = "VOLUME"
        knobVolume.value = 0.85f

        knobCtrl1.label = "CTRL 1"
        knobCtrl1.value = 0.5f

        knobCtrl2.label = "CTRL 2"
        knobCtrl2.value = 0.5f

        knobCtrl3.label = "CTRL 3"
        knobCtrl3.value = 0.5f
    }

    /**
     * Attaches interactive listeners for knobs, buttons, and pads.
     */
    private fun setupListeners() {
        // --- Bank Selection ---
        btnBankA.onButtonPressed = { switchToBank(BankId.A) }
        btnBankB.onButtonPressed = { switchToBank(BankId.B) }
        btnBankC.onButtonPressed = { switchToBank(BankId.C) }

        // --- Master Knobs ---
        knobVolume.onValueChanged = { v ->
            audioEngine.setMasterVolume(v)
        }

        knobCtrl1.onValueChanged = { v ->
            onCtrlKnobChanged(0, v)
        }

        knobCtrl2.onValueChanged = { v ->
            onCtrlKnobChanged(1, v)
        }

        knobCtrl3.onValueChanged = { v ->
            onCtrlKnobChanged(2, v)
        }

        // --- Pads ---
        for (i in pads.indices) {
            pads[i].onPadPressed = { padIndex ->
                handlePadPress(padIndex)
            }
            pads[i].onPadReleased = { padIndex ->
                handlePadRelease(padIndex)
            }
        }

        // --- Rec Button ---
        btnRec.onButtonPressed = {
            handleRecButtonClick()
        }

        // --- Pattern Button ---
        btnPattern.onButtonPressed = {
            patternLongPressTriggered = false
            mainHandler.postDelayed(patternLongPressRunnable, 750L)
        }
        btnPattern.onButtonReleased = {
            mainHandler.removeCallbacks(patternLongPressRunnable)
            if (!patternLongPressTriggered) {
                handlePatternButtonClick()
            }
        }

        // --- FX Button ---
        btnFx.onButtonPressed = {
            handleFxToggle()
        }

        // --- Hold Button ---
        btnHold.onButtonPressed = {
            isHoldMode = !isHoldMode
            btnHold.isActive = isHoldMode
            displayView.showMessage(if (isHoldMode) "HOLD ON" else "HOLD OFF")
        }

        // --- Roll Button ---
        btnRoll.onButtonPressed = {
            isRollMode = true
            btnRoll.isActive = true
            displayView.showMessage("ROLL MODE")
        }
        btnRoll.onButtonReleased = {
            isRollMode = false
            btnRoll.isActive = false
            stopRoll()
        }

        // --- Play Button ---
        btnPlay.onButtonPressed = {
            if (sequencer.isPlaying()) {
                sequencer.stopPlayback()
            } else if (sequencer.hasPattern()) {
                sequencer.startPlayback()
            } else {
                displayView.showMessage("NO PATTERN")
            }
        }

        // --- Stop Button ---
        btnStop.onButtonPressed = {
            handleStopAll()
        }

        // --- Copy Button ---
        btnCopy.onButtonPressed = {
            handleCopyToggle()
        }

        // --- Del Button ---
        btnDel.onButtonPressed = {
            handleDeleteToggle()
        }

        // --- Start/End Button ---
        btnStartEnd.onButtonPressed = {
            handleStartEndToggle()
        }

        // --- Pad Link Button ---
        btnPadLink.onButtonPressed = {
            handlePadLinkToggle()
        }

        // --- Import Button ---
        btnImport.onButtonPressed = {
            openDocumentLauncher.launch(fileImporter.getSupportedMimeTypes())
        }
    }

    /**
     * Registers callbacks for audio engine, sequencer, and microphone updates.
     */
    private fun setupAudioCallbacks() {
        // Pad playback state changes
        audioEngine.onPadStateChanged = { bankId, padIndex, isPlaying ->
            if (bankId == currentBank && padIndex in pads.indices) {
                pads[padIndex].isActive = isPlaying
            }
            if (isPlaying) {
                startVuMeterMonitoring()
            }
        }

        // Sequencer beat tick
        sequencer.onBeatTick = { beat ->
            val modeStr = if (sequencer.isRecording()) "PTN REC" else "PTN PLAY"
            displayView.setText(modeStr, "BEAT: $beat / 4")
        }

        // Sequencer state changes
        sequencer.onStateChanged = {
            btnPattern.isActive = sequencer.isPlaying() || sequencer.isRecording()
            btnPlay.isActive = sequencer.isPlaying()
            btnRec.isActive = sequencer.isRecording() || isRecordingStandby || micRecorder.isRecording()
            if (!sequencer.isPlaying() && !sequencer.isRecording() && !micRecorder.isRecording()) {
                displayView.setBank(currentBank)
            }
        }

        // Mic recording amplitude levels
        micRecorder.onLevelUpdate = { level ->
            displayView.setLevel(level, level)
        }

        // Mic recording finished
        micRecorder.onRecordingComplete = { filePath ->
            handleRecordingSaved(filePath)
        }
    }

    // =========================================================================
    // PAD HANDLING
    // =========================================================================

    private fun handlePadPress(padIndex: Int) {
        // 1. In FX selection mode, pads 1-12 select built-in effect types
        if (isFxMode) {
            val fxTypes = EffectType.values()
            if (padIndex in fxTypes.indices) {
                val selectedEffect = fxTypes[padIndex]
                audioEngine.setActiveEffect(selectedEffect)
                updateKnobsForFx()
                displayView.setEffectInfo(selectedEffect.displayName, audioEngine.getEffectParams())

                // Flash pad to indicate selection
                pads[padIndex].isActive = true
                mainHandler.postDelayed({ pads[padIndex].isActive = false }, 180)
            }
            return
        }

        // 2. In Delete Mode, pad tap marks pad for confirmation
        if (isDeleteMode) {
            selectedDeletePad = padIndex
            displayView.showMessage("DEL PAD ${padIndex + 1}? TAP DEL")
            pads[padIndex].isActive = true
            return
        }

        // 3. In Copy Mode, first tap is source, second tap is destination
        if (isCopyMode) {
            if (copySourcePad == -1) {
                copySourcePad = padIndex
                displayView.showMessage("SRC: PAD ${padIndex + 1} -> DST?")
                pads[padIndex].isActive = true
            } else {
                executePadCopy(copySourcePad, padIndex)
            }
            return
        }

        // 4. In Pad Link Mode, select pair
        if (isPadLinkMode) {
            if (padLinkFirstPad == -1) {
                padLinkFirstPad = padIndex
                displayView.showMessage("LINK: P${padIndex + 1} + ?")
                pads[padIndex].isActive = true
            } else {
                executePadLink(padLinkFirstPad, padIndex)
            }
            return
        }

        // 5. In Mic Record Standby, pad press starts microphone recording to this pad
        if (isRecordingStandby) {
            stopRecBlink()
            isRecordingStandby = false
            startMicRecording(padIndex)
            return
        }

        // 6. Normal Pad Trigger Mode
        selectedEditingPad = padIndex
        val state = audioEngine.getPadState(currentBank, padIndex)
        val sample = state?.sample

        // Start Roll or single trigger
        if (isRollMode || btnRoll.isActive) {
            startRoll(padIndex)
        } else {
            audioEngine.triggerPad(currentBank, padIndex)
            if (sequencer.isRecording()) {
                sequencer.recordEvent(currentBank, padIndex)
            }
        }

        // Update display with pad info
        if (sample != null) {
            displayView.setPadInfo(padIndex, sample.name)
            if (!isStartEndMode) {
                syncKnobsToSample(sample)
            }
        } else {
            displayView.setPadInfo(padIndex, "EMPTY")
        }
    }

    private fun handlePadRelease(padIndex: Int) {
        if (rollingPadIndex == padIndex) {
            stopRoll()
        }

        // In GATE mode, release cuts sound unless HOLD is enabled
        if (!isHoldMode && !isFxMode && !isCopyMode && !isDeleteMode) {
            audioEngine.releasePad(currentBank, padIndex)
        }
    }

    // =========================================================================
    // KNOB INTERACTIONS
    // =========================================================================

    private fun onCtrlKnobChanged(knobIndex: Int, value: Float) {
        when {
            // Case 1: FX Parameter control
            isFxMode || audioEngine.getActiveEffect() != null -> {
                audioEngine.setEffectParam(knobIndex, value)
                val effect = audioEngine.getActiveEffect()
                if (effect != null) {
                    displayView.setEffectInfo(effect.displayName, audioEngine.getEffectParams())
                }
            }

            // Case 2: Start/End waveform trim editing
            isStartEndMode && selectedEditingPad in 0 until 12 -> {
                val sample = audioEngine.getPadState(currentBank, selectedEditingPad)?.sample ?: return
                when (knobIndex) {
                    0 -> {
                        // CTRL1 = Start Point
                        sample.startPoint = value.coerceIn(0f, sample.endPoint - 0.01f)
                    }
                    1 -> {
                        // CTRL2 = End Point
                        sample.endPoint = value.coerceIn(sample.startPoint + 0.01f, 1f)
                    }
                    2 -> {
                        // CTRL3 = Pitch
                        val pitch = 0.5f + value * 1.5f
                        sample.pitch = pitch
                        audioEngine.setPadPitch(currentBank, selectedEditingPad, pitch)
                    }
                }
                sampleStorage.savePadConfig(currentBank, selectedEditingPad, sample)
                displayView.setText(
                    "PAD ${selectedEditingPad + 1} S/E",
                    "S:%02d E:%02d".format((sample.startPoint * 100).toInt(), (sample.endPoint * 100).toInt())
                )
            }

            // Case 3: Default Pad parameters (Volume, Pitch, PlayMode)
            selectedEditingPad in 0 until 12 -> {
                val sample = audioEngine.getPadState(currentBank, selectedEditingPad)?.sample ?: return
                when (knobIndex) {
                    0 -> {
                        // CTRL1 = Pad Volume
                        sample.volume = value
                        audioEngine.setPadVolume(currentBank, selectedEditingPad, value)
                        displayView.setText("PAD ${selectedEditingPad + 1} VOL", "${(value * 100).toInt()}%")
                    }
                    1 -> {
                        // CTRL2 = Pitch [0.5x .. 2.0x]
                        val pitch = 0.5f + value * 1.5f
                        sample.pitch = pitch
                        audioEngine.setPadPitch(currentBank, selectedEditingPad, pitch)
                        displayView.setText("PAD ${selectedEditingPad + 1} PTCH", "%.2fx".format(pitch))
                    }
                    2 -> {
                        // CTRL3 = PlayMode
                        val mode = when {
                            value < 0.25f -> PlayMode.TRIGGER
                            value < 0.50f -> PlayMode.LOOP
                            value < 0.75f -> PlayMode.GATE
                            else -> PlayMode.ONE_SHOT
                        }
                        sample.playMode = mode
                        audioEngine.setPadPlayMode(currentBank, selectedEditingPad, mode)
                        displayView.setText("PAD ${selectedEditingPad + 1} MODE", mode.displayName)
                    }
                }
                sampleStorage.savePadConfig(currentBank, selectedEditingPad, sample)
            }
        }
    }

    private fun updateKnobsForFx() {
        knobCtrl1.label = "CTRL 1"
        knobCtrl2.label = "CTRL 2"
        knobCtrl3.label = "CTRL 3"
        val params = audioEngine.getEffectParams()
        knobCtrl1.value = params[0]
        knobCtrl2.value = params[1]
        knobCtrl3.value = params[2]
    }

    private fun updateKnobsForStartEnd() {
        knobCtrl1.label = "START"
        knobCtrl2.label = "END"
        knobCtrl3.label = "PITCH"
        if (selectedEditingPad in 0 until 12) {
            val sample = audioEngine.getPadState(currentBank, selectedEditingPad)?.sample
            if (sample != null) {
                knobCtrl1.value = sample.startPoint
                knobCtrl2.value = sample.endPoint
                knobCtrl3.value = ((sample.pitch - 0.5f) / 1.5f).coerceIn(0f, 1f)
            }
        }
    }

    private fun updateKnobsForDefault() {
        knobCtrl1.label = "PAD VOL"
        knobCtrl2.label = "PITCH"
        knobCtrl3.label = "MODE"
    }

    private fun syncKnobsToSample(sample: Sample) {
        knobCtrl1.value = sample.volume
        knobCtrl2.value = ((sample.pitch - 0.5f) / 1.5f).coerceIn(0f, 1f)
        knobCtrl3.value = when (sample.playMode) {
            PlayMode.TRIGGER -> 0.12f
            PlayMode.LOOP -> 0.37f
            PlayMode.GATE -> 0.62f
            PlayMode.ONE_SHOT -> 0.88f
        }
    }

    // =========================================================================
    // MODES & ACTION HANDLERS
    // =========================================================================

    private fun switchToBank(bank: BankId) {
        currentBank = bank
        refreshBankButtons()

        // Update all pads for new bank
        for (i in pads.indices) {
            updatePadView(i)
        }
        displayView.setBank(bank)
    }

    private fun refreshBankButtons() {
        btnBankA.isActive = (currentBank == BankId.A)
        btnBankB.isActive = (currentBank == BankId.B)
        btnBankC.isActive = (currentBank == BankId.C)
    }

    private fun handleRecButtonClick() {
        when {
            // If mic is currently recording, stop it and finalize
            micRecorder.isRecording() -> {
                micRecorder.stopRecording()
                btnRec.isActive = false
                displayView.setRecording(false)
            }

            // If in pattern mode, toggle pattern recording
            btnPattern.isActive -> {
                if (sequencer.isRecording()) {
                    sequencer.stopRecording()
                    btnRec.isActive = false
                } else {
                    sequencer.startRecording()
                    btnRec.isActive = true
                }
            }

            // If already in standby, cancel
            isRecordingStandby -> {
                stopRecBlink()
                isRecordingStandby = false
                btnRec.isActive = false
                displayView.showMessage("REC CANCEL")
            }

            // Enter record standby
            else -> {
                exitOtherModes()
                isRecordingStandby = true
                startRecBlink()
                displayView.showMessage("SELECT PAD REC")
            }
        }
    }

    private fun handlePatternButtonClick() {
        if (isRecordingStandby) {
            // REC then PATTERN pressed -> Start pattern recording!
            stopRecBlink()
            isRecordingStandby = false
            sequencer.startRecording(bpm = 120)
            btnPattern.isActive = true
            btnRec.isActive = true
            displayView.setText("PTN REC", "BPM: ${sequencer.getBpm()}")
        } else if (sequencer.isPlaying()) {
            sequencer.stopPlayback()
            btnPattern.isActive = false
            btnPlay.isActive = false
        } else if (sequencer.hasPattern()) {
            sequencer.startPlayback()
            btnPattern.isActive = true
            btnPlay.isActive = true
            displayView.setText("PTN PLAY", "BPM: ${sequencer.getBpm()}")
        } else {
            displayView.showMessage("NO PATTERN")
        }
    }

    private fun handleFxToggle() {
        isFxMode = !isFxMode
        btnFx.isActive = isFxMode

        if (isFxMode) {
            exitOtherModes(exceptFx = true)
            if (audioEngine.getActiveEffect() == null) {
                audioEngine.setActiveEffect(EffectType.FILTER)
            }
            updateKnobsForFx()
            val fx = audioEngine.getActiveEffect() ?: EffectType.FILTER
            displayView.setEffectInfo(fx.displayName, audioEngine.getEffectParams())
        } else {
            audioEngine.setActiveEffect(null)
            updateKnobsForDefault()
            displayView.showMessage("FX BYPASS")
        }
    }

    private fun handleCopyToggle() {
        if (isCopyMode) {
            isCopyMode = false
            copySourcePad = -1
            btnCopy.isActive = false
            refreshCurrentBankPads()
            displayView.showMessage("COPY CANCEL")
        } else {
            exitOtherModes(exceptCopy = true)
            isCopyMode = true
            copySourcePad = -1
            btnCopy.isActive = true
            displayView.showMessage("SELECT SRC PAD")
        }
    }

    private fun executePadCopy(src: Int, dst: Int) {
        if (src == dst) {
            displayView.showMessage("SAME PAD")
        } else {
            val srcSample = audioEngine.getPadState(currentBank, src)?.sample
            if (srcSample != null) {
                val newSample = srcSample.copy(id = UUID.randomUUID().toString())
                audioEngine.loadSample(currentBank, dst, newSample.filePath)
                audioEngine.setPadVolume(currentBank, dst, newSample.volume)
                audioEngine.setPadPitch(currentBank, dst, newSample.pitch)
                audioEngine.setPadPlayMode(currentBank, dst, newSample.playMode)

                val targetState = audioEngine.getPadState(currentBank, dst)
                targetState?.sample?.apply {
                    startPoint = newSample.startPoint
                    endPoint = newSample.endPoint
                    isReversed = newSample.isReversed
                }
                sampleStorage.savePadConfig(currentBank, dst, newSample)
                updatePadView(dst)
                displayView.showMessage("COPIED P${src + 1}->P${dst + 1}")
            } else {
                displayView.showMessage("SRC EMPTY")
            }
        }

        isCopyMode = false
        copySourcePad = -1
        btnCopy.isActive = false
        refreshCurrentBankPads()
    }

    private fun handleDeleteToggle() {
        if (isDeleteMode) {
            if (selectedDeletePad != -1) {
                // Confirm Delete
                val padToDelete = selectedDeletePad
                audioEngine.stopPad(currentBank, padToDelete)
                sampleStorage.deletePadConfig(currentBank, padToDelete)

                val state = audioEngine.getPadState(currentBank, padToDelete)
                state?.sample = null

                updatePadView(padToDelete)
                displayView.showMessage("PAD ${padToDelete + 1} DELETED")

                selectedDeletePad = -1
                isDeleteMode = false
                btnDel.isActive = false
            } else {
                isDeleteMode = false
                btnDel.isActive = false
                refreshCurrentBankPads()
                displayView.showMessage("DEL CANCEL")
            }
        } else {
            exitOtherModes(exceptDelete = true)
            isDeleteMode = true
            selectedDeletePad = -1
            btnDel.isActive = true
            displayView.showMessage("TAP PAD TO DEL")
        }
    }

    private fun handleStartEndToggle() {
        isStartEndMode = !isStartEndMode
        btnStartEnd.isActive = isStartEndMode

        if (isStartEndMode) {
            exitOtherModes(exceptStartEnd = true)
            updateKnobsForStartEnd()
            displayView.showMessage("START/END MODE")
        } else {
            updateKnobsForDefault()
            displayView.showMessage("START/END OFF")
        }
    }

    private fun handlePadLinkToggle() {
        if (isPadLinkMode) {
            isPadLinkMode = false
            padLinkFirstPad = -1
            btnPadLink.isActive = false
            refreshCurrentBankPads()
            displayView.showMessage("LINK CANCEL")
        } else {
            exitOtherModes(exceptPadLink = true)
            isPadLinkMode = true
            padLinkFirstPad = -1
            btnPadLink.isActive = true
            displayView.showMessage("PRESS 1ST PAD")
        }
    }

    private fun executePadLink(pad1: Int, pad2: Int) {
        if (pad1 == pad2) {
            displayView.showMessage("SAME PAD")
        } else {
            val state1 = audioEngine.getPadState(currentBank, pad1)
            if (state1?.isLinked == true && state1.linkedPadIndex == pad2) {
                audioEngine.unlinkPad(currentBank, pad1)
                displayView.showMessage("UNLINKED")
            } else {
                audioEngine.linkPads(currentBank, pad1, pad2)
                displayView.showMessage("LINKED P${pad1 + 1}+P${pad2 + 1}")
            }
            updatePadView(pad1)
            updatePadView(pad2)
        }

        isPadLinkMode = false
        padLinkFirstPad = -1
        btnPadLink.isActive = false
    }

    private fun handleStopAll() {
        audioEngine.stopAll()
        if (sequencer.isPlaying()) sequencer.stopPlayback()
        if (sequencer.isRecording()) sequencer.stopRecording()
        if (micRecorder.isRecording()) micRecorder.stopRecording()

        stopRoll()
        exitOtherModes()
        btnPlay.isActive = false
        btnRec.isActive = false
        btnPattern.isActive = false

        displayView.setText("STOPPED", "READY")
    }

    private fun exitOtherModes(
        exceptCopy: Boolean = false,
        exceptDelete: Boolean = false,
        exceptPadLink: Boolean = false,
        exceptStartEnd: Boolean = false,
        exceptFx: Boolean = false
    ) {
        if (!exceptCopy && isCopyMode) {
            isCopyMode = false
            copySourcePad = -1
            btnCopy.isActive = false
        }
        if (!exceptDelete && isDeleteMode) {
            isDeleteMode = false
            selectedDeletePad = -1
            btnDel.isActive = false
        }
        if (!exceptPadLink && isPadLinkMode) {
            isPadLinkMode = false
            padLinkFirstPad = -1
            btnPadLink.isActive = false
        }
        if (!exceptStartEnd && isStartEndMode) {
            isStartEndMode = false
            btnStartEnd.isActive = false
        }
        if (!exceptFx && isFxMode) {
            isFxMode = false
            btnFx.isActive = false
        }
        if (isRecordingStandby) {
            isRecordingStandby = false
            stopRecBlink()
            btnRec.isActive = false
        }
        refreshCurrentBankPads()
    }

    // =========================================================================
    // AUDIO IMPORT & MIC RECORDING
    // =========================================================================

    private fun importAudioFile(uri: Uri) {
        displayView.showMessage("IMPORTING...")

        executor.execute {
            val importedPath = fileImporter.importFile(uri)
            if (importedPath == null) {
                mainHandler.post {
                    displayView.showMessage("IMPORT FAILED")
                }
                return@execute
            }

            // Find next empty pad in current bank or overwrite selected pad
            var targetPad = -1
            for (i in 0 until 12) {
                if (audioEngine.getPadState(currentBank, i)?.sample == null) {
                    targetPad = i
                    break
                }
            }
            if (targetPad == -1) {
                targetPad = if (selectedEditingPad in 0 until 12) selectedEditingPad else 0
            }

            val loadSuccess = audioEngine.loadSample(currentBank, targetPad, importedPath)
            if (loadSuccess) {
                val padState = audioEngine.getPadState(currentBank, targetPad)
                val sample = padState?.sample
                if (sample != null) {
                    sampleStorage.savePadConfig(currentBank, targetPad, sample)
                }
                mainHandler.post {
                    updatePadView(targetPad)
                    displayView.showMessage("LOADED PAD ${targetPad + 1}")
                }
            } else {
                mainHandler.post {
                    displayView.showMessage("DECODE FAILED")
                }
            }
        }
    }

    private fun startMicRecording(padIndex: Int) {
        recordingTargetPad = padIndex
        val samplesDir = sampleStorage.getSamplesDir()
        val targetFile = File(
            samplesDir,
            "rec_${currentBank.name}_pad${padIndex + 1}_${System.currentTimeMillis()}.wav"
        )

        btnRec.isActive = true
        displayView.setRecording(true)
        displayView.setText("RECORDING", "PAD ${padIndex + 1}")

        micRecorder.startRecording(targetFile.absolutePath)
    }

    private fun handleRecordingSaved(filePath: String) {
        val targetPad = recordingTargetPad
        displayView.setRecording(false)
        btnRec.isActive = false

        if (targetPad in 0 until 12) {
            executor.execute {
                val success = audioEngine.loadSample(currentBank, targetPad, filePath)
                if (success) {
                    val sample = audioEngine.getPadState(currentBank, targetPad)?.sample
                    if (sample != null) {
                        sampleStorage.savePadConfig(currentBank, targetPad, sample)
                    }
                    mainHandler.post {
                        updatePadView(targetPad)
                        displayView.showMessage("REC SAVED P${targetPad + 1}")
                    }
                } else {
                    mainHandler.post {
                        displayView.showMessage("REC SAVE ERR")
                    }
                }
            }
        }
        recordingTargetPad = -1
    }

    // =========================================================================
    // SAMPLE LOADING & BANK REFRESH
    // =========================================================================

    private fun loadBankSamples() {
        executor.execute {
            for (bank in BankId.values()) {
                for (padIndex in 0 until 12) {
                    val savedConfig = sampleStorage.loadPadConfig(bank, padIndex)
                    if (savedConfig != null && File(savedConfig.filePath).exists()) {
                        audioEngine.loadSample(bank, padIndex, savedConfig.filePath)
                        audioEngine.setPadVolume(bank, padIndex, savedConfig.volume)
                        audioEngine.setPadPitch(bank, padIndex, savedConfig.pitch)
                        audioEngine.setPadPlayMode(bank, padIndex, savedConfig.playMode)

                        val state = audioEngine.getPadState(bank, padIndex)
                        state?.sample?.apply {
                            startPoint = savedConfig.startPoint
                            endPoint = savedConfig.endPoint
                            isReversed = savedConfig.isReversed
                        }
                    } else if (bank == BankId.A) {
                        // Fallback to factory raw resource for Bank A
                        val resId = sampleStorage.getDefaultSampleMapping()[padIndex]
                        if (resId != null) {
                            audioEngine.loadSampleFromResource(bank, padIndex, resId)
                        }
                    }
                }
            }

            mainHandler.post {
                refreshBankButtons()
                for (i in pads.indices) {
                    updatePadView(i)
                }
                displayView.setBank(currentBank)
            }
        }
    }

    private fun updatePadView(padIndex: Int) {
        if (padIndex !in pads.indices) return
        val pad = pads[padIndex]
        val state = audioEngine.getPadState(currentBank, padIndex)
        val sample = state?.sample

        pad.isLoaded = (sample != null)
        pad.sampleName = sample?.name ?: ""
        pad.isActive = (state?.isPlaying == true)
    }

    private fun refreshCurrentBankPads() {
        for (i in pads.indices) {
            updatePadView(i)
        }
    }

    // =========================================================================
    // ROLL & TIMERS
    // =========================================================================

    private fun startRoll(padIndex: Int) {
        rollingPadIndex = padIndex
        rollRunnable?.let { mainHandler.removeCallbacks(it) }

        rollRunnable = object : Runnable {
            override fun run() {
                if (rollingPadIndex == padIndex && (isRollMode || btnRoll.isActive)) {
                    audioEngine.triggerPad(currentBank, padIndex)
                    if (sequencer.isRecording()) {
                        sequencer.recordEvent(currentBank, padIndex)
                    }

                    // Roll speed interval derived from knobCtrl1 (50ms to 260ms)
                    val intervalMs = (50 + (1.0f - knobCtrl1.value) * 210).toLong()
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
        }
        mainHandler.post(rollRunnable!!)
    }

    private fun stopRoll() {
        rollingPadIndex = -1
        rollRunnable?.let { mainHandler.removeCallbacks(it) }
        rollRunnable = null
    }

    private fun startRecBlink() {
        stopRecBlink()
        recBlinkRunnable = object : Runnable {
            override fun run() {
                if (isRecordingStandby) {
                    recBlinkState = !recBlinkState
                    btnRec.isActive = recBlinkState
                    mainHandler.postDelayed(this, 320L)
                }
            }
        }
        mainHandler.post(recBlinkRunnable!!)
    }

    private fun stopRecBlink() {
        recBlinkRunnable?.let { mainHandler.removeCallbacks(it) }
        recBlinkRunnable = null
    }

    private fun startVuMeterMonitoring() {
        if (vuRunnable != null) return
        vuRunnable = object : Runnable {
            override fun run() {
                var anyPlaying = false
                for (i in 0 until 12) {
                    if (audioEngine.isPadPlaying(currentBank, i)) {
                        anyPlaying = true
                        break
                    }
                }

                if (micRecorder.isRecording()) {
                    // Handled directly by micRecorder.onLevelUpdate
                } else if (anyPlaying) {
                    val base = 0.55f + (Math.random() * 0.40f).toFloat()
                    val l = (base * (0.85f + (Math.random() * 0.25f).toFloat())).coerceIn(0f, 1f)
                    val r = (base * (0.85f + (Math.random() * 0.25f).toFloat())).coerceIn(0f, 1f)
                    displayView.setLevel(l, r)
                    mainHandler.postDelayed(this, 50L)
                } else {
                    if (displayView.vuLeft > 0.05f || displayView.vuRight > 0.05f) {
                        displayView.setLevel(displayView.vuLeft * 0.65f, displayView.vuRight * 0.65f)
                        mainHandler.postDelayed(this, 50L)
                    } else {
                        displayView.setLevel(0f, 0f)
                        vuRunnable = null
                    }
                }
            }
        }
        mainHandler.post(vuRunnable!!)
    }

    // =========================================================================
    // PERMISSIONS & LIFECYCLE
    // =========================================================================

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onPause() {
        super.onPause()
        audioEngine.stopAll()
        if (sequencer.isPlaying()) sequencer.stopPlayback()
        if (sequencer.isRecording()) sequencer.stopRecording()
        if (micRecorder.isRecording()) micRecorder.stopRecording()
        stopRoll()
        stopRecBlink()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRoll()
        stopRecBlink()
        vuRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(patternLongPressRunnable)
        audioEngine.release()
        executor.shutdown()
    }
}
