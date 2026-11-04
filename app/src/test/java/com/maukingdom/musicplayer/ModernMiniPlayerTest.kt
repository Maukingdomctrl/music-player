package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.v2.MINI_SUBTITLE_ALPHA
import com.maukingdom.musicplayer.ui.v2.MiniSwipe
import com.maukingdom.musicplayer.ui.v2.animationsOff
import com.maukingdom.musicplayer.ui.v2.averageColor
import com.maukingdom.musicplayer.ui.v2.blendOver
import com.maukingdom.musicplayer.ui.v2.contrastRatio
import com.maukingdom.musicplayer.ui.v2.miniPlayerTint
import com.maukingdom.musicplayer.ui.v2.miniProgress
import com.maukingdom.musicplayer.ui.v2.relativeLuminance
import com.maukingdom.musicplayer.ui.v2.resolveMiniSwipe
import com.maukingdom.musicplayer.ui.v2.shouldCloseSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernMiniPlayerTest {

    private val white = 0xFFFFFFFF.toInt()

    private val inputs = mapOf(
        "white" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF000000.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "pale yellow" to 0xFFFFF5B0.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "orange" to 0xFFFF8A3D.toInt(),
        "mid grey" to 0xFF808080.toInt(),
        "navy" to 0xFF101040.toInt(),
        "pastel pink" to 0xFFFFC0CB.toInt()
    )

    @Test
    fun tintKeepsWhiteTitleAboveAa() {
        for ((name, argb) in inputs) {
            val tint = miniPlayerTint(argb)
            val ratio = contrastRatio(white, tint)
            assertTrue("$name: white contrast $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun tintKeepsArtistLineAboveAa() {
        for ((name, argb) in inputs) {
            val tint = miniPlayerTint(argb)
            val artist = blendOver(white, MINI_SUBTITLE_ALPHA, tint)
            val ratio = contrastRatio(artist, tint)
            assertTrue("$name: artist contrast $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun tintIsDarkAndOpaque() {
        for ((name, argb) in inputs) {
            val tint = miniPlayerTint(argb)
            assertEquals("$name opaque", 0xFF, tint ushr 24)
            assertTrue("$name dark", relativeLuminance(tint) < 0.15)
        }
    }

    @Test
    fun blackCoverStillGivesAVisibleBar() {
        // Not pure black, so the bar stands out from the #0B0B0F background.
        assertTrue(relativeLuminance(miniPlayerTint(0xFF000000.toInt())) > relativeLuminance(0xFF0B0B0F.toInt()))
    }

    @Test
    fun tintKeepsHueFamily() {
        val tint = miniPlayerTint(0xFFFF0000.toInt())
        val r = (tint shr 16) and 0xFF
        val g = (tint shr 8) and 0xFF
        val b = tint and 0xFF
        assertTrue(r > g && r > b)
    }

    @Test
    fun averageSkipsTransparentPixels() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0x00FFFFFF)
        assertEquals(0xFF7F007F.toInt(), averageColor(pixels))
        assertNull(averageColor(intArrayOf(0, 0)))
        assertNull(averageColor(IntArray(0)))
    }

    @Test
    fun progressIsZeroWithoutDurationAndClamped() {
        assertEquals(0f, miniProgress(5_000L, 0L), 0f)
        assertEquals(0f, miniProgress(5_000L, -1L), 0f)
        assertEquals(0.5f, miniProgress(30_000L, 60_000L), 1e-6f)
        assertEquals(1f, miniProgress(90_000L, 60_000L), 0f)
        assertEquals(0f, miniProgress(-10L, 60_000L), 0f)
    }

    private fun swipe(dx: Float, dy: Float, vx: Float = 0f, vy: Float = 0f) =
        resolveMiniSwipe(dx, dy, vx, vy, distancePx = 100f, velocityPx = 1_000f)

    @Test
    fun horizontalSwipesSkip() {
        assertEquals(MiniSwipe.NEXT, swipe(-120f, 10f))
        assertEquals(MiniSwipe.PREVIOUS, swipe(120f, -10f))
        assertEquals(MiniSwipe.NONE, swipe(-60f, 0f))
    }

    @Test
    fun shortFlingCountsButNotATwitch() {
        assertEquals(MiniSwipe.NEXT, swipe(-40f, 0f, vx = -1_500f))
        assertEquals(MiniSwipe.NONE, swipe(-10f, 0f, vx = -1_500f))
    }

    @Test
    fun flingBackCancelsSkip() {
        assertEquals(MiniSwipe.NONE, swipe(-120f, 0f, vx = 2_000f))
        assertEquals(MiniSwipe.NONE, swipe(120f, 0f, vx = -2_000f))
    }

    @Test
    fun upOpensDownDoesNothing() {
        assertEquals(MiniSwipe.OPEN, swipe(5f, -120f))
        assertEquals(MiniSwipe.OPEN, swipe(0f, -30f, vy = -1_500f))
        assertEquals(MiniSwipe.NONE, swipe(0f, 200f, vy = 3_000f))
        assertEquals(MiniSwipe.NONE, swipe(0f, -40f))
    }

    @Test
    fun sheetClosesOnDistanceOrFling() {
        assertFalse(shouldCloseSheet(0.1f, 0f, 1_000f))
        assertTrue(shouldCloseSheet(0.3f, 0f, 1_000f))
        assertTrue(shouldCloseSheet(0.05f, 1_500f, 1_000f))
        // An upward fling keeps it open even after a long drag.
        assertFalse(shouldCloseSheet(0.6f, -1_500f, 1_000f))
    }

    @Test
    fun animatorScaleZeroMeansNoAnimation() {
        assertTrue(animationsOff(0f))
        assertFalse(animationsOff(0.5f))
        assertFalse(animationsOff(1f))
    }
}
