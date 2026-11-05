package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.playback.EqPreset
import com.maukingdom.musicplayer.playback.ToneControl
import com.maukingdom.musicplayer.playback.widget.WidgetModel
import com.maukingdom.musicplayer.playback.widget.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualiserAndWidgetTest {

    // A typical five-band phone equaliser, and a ten-band one.
    private val five = listOf(60, 230, 910, 3_600, 14_000)
    private val ten = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    private fun levels(
        preset: EqPreset,
        bass: Int = 0,
        treble: Int = 0,
        bands: List<Int> = five,
        min: Int = -1_500,
        max: Int = 1_500
    ) = ToneControl.bandLevels(bands, min, max, bass, treble, preset)

    // Band mapping

    @Test
    fun presetsMapByCentreFrequency() {
        assertEquals(listOf(0, 0, 0, 0, 0), levels(EqPreset.FLAT))
        // Car: low +4 / mid 0 / high +2 (230 Hz is still "low").
        assertEquals(listOf(400, 400, 0, 0, 200), levels(EqPreset.CAR))
        // Vocal: low −2 / mid +4 / high +1.
        assertEquals(listOf(-200, -200, 400, 400, 100), levels(EqPreset.VOCAL))
        assertEquals(listOf(600, 600, 0, 0, 0), levels(EqPreset.BASS_BOOST))
        assertEquals(listOf(200, 200, -100, -100, 200), levels(EqPreset.EARPHONES))
    }

    @Test
    fun regionBoundaries() {
        val preset = EqPreset.BASS_BOOST // low +6, low-mid +3
        assertEquals(6, preset.gainDb(250))
        assertEquals(3, preset.gainDb(251))
        assertEquals(3, preset.gainDb(800))
        assertEquals(0, preset.gainDb(801))
        assertEquals(2, EqPreset.CAR.gainDb(4_000))
        assertEquals(0, EqPreset.CAR.gainDb(3_999))
    }

    @Test
    fun tenBandPhonesGetTheLowMidStep() {
        assertEquals(
            listOf(600, 600, 600, 600, 300, 0, 0, 0, 0, 0),
            levels(EqPreset.BASS_BOOST, bands = ten)
        )
    }

    // Preset + knobs

    @Test
    fun knobsAddOnTopOfThePreset() {
        // Car + bass +2 + treble +3: low 4+2, high 2+3, the 3.6 kHz band gets half the treble.
        assertEquals(listOf(600, 600, 0, 150, 500), levels(EqPreset.CAR, bass = 2, treble = 3))
        // Knobs can also cut against a preset.
        assertEquals(listOf(-200, -200, 0, 0, 200), levels(EqPreset.CAR, bass = -6))
    }

    @Test
    fun sumsClampToTheDeviceRange() {
        assertEquals(listOf(1_000, 1_000, 0, 0, 0), levels(EqPreset.BASS_BOOST, bass = 6, max = 1_000))
        assertEquals(listOf(-400, -400, 400, 400, 100), levels(EqPreset.VOCAL, bass = -6, min = -400, max = 400))
    }

    @Test
    fun classicKnobsAloneAreUnchanged() {
        // The old call (no preset) behaves exactly as before.
        assertEquals(listOf(600, 600, 0, -300, -600), ToneControl.bandLevels(five, -1_500, 1_500, 6, -6))
    }

    @Test
    fun equaliserIsOnForAnyNonFlatSetting() {
        assertFalse(ToneControl.isActive(0, 0, EqPreset.FLAT))
        assertTrue(ToneControl.isActive(0, 0, EqPreset.CAR))
        assertTrue(ToneControl.isActive(1, 0, EqPreset.FLAT))
    }

    @Test
    fun savedIdsRoundTripAndUnknownIsFlat() {
        EqPreset.entries.forEach { assertEquals(it, EqPreset.fromId(it.id)) }
        assertEquals(EqPreset.FLAT, EqPreset.fromId(null))
        assertEquals(EqPreset.FLAT, EqPreset.fromId("loudness"))
        assertEquals(listOf("Flat", "Car", "Vocal", "Bass boost", "Earphones"), EqPreset.entries.map { it.label })
    }

    // Widget

    @Test
    fun widgetEmptyStateHasOneLine() {
        assertEquals("Tap to start MyCar Music" to null, WidgetModel.texts(WidgetState(), "Tap to start MyCar Music"))
        val playing = WidgetState(hasSong = true, songId = "s", title = "Tum Hi Ho", artist = "", isPlaying = true)
        assertEquals("Tum Hi Ho" to "Unknown artist", WidgetModel.texts(playing, "x"))
    }

    @Test
    fun widgetLayoutBySize() {
        assertFalse(WidgetModel.useLarge(widthDp = 320, heightDp = 60))
        assertTrue(WidgetModel.useLarge(widthDp = 320, heightDp = 140))
        // Tall but too narrow for the 4x2 row: the 4x1 layout.
        assertFalse(WidgetModel.useLarge(widthDp = 200, heightDp = 140))
    }

    @Test
    fun onlyPlayWhilePausedAsksForAForegroundStart() {
        assertTrue(WidgetModel.playNeedsForeground(WidgetState(hasSong = true, isPlaying = false)))
        assertFalse(WidgetModel.playNeedsForeground(WidgetState(hasSong = true, isPlaying = true)))
        assertFalse(WidgetModel.playNeedsForeground(WidgetState(hasSong = false)))
        assertNull(WidgetState().songId)
        assertEquals(256, WidgetModel.COVER_MAX_PX)
    }
}
