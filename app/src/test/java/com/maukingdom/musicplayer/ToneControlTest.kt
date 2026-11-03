package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.playback.ToneControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneControlTest {

    // A typical five-band phone equaliser.
    private val bands = listOf(60, 230, 910, 3_600, 14_000)

    private fun levels(bass: Int, treble: Int, min: Int = -1_500, max: Int = 1_500) =
        ToneControl.bandLevels(bands, min, max, bass, treble)

    @Test
    fun zeroGivesAllZero() {
        assertEquals(listOf(0, 0, 0, 0, 0), levels(0, 0))
        assertFalse(ToneControl.isActive(0, 0))
    }

    @Test
    fun bassAppliesToLowBands() {
        assertEquals(listOf(600, 600, 0, 0, 0), levels(6, 0))
        assertEquals(listOf(-300, -300, 0, 0, 0), levels(-3, 0))
        assertTrue(ToneControl.isActive(6, 0))
    }

    @Test
    fun trebleAppliesFullyAbove4kAndHalfFrom2kTo4k() {
        assertEquals(listOf(0, 0, 0, 300, 600), levels(0, 6))
        assertEquals(listOf(0, 0, 0, -100, -200), levels(0, -2))
        assertTrue(ToneControl.isActive(0, -2))
    }

    @Test
    fun bassHalfZoneAndBoundaries() {
        val edges = listOf(250, 251, 500, 501, 1_999, 2_000, 3_999, 4_000)
        assertEquals(
            listOf(400, 200, 200, 0, 0, 0, 0, 0),
            ToneControl.bandLevels(edges, -1_500, 1_500, 4, 0)
        )
        assertEquals(
            listOf(0, 0, 0, 0, 0, 200, 200, 400),
            ToneControl.bandLevels(edges, -1_500, 1_500, 0, 4)
        )
    }

    @Test
    fun bassAndTrebleTogether() {
        // Their zones never overlap, so each band gets at most one of them.
        assertEquals(listOf(600, 600, 0, -300, -600), levels(6, -6))
    }

    @Test
    fun levelsClampToTheEqualiserRange() {
        assertEquals(listOf(400, 400, 0, 300, 400), levels(6, 6, min = -400, max = 400))
        assertEquals(listOf(-400, -400, 0, -300, -400), levels(-6, -6, min = -400, max = 400))
    }

    @Test
    fun inputDbClampsToPlusMinus6() {
        assertEquals(levels(6, 6), levels(20, 99))
        assertEquals(levels(-6, -6), levels(-20, -99))
        assertEquals(6, ToneControl.clampDb(9))
        assertEquals(-6, ToneControl.clampDb(-9))
    }

    @Test
    fun knobMappingHasACentreDetent() {
        assertEquals(0.5f, ToneControl.fractionForDb(0), 1e-6f)
        assertEquals(0f, ToneControl.fractionForDb(-6), 1e-6f)
        assertEquals(1f, ToneControl.fractionForDb(6), 1e-6f)

        for (db in -6..6) {
            assertEquals(db, ToneControl.dbForFraction(ToneControl.fractionForDb(db)))
        }

        // 0.7 dB either side of centre still reads 0; 0.8 dB rounds to 1.
        assertEquals(0, ToneControl.dbForFraction(0.5f + 0.7f / 12f))
        assertEquals(0, ToneControl.dbForFraction(0.5f - 0.7f / 12f))
        assertEquals(1, ToneControl.dbForFraction(0.5f + 0.8f / 12f))
        assertEquals(-1, ToneControl.dbForFraction(0.5f - 0.8f / 12f))
    }

    @Test
    fun formatsWithSignAndRealMinus() {
        assertEquals("+3", ToneControl.formatDb(3))
        assertEquals("0", ToneControl.formatDb(0))
        assertEquals("−2", ToneControl.formatDb(-2))
    }
}
