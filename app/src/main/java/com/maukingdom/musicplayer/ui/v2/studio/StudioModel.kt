package com.maukingdom.musicplayer.ui.v2.studio

import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.v2.hslToRgb
import com.maukingdom.musicplayer.ui.v2.relativeLuminance
import com.maukingdom.musicplayer.ui.v2.rgbToHsl
import java.util.Locale
import kotlin.math.abs

/*
 * The Studio player's colour, labels and times. Pure, so it is unit tested.
 * The player uses ONE hue: the skin's tint, or the cover's dominant colour.
 */

/** Each skin's single tint in the Studio player (from the "v2 · Studio player" mockup). */
fun studioSkinTint(skin: PlayerSkin): Int = when (skin) {
    PlayerSkin.NEON_WALKMAN -> 0xFFFF8A3D.toInt()
    PlayerSkin.GLASS_COCKPIT -> 0xFFFFB27A.toInt()
    PlayerSkin.INSTRUMENT -> 0xFFE8352A.toInt()
    PlayerSkin.CALM_BLUE -> 0xFF4C8DFF.toInt()
    PlayerSkin.SUNLIGHT -> 0xFFFFD400.toInt()
    PlayerSkin.HEAD_UNIT -> 0xFF9DFF6A.toInt()
    PlayerSkin.MINIMAL -> 0xFFFF7A1A.toInt()
}

/** Covers greyer than this have no colour to take; the skin tint is used. */
const val MIN_COVER_SATURATION = 0.15f

/** Tint luminance range: bright enough for the 3:1 active-icon contrast on #0B0B0F, never washed out. */
const val MIN_TINT_LUMINANCE = 0.12
const val MAX_TINT_LUMINANCE = 0.55

/**
 * The cover's dominant colour from opaque ARGB [pixels]: an average that
 * weights vivid, mid-light pixels most, so a red jacket on a grey wall
 * reads red rather than a muddy brown. Null when nothing is opaque.
 */
fun dominantColor(pixels: IntArray): Int? {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        val (_, s, l) = rgbToHsl(p)
        // Saturation counts twice; very dark and very light pixels count least.
        val weight = 0.02 + s * s * (1.0 - abs(2.0 * l - 1.0))
        r += ((p shr 16) and 0xFF) * weight
        g += ((p shr 8) and 0xFF) * weight
        b += (p and 0xFF) * weight
        total += weight
    }
    if (total == 0.0) return null
    return (0xFF shl 24) or
        ((r / total).toInt().coerceIn(0, 255) shl 16) or
        ((g / total).toInt().coerceIn(0, 255) shl 8) or
        (b / total).toInt().coerceIn(0, 255)
}

/**
 * The Studio tint for a cover's [dominantArgb]: its hue and saturation
 * kept, its lightness moved until luminance is within
 * [MIN_TINT_LUMINANCE]..[MAX_TINT_LUMINANCE]. Null for a near-grey cover.
 */
fun studioTintFromCover(dominantArgb: Int): Int? {
    val (h, s, l) = rgbToHsl(dominantArgb)
    if (s < MIN_COVER_SATURATION) return null
    var light = l
    var colour = hslToRgb(h, s, light)
    var guard = 0
    while (relativeLuminance(colour) < MIN_TINT_LUMINANCE && light < 0.95f && guard++ < 100) {
        light += 0.01f
        colour = hslToRgb(h, s, light)
    }
    while (relativeLuminance(colour) > MAX_TINT_LUMINANCE && light > 0.05f && guard++ < 200) {
        light -= 0.01f
        colour = hslToRgb(h, s, light)
    }
    return colour
}

/** "MP3", "FLAC", "AAC"…, from the MIME type or else the file name; null when unknown. */
fun formatBadge(mimeType: String?, uri: String?): String? {
    val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    val byMime = when {
        "mpeg" in mime || "mp3" in mime -> "MP3"
        "flac" in mime -> "FLAC"
        "opus" in mime -> "OPUS"
        "ogg" in mime || "vorbis" in mime -> "OGG"
        "wav" in mime || "x-wav" in mime -> "WAV"
        "mp4" in mime || "m4a" in mime || "aac" in mime -> "AAC"
        else -> null
    }
    if (byMime != null) return byMime
    val extension = uri?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    return when (extension) {
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "opus" -> "OPUS"
        "ogg" -> "OGG"
        "wav" -> "WAV"
        "m4a", "aac", "mp4" -> "AAC"
        else -> null
    }
}

/** "1:55", "12:03", "1:02:09"; never negative. */
fun studioClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total / 60L) % 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** "-3:08" left of [durationMs] at [positionMs]; "--:--" while the duration is unknown. */
fun studioRemaining(positionMs: Long, durationMs: Long): String =
    if (durationMs <= 0L) "--:--" else "-" + studioClock(durationMs - positionMs.coerceIn(0L, durationMs))

/** Bars shown with animations off: low and still, a gentle slope from the bass up. */
val STATIC_BAR_LEVELS: FloatArray = FloatArray(40) { 0.14f + 0.08f * kotlin.math.sin(it * 0.55f).let { s -> s * s } }
