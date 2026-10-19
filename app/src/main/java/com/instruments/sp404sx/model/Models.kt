package com.instruments.sp404sx.model

import com.google.gson.annotations.SerializedName

/**
 * Playback mode for a pad's sample.
 * - TRIGGER: Starts playback from beginning on press.
 * - LOOP: Loops continuously until stopped or toggled.
 * - GATE: Plays while held down; stops on release.
 * - ONE_SHOT: Plays to completion and cannot be interrupted.
 */
enum class PlayMode(val displayName: String) {
    TRIGGER("TRIG"),   // Play once when pad pressed
    LOOP("LOOP"),      // Loop continuously until stopped
    GATE("GATE"),      // Play while pad is held down
    ONE_SHOT("1SHOT")  // Play to completion, cannot be interrupted
}

/**
 * Bank identifier for pad organization (Banks A, B, C).
 */
enum class BankId(val displayName: String) {
    A("A"), B("B"), C("C")
}

/**
 * Represents an audio sample loaded into a pad.
 * Stores sample parameters, trimming points, volume, and pitch.
 */
data class Sample(
    val id: String,
    var name: String,
    var filePath: String,
    var durationMs: Long = 0,
    var sampleRate: Int = 44100,
    var channels: Int = 2,
    var playMode: PlayMode = PlayMode.TRIGGER,
    var volume: Float = 1.0f,
    var pitch: Float = 1.0f,
    var startPoint: Float = 0f,
    var endPoint: Float = 1.0f,
    var isReversed: Boolean = false
)

/**
 * State of a single pad on the SP-404SX interface.
 */
data class PadState(
    val padIndex: Int,
    val bankId: BankId,
    var sample: Sample? = null,
    @Transient var isPlaying: Boolean = false,
    var isLinked: Boolean = false,
    var linkedPadIndex: Int = -1
)

/**
 * A sequencer event representing a pad trigger at a specific time offset.
 */
data class SequenceEvent(
    val timeMs: Long,
    val bankId: BankId,
    val padIndex: Int,
    val velocity: Float = 1.0f
)

/**
 * A recorded pattern consisting of timed sequence events.
 */
data class Pattern(
    var events: MutableList<SequenceEvent> = mutableListOf(),
    var lengthMs: Long = 0,
    var bpm: Int = 120,
    var quantize: Boolean = false
)
