package com.maukingdom.musicplayer.ui.v2

/** The font scale range the Modern layout is designed for. */
const val MIN_FONT_SCALE = 0.9f
const val MAX_FONT_SCALE = 1.3f

/**
 * The font scale Modern actually uses: the system setting, kept within
 * [MIN_FONT_SCALE]..[MAX_FONT_SCALE] so very small settings stay readable
 * in the car and very large ones do not break the layout. Pure, for JVM tests.
 */
fun clampFontScale(systemFontScale: Float): Float =
    systemFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
