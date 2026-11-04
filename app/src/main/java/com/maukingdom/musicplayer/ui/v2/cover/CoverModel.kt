package com.maukingdom.musicplayer.ui.v2.cover

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/*
 * Living covers: everything that can be decided without Android or Compose
 * runtime — era style, display mode, which songs to show, the seeded
 * generated artwork, pause rules and the timing curves. Unit tested.
 */

// ---------------------------------------------------------------- era style

/** The look laid over a playlist cover, chosen from its name. */
enum class EraStyle {
    /** "old", "classic": sepia, film grain, vignette. */
    SEPIA,

    /** "90": magenta/cyan tint, scanlines, neon edge, rare 1-frame shift. */
    VHS,

    /** "2000": glossy diagonal light sweep. */
    CD,

    /** "new": slow soft colour blobs, photos untinted. */
    MODERN,

    /** "marathi": saffron/green warm tint. */
    MARATHI,

    /** "ganpati", "ganesh", "bappa": marigold tint and drifting petals. */
    GANPATI,

    /** Anything else: a subtle accent tint. */
    NEUTRAL
}

/**
 * The era style for [playlistName], case-insensitive. When several match,
 * the most specific wins: festival, then language, then decade ("90" before
 * "2000"), then "old"/"classic", then "new". So "Old Hindi 90s" is VHS and
 * "Ganpati Marathi" is GANPATI.
 *
 * "old", "classic" and "new" must start a word ("Oldies", "Classics",
 * "Newest" match; "Bold", "Renewal" do not). "90" and "2000" may appear
 * anywhere in a word ("1990s", "2000s").
 */
fun eraStyleFor(playlistName: String): EraStyle {
    val words = playlistName.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }

    fun anyContains(part: String) = words.any { it.contains(part) }
    fun anyStarts(prefix: String) = words.any { it.startsWith(prefix) }

    return when {
        anyContains("ganpati") || anyContains("ganesh") || anyContains("bappa") -> EraStyle.GANPATI
        anyContains("marathi") -> EraStyle.MARATHI
        anyContains("90") -> EraStyle.VHS
        anyContains("2000") -> EraStyle.CD
        anyStarts("old") || anyStarts("classic") -> EraStyle.SEPIA
        anyStarts("new") -> EraStyle.MODERN
        else -> EraStyle.NEUTRAL
    }
}

// -------------------------------------------------------------------- mode

/** How a cover shows its pictures. */
enum class CoverMode {
    /** No pictures: generated era artwork. */
    GENERATED,

    /** One picture: slow Ken Burns. */
    KEN_BURNS,

    /** 2–4 pictures, one at a time, crossfading every [HOLD_SECONDS]. */
    CROSSFADE,

    /** Four pictures in a slowly drifting 2×2 grid (mid-size covers). */
    MOSAIC
}

/** Mosaic only on covers this big and smaller than [MOSAIC_MAX_DP]. */
const val MOSAIC_MIN_DP = 140f
const val MOSAIC_MAX_DP = 200f

/**
 * The mode for [pictureCount] loaded pictures on a [sizeDp] cover. A 2×2
 * mosaic needs four pictures and suits the 140 dp cards; the large 220 dp
 * header crossfades full pictures instead.
 */
fun coverModeFor(pictureCount: Int, sizeDp: Float): CoverMode = when {
    pictureCount <= 0 -> CoverMode.GENERATED
    pictureCount == 1 -> CoverMode.KEN_BURNS
    pictureCount >= 4 && sizeDp >= MOSAIC_MIN_DP && sizeDp < MOSAIC_MAX_DP -> CoverMode.MOSAIC
    else -> CoverMode.CROSSFADE
}

// -------------------------------------------------------------- candidates

const val MAX_COVER_PICTURES = 4
const val COVER_CANDIDATES_PER_PLAYLIST = 8

/** One playlist entry (in playlist order) with its song's album. */
data class CoverCandidateRef(val playlistId: Long, val songId: String, val album: String)

/**
 * Songs to try for each playlist's cover, in the order to try them: one
 * song per album first (so a mosaic is not four copies of one album cover),
 * then the rest, at most [perPlaylist]. Songs without artwork are skipped
 * later, when loading fails.
 */
fun coverCandidates(refs: List<CoverCandidateRef>, perPlaylist: Int): Map<Long, List<String>> =
    refs.groupBy { it.playlistId }.mapValues { (_, entries) ->
        val firstPerAlbum = entries.distinctBy { it.album.trim().lowercase(Locale.ROOT) }
        (firstPerAlbum + entries).map { it.songId }.distinct().take(perPlaylist)
    }

// ------------------------------------------------------------ pause rules

/** Animated covers allowed to move at once on a screen; the rest stay still. */
const val MAX_ANIMATED_COVERS = 3

/**
 * Whether a cover moves this frame. It shows its static frame when it was
 * not asked to animate, the system's "Remove animations" is on, the app is
 * not resumed, something covers it (Settings, Now Playing, an open
 * playlist), or it did not get one of the screen's animation slots.
 */
fun shouldAnimate(
    requested: Boolean,
    animationsOff: Boolean,
    appResumed: Boolean,
    covered: Boolean,
    hasSlot: Boolean
): Boolean = requested && !animationsOff && appResumed && !covered && hasSlot

/** Covers that get a slot: the first [max] that asked, in the order they asked. */
fun <T> grantedSlots(waiting: List<T>, max: Int = MAX_ANIMATED_COVERS): List<T> = waiting.take(max)

// ------------------------------------------------------------ timing curves

/** Seconds a picture stays before the next fades in, and the fade itself. */
const val HOLD_SECONDS = 5f
const val FADE_SECONDS = 1.2f

/** Ken Burns: scale 1.0 → 1.08 and a small pan, there and back over one period. */
@Immutable
data class KenBurns(val scale: Float, val panX: Float, val panY: Float)

/** Period of one Ken Burns leg (there or back), 12–16 s depending on the seed. */
fun kenBurnsPeriod(seed: Long): Float = 12f + (Math.floorMod(seed * 7919L, 5L)).toFloat()

/**
 * The Ken Burns frame at [seconds]. Pan is a fraction of the cover size
 * (at most ±3 %), its direction picked by [seed]. Eased, so it never jerks
 * at the turning points.
 */
fun kenBurnsAt(seconds: Float, seed: Long): KenBurns {
    val period = kenBurnsPeriod(seed)
    val phase = (seconds / period) % 2f
    val leg = if (phase < 1f) phase else 2f - phase // 0→1→0
    val eased = (1f - kotlin.math.cos(leg * PI.toFloat())) / 2f
    val angle = (Math.floorMod(seed, 8L)) * (PI.toFloat() / 4f)
    return KenBurns(
        scale = 1f + 0.08f * eased,
        panX = 0.03f * eased * kotlin.math.cos(angle),
        panY = 0.03f * eased * sin(angle)
    )
}

/** Which picture shows, which comes next, and how far the fade to it is (0..1). */
@Immutable
data class CrossfadeFrame(val current: Int, val next: Int, val fade: Float)

fun crossfadeAt(seconds: Float, count: Int): CrossfadeFrame {
    if (count <= 1) return CrossfadeFrame(0, 0, 0f)
    val step = HOLD_SECONDS + FADE_SECONDS
    val cycle = (seconds.coerceAtLeast(0f) / step).toInt()
    val within = seconds.coerceAtLeast(0f) - cycle * step
    val current = cycle % count
    val fade = ((within - HOLD_SECONDS) / FADE_SECONDS).coerceIn(0f, 1f)
    return CrossfadeFrame(current, (current + 1) % count, fade)
}

/** VHS: a one-frame horizontal shift, at most once per [VHS_GLITCH_EVERY] s. */
const val VHS_GLITCH_EVERY = 6.5f
private const val VHS_GLITCH_LENGTH = 1f / 60f // shorter than two frames at 90 Hz

/** Horizontal shift as a fraction of the width at [seconds]: 0 almost always. */
fun vhsShiftAt(seconds: Float, seed: Long): Float {
    val offset = (Math.floorMod(seed, 13L)) * 0.37f
    val within = (seconds + offset) % VHS_GLITCH_EVERY
    return if (within < VHS_GLITCH_LENGTH) 0.035f else 0f
}

/** CD: one light sweep every [CD_SWEEP_EVERY] s, lasting [CD_SWEEP_LENGTH] s. */
const val CD_SWEEP_EVERY = 7f
const val CD_SWEEP_LENGTH = 1.4f

/** Sweep position -0.5..1.5 across the cover while sweeping, else null (no light). */
fun cdSweepAt(seconds: Float, seed: Long): Float? {
    val within = (seconds + (Math.floorMod(seed, 7L)).toFloat()) % CD_SWEEP_EVERY
    if (within >= CD_SWEEP_LENGTH) return null
    val t = within / CD_SWEEP_LENGTH
    val eased = t * t * (3f - 2f * t)
    return -0.5f + 2f * eased
}

/** Film grain jumps to a new offset this often, like projected frames. */
const val GRAIN_FRAME_SECONDS = 1f / 12f

/** Grain tile offset (0..1 of the tile) for [seconds]; steps 12 times a second. */
fun grainOffsetAt(seconds: Float): Pair<Float, Float> {
    val frame = (seconds / GRAIN_FRAME_SECONDS).toLong()
    val x = (Math.floorMod(frame * 37L, 101L)) / 101f
    val y = (Math.floorMod(frame * 59L, 97L)) / 97f
    return x to y
}

// ------------------------------------------------------- generated artwork

/*
 * "Pressed Light": every playlist without pictures gets a record of its own.
 * Its grooves are concentric rings whose spacing and weight come from the
 * playlist id, so each pressing is unique yet always the same; a label disc
 * sits off-centre where the light catches it, and two soft bands of colour
 * cross the sleeve like stage light. The era decides the palette, nothing
 * else: the structure stays the playlist's own.
 */

/** A ring of the record, in cover fractions (centre and radius 0..1). */
@Immutable
data class GeneratedRing(val radius: Float, val width: Float, val tone: Int, val alpha: Float)

/** A generated cover: record centre, rings, label, light bands. Fractions of the cover size. */
@Immutable
data class GeneratedArt(
    val centerX: Float,
    val centerY: Float,
    val rings: List<GeneratedRing>,
    val labelRadius: Float,
    val labelTone: Int,
    val bandAngleDegrees: Float,
    val bandOffsets: List<Float>,
    val rotationSpeedDegrees: Float
)

/** Number of palette tones a [GeneratedArt] refers to (indices 0 until this). */
const val GENERATED_TONES = 4

/** The generated cover for [seed] (the playlist id). Same seed, same art. */
fun generateArt(seed: Long): GeneratedArt {
    // Golden-ratio mix, so neighbouring ids give unrelated pressings.
    val random = java.util.Random(seed * -7046029254386353131L xor 0x5DEECE66DL)
    fun between(min: Float, max: Float) = min + random.nextFloat() * (max - min)

    val rings = buildList {
        var radius = between(0.22f, 0.28f)
        while (radius < 0.95f) {
            val width = between(0.004f, 0.018f)
            add(
                GeneratedRing(
                    radius = radius,
                    width = width,
                    tone = random.nextInt(GENERATED_TONES),
                    alpha = between(0.18f, 0.55f)
                )
            )
            // Grooves bunch and spread like real tracks on a side.
            radius += width + between(0.012f, if (random.nextInt(5) == 0) 0.07f else 0.03f)
        }
    }

    return GeneratedArt(
        centerX = between(0.38f, 0.62f),
        centerY = between(0.42f, 0.66f),
        rings = rings,
        labelRadius = between(0.12f, 0.17f),
        labelTone = random.nextInt(GENERATED_TONES),
        bandAngleDegrees = between(20f, 70f),
        bandOffsets = listOf(between(0.15f, 0.45f), between(0.55f, 0.85f)),
        rotationSpeedDegrees = between(4f, 9f) * if (random.nextBoolean()) 1f else -1f
    )
}

// ------------------------------------------------------------------ petals

/** A falling petal at a moment, in cover fractions. */
@Immutable
data class Petal(val x: Float, val y: Float, val rotationDegrees: Float, val size: Float, val tone: Int)

/** 6–10 petals for [seed], drifting down slowly (about 11 s to cross) and swaying. */
fun petalsAt(seconds: Float, seed: Long): List<Petal> {
    val random = java.util.Random(seed xor 0x2545F4914F6CDD1DL)
    val count = 6 + random.nextInt(5)
    return List(count) {
        val startX = random.nextFloat()
        val startY = random.nextFloat()
        val speed = 0.07f + random.nextFloat() * 0.04f
        val sway = 0.02f + random.nextFloat() * 0.03f
        val spin = 20f + random.nextFloat() * 50f
        val size = 0.035f + random.nextFloat() * 0.025f
        val tone = random.nextInt(3)
        val y = (startY + seconds * speed) % 1.1f - 0.05f
        Petal(
            x = startX + sway * sin(seconds * 0.9f + startX * 6f),
            y = y,
            rotationDegrees = (startX * 360f + seconds * spin) % 360f,
            size = size,
            tone = tone
        )
    }
}

/** Modern blobs: centre of blob [index] at [seconds], slow Lissajous paths. */
fun blobCenterAt(seconds: Float, seed: Long, index: Int): Pair<Float, Float> {
    val phase = (Math.floorMod(seed + index * 31L, 100L)) / 100f * 2f * PI.toFloat()
    val speed = 0.11f + index * 0.035f
    val x = 0.5f + 0.32f * sin(seconds * speed + phase)
    val y = 0.5f + 0.30f * sin(seconds * speed * 1.37f + phase * 0.7f)
    return x to y
}
