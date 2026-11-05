package com.maukingdom.musicplayer.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An equaliser preset as gains per frequency region, in whole dB. Regions
 * are mapped onto whatever bands the phone has by each band's centre
 * frequency: low up to 250 Hz, low-mid up to 800 Hz, mid below 4 kHz, high
 * from 4 kHz.
 */
enum class EqPreset(
    val id: String,
    val label: String,
    val lowDb: Int,
    val lowMidDb: Int,
    val midDb: Int,
    val highDb: Int
) {
    FLAT("flat", "Flat", 0, 0, 0, 0),
    CAR("car", "Car", 4, 2, 0, 2),
    /** For old recordings: voices forward, rumble down. */
    VOCAL("vocal", "Vocal", -2, 1, 4, 1),
    BASS_BOOST("bass_boost", "Bass boost", 6, 3, 0, 0),
    EARPHONES("earphones", "Earphones", 2, 0, -1, 2);

    /** This preset's gain for a band centred at [hz]. */
    fun gainDb(hz: Int): Int = when {
        hz <= LOW_MAX_HZ -> lowDb
        hz <= LOW_MID_MAX_HZ -> lowMidDb
        hz < HIGH_MIN_HZ -> midDb
        else -> highDb
    }

    companion object {
        const val LOW_MAX_HZ = 250
        const val LOW_MID_MAX_HZ = 800
        const val HIGH_MIN_HZ = 4_000

        /** A saved id, or Flat for none / unknown. */
        fun fromId(id: String?): EqPreset = entries.firstOrNull { it.id == id } ?: FLAT
    }
}

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
    fun isActive(bassDb: Int, trebleDb: Int, preset: EqPreset = EqPreset.FLAT): Boolean =
        bassDb != 0 || trebleDb != 0 || preset != EqPreset.FLAT

    /**
     * Per-band levels in mB for bands with centre frequencies [centreHz].
     *
     * Bass applies fully to bands at or below 250 Hz and half to bands above
     * 250 up to 500 Hz. Treble applies fully at or above 4 kHz and half from
     * 2 kHz up to 4 kHz. Other bands stay at 0. The knobs apply on top of
     * [preset] (summed); every level is clamped to [minMb]..[maxMb].
     */
    fun bandLevels(
        centreHz: List<Int>,
        minMb: Int,
        maxMb: Int,
        bassDb: Int,
        trebleDb: Int,
        preset: EqPreset = EqPreset.FLAT
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

            (preset.gainDb(hz) * 100 + bass + treble).coerceIn(low, high)
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
