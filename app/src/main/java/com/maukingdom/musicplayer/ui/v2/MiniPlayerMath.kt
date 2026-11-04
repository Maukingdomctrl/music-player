package com.maukingdom.musicplayer.ui.v2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Pure helpers for the mini player and the Now Playing sheet. No Android or
 * Compose types, so they run in JVM tests.
 */

/** Lightness range of the mini player's tint before the contrast check. */
private const val TINT_MIN_LIGHTNESS = 0.20f
private const val TINT_MAX_LIGHTNESS = 0.35f

/** Muted, not neon: covers with loud colours still give a calm bar. */
private const val TINT_MAX_SATURATION = 0.55f

/**
 * Contrast of white on the tint. 6:1 (not just 4.5) so the artist line,
 * white at [MINI_SUBTITLE_ALPHA], still has at least 4.5:1.
 */
const val MINI_MIN_CONTRAST = 6.0f

/** Opacity of the white artist line on the mini player. */
const val MINI_SUBTITLE_ALPHA = 0.82f

/**
 * Average of opaque ARGB [pixels] (fully transparent ones are skipped), as
 * an opaque colour. Null when there is nothing to average.
 */
fun averageColor(pixels: IntArray): Int? {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (p in pixels) {
        if ((p ushr 24) == 0) continue
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0L) return null
    return argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * The mini player background for a cover whose average colour is [argb]:
 * same hue, saturation capped, lightness pulled into 20–35 %, then darkened
 * further until white text has at least [MINI_MIN_CONTRAST]:1.
 */
fun miniPlayerTint(argb: Int): Int {
    val (h, s, l) = rgbToHsl(argb)
    val sat = min(s, TINT_MAX_SATURATION)
    var light = l.coerceIn(TINT_MIN_LIGHTNESS, TINT_MAX_LIGHTNESS)
    var result = hslToRgb(h, sat, light)
    while (contrastRatio(WHITE, result) < MINI_MIN_CONTRAST && light > 0f) {
        light = max(0f, light - 0.01f)
        result = hslToRgb(h, sat, light)
    }
    return result
}

/** WCAG relative luminance of an ARGB colour (alpha ignored). */
fun relativeLuminance(argb: Int): Double {
    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((argb shr 16) and 0xFF) +
        0.7152 * channel((argb shr 8) and 0xFF) +
        0.0722 * channel(argb and 0xFF)
}

/** WCAG contrast ratio between two colours, 1..21. */
fun contrastRatio(a: Int, b: Int): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return ((max(la, lb) + 0.05) / (min(la, lb) + 0.05)).toFloat()
}

/** Played fraction for the progress line: 0 while the duration is unknown, else clamped to 0..1. */
fun miniProgress(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** What a finished drag on the mini player means. */
enum class MiniSwipe { NONE, NEXT, PREVIOUS, OPEN }

/**
 * Decides a mini player drag from its total movement ([dx], [dy], px) and
 * release velocity ([vx], [vy], px/s). The dominant axis wins. A drag counts
 * when it travels [distancePx] or is flung faster than [velocityPx].
 * Left = next, right = previous, up = open; down does nothing.
 */
fun resolveMiniSwipe(
    dx: Float,
    dy: Float,
    vx: Float,
    vy: Float,
    distancePx: Float,
    velocityPx: Float
): MiniSwipe {
    val horizontal = abs(dx) >= abs(dy)
    return if (horizontal) {
        val committed = abs(dx) >= distancePx || (abs(vx) >= velocityPx && abs(dx) > distancePx / 4)
        when {
            !committed -> MiniSwipe.NONE
            // A fling against the drag direction cancels it.
            dx < 0 && vx <= velocityPx -> MiniSwipe.NEXT
            dx > 0 && vx >= -velocityPx -> MiniSwipe.PREVIOUS
            else -> MiniSwipe.NONE
        }
    } else {
        val up = dy <= -distancePx || (vy <= -velocityPx && dy < 0)
        if (up) MiniSwipe.OPEN else MiniSwipe.NONE
    }
}

/**
 * Whether a released drag on the Now Playing header closes the sheet.
 * [fraction] is how far it was dragged down (0 = open, 1 = off screen) and
 * [velocityPx] the release velocity (positive = down).
 */
fun shouldCloseSheet(fraction: Float, velocityPx: Float, flingPx: Float): Boolean = when {
    velocityPx >= flingPx -> true
    velocityPx <= -flingPx -> false
    else -> fraction >= 0.25f
}

/** True when the system "remove animations" setting is on (animator scale 0). */
fun animationsOff(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** [fg] at [alpha] over the opaque [bg], as an opaque colour. */
fun blendOver(fg: Int, alpha: Float, bg: Int): Int {
    fun mix(shift: Int): Int {
        val f = (fg shr shift) and 0xFF
        val b = (bg shr shift) and 0xFF
        return Math.round(f * alpha + b * (1f - alpha))
    }
    return argb(mix(16), mix(8), mix(0))
}

private const val WHITE = -0x1 // 0xFFFFFFFF

private fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** HSL with h in 0..360, s and l in 0..1. */
internal fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
}

internal fun hslToRgb(h: Float, s: Float, l: Float): Int {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun to255(v: Float) = Math.round((v + m) * 255f)
    return argb(to255(r), to255(g), to255(b))
}
