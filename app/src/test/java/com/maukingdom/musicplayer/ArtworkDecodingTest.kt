package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.artworkSampleSize
import com.maukingdom.musicplayer.ui.decodeWithinBounds
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkDecodingTest {

    /** Like BitmapFactory with inJustDecodeBounds: reports the size, returns no bitmap. */
    private fun boundsOnly(width: Int, height: Int): (InputStream) -> Pair<Int, Int> = { width to height }

    @Test
    fun aBoundsOnlyDecodeThatReturnsNoBitmapStillLoadsTheCover() {
        var opened = 0
        var usedSample = -1
        val result = decodeWithinBounds(
            open = { opened++; ByteArrayInputStream(ByteArray(1)) },
            readSize = boundsOnly(1200, 1200),
            decode = { _, sample -> usedSample = sample; "bitmap" },
            maxPx = 512
        )
        assertEquals("bitmap", result)
        assertEquals(2, opened)
        // 1200 -> 600 still >= 512; 300 would be too small.
        assertEquals(2, usedSample)
    }

    @Test
    fun failuresGiveNull() {
        assertNull(decodeWithinBounds<InputStream, String>({ null }, boundsOnly(500, 500), { _, _ -> "x" }, 512))
        assertNull(decodeWithinBounds({ ByteArrayInputStream(ByteArray(0)) }, boundsOnly(-1, -1), { _, _ -> "x" }, 512))
        assertNull(decodeWithinBounds({ ByteArrayInputStream(ByteArray(0)) }, boundsOnly(500, 0), { _, _ -> "x" }, 512))
        assertNull(decodeWithinBounds<InputStream, String>({ ByteArrayInputStream(ByteArray(0)) }, boundsOnly(500, 500), { _, _ -> null }, 512))
    }

    @Test
    fun sampleSizes() {
        assertEquals(1, artworkSampleSize(512, 512, 512))
        assertEquals(1, artworkSampleSize(1000, 1000, 512))
        assertEquals(2, artworkSampleSize(1024, 1024, 512))
        assertEquals(4, artworkSampleSize(3000, 2048, 512))
        // The shorter side decides: never below the target.
        assertEquals(1, artworkSampleSize(4000, 600, 512))
    }
}
