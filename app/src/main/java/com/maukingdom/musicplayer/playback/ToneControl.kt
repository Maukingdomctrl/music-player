package com.maukingdom.musicplayer.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bass and treble maths for the system equaliser. No Android types, so it
 * is unit-tested on the JVM.
 *
 * Levels are in millibels (1 dB = 100 mB), as android.media.audiofx uses.
 */
object ToneControl {

    const val MIN_DB = -6
    const val MAX_DB = 6

    /** Number of 1 dB steps between [MIN_DB] and [MAX_DB]. */
    const val STEPS = MAX_DB - MIN_DB

    private const val BASS_FULL_MAX_HZ = 250
    private const val BASS_HALF_MAX_HZ = 500
    private const val TREBLE_HALF_MIN_HZ = 2_000
    private const val TREBLE_FULL_MIN_HZ = 4_000

    /** Within this many dB of 0 a knob drag snaps to 0 (the centre detent). */
    private const val DETENT_DB = 0.75f

    fun clampDb(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)

    /** True when the equaliser needs to be on at all. */
    fun isActive(bassDb: Int, trebleDb: Int): Boolean = bassDb != 0 || trebleDb != 0

    /**
     * Per-band levels in mB for bands with centre frequencies [centreHz].
     *
     * Bass applies fully to bands at or below 250 Hz and half to bands above
     * 250 up to 500 Hz. Treble applies fully at or above 4 kHz and half from
     * 2 kHz up to 4 kHz. Other bands stay at 0. Every level is clamped to
     * [minMb]..[maxMb].
     */
    fun bandLevels(
        centreHz: List<Int>,
        minMb: Int,
        maxMb: Int,
        bassDb: Int,
        trebleDb: Int
    ): List<Int> {
        val bassMb = clampDb(bassDb) * 100
        val trebleMb = clampDb(trebleDb) * 100
        val low = minOf(minMb, maxMb)
        val high = maxOf(minMb, maxMb)

        return centreHz.map { hz ->
            val bass = when {
                hz <= BASS_FULL_MAX_HZ -> bassMb
                hz <= BASS_HALF_MAX_HZ -> bassMb / 2
                else -> 0
            }

            val treble = when {
                hz >= TREBLE_FULL_MIN_HZ -> trebleMb
                hz >= TREBLE_HALF_MIN_HZ -> trebleMb / 2
                else -> 0
            }

            (bass + treble).coerceIn(low, high)
        }
    }

    /** Knob position 0..1 for [db]; 0 dB is the top centre (0.5). */
    fun fractionForDb(db: Int): Float = (clampDb(db) - MIN_DB).toFloat() / STEPS

    /**
     * dB for a knob position, rounded to whole dB, with a wider catch zone
     * around 0 so the centre is easy to find.
     */
    fun dbForFraction(fraction: Float): Int {
        val raw = fraction.coerceIn(0f, 1f) * STEPS + MIN_DB
        if (abs(raw) < DETENT_DB) return 0
        return clampDb(raw.roundToInt())
    }

    /** "+3", "0" or "−2" (with a real minus sign). */
    fun formatDb(db: Int): String = when {
        db > 0 -> "+$db"
        db < 0 -> "−${-db}"
        else -> "0"
    }
}
