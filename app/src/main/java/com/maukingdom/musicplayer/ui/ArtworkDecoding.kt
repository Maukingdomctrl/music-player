package com.maukingdom.musicplayer.ui

import java.io.Closeable

/*
 * The steps of MusicViewModel.loadArtwork, with the Android decoders passed
 * in, so the control flow is unit tested on the JVM.
 */

/** The largest power-of-two subsample that still leaves both sides ≥ [maxPx]. */
internal fun artworkSampleSize(width: Int, height: Int, maxPx: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxPx && height / (sample * 2) >= maxPx) {
        sample *= 2
    }
    return sample
}

/**
 * Reads the image size with [readSize] (a bounds-only decode: it returns
 * the size, never a bitmap), then decodes once more with [decode] at the
 * subsample for [maxPx]. Null when a stream cannot be opened, the size is
 * unknown, or decoding fails.
 *
 * The size comes from [readSize]'s result, not from whether a bitmap came
 * back: a bounds-only BitmapFactory.decodeStream always returns null, and
 * treating that null as a failure made every cover load fail.
 */
internal fun <S : Closeable, B : Any> decodeWithinBounds(
    open: () -> S?,
    readSize: (S) -> Pair<Int, Int>,
    decode: (S, Int) -> B?,
    maxPx: Int
): B? {
    val (width, height) = open()?.use(readSize) ?: return null
    if (width <= 0 || height <= 0) return null
    val sample = artworkSampleSize(width, height, maxPx)
    return open()?.use { decode(it, sample) }
}
