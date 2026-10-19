package com.instruments.sp404sx.model

/**
 * Enumeration of the 12 built-in DSP effects inspired by the SP-404SX.
 */
enum class EffectType(val displayName: String, val fxNumber: Int) {
    FILTER("FILTER", 1),
    DELAY("DELAY", 2),
    REVERB("REVERB", 3),
    VINYL_SIM("VINYL SIM", 4),
    COMPRESSOR("COMP", 5),
    RING_MOD("RING MOD", 6),
    FLANGER("FLANGER", 7),
    PHASER("PHASER", 8),
    ISOLATOR("ISOLATOR", 9),
    LOFI("LO-FI", 10),
    DJFX_LOOPER("DJFX LOOP", 11),
    TAPE_ECHO("TAPE ECHO", 12)
}
