package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.v2.cover.CoverCandidateRef
import com.maukingdom.musicplayer.ui.v2.cover.CoverMode
import com.maukingdom.musicplayer.ui.v2.cover.EraStyle
import com.maukingdom.musicplayer.ui.v2.cover.MAX_ANIMATED_COVERS
import com.maukingdom.musicplayer.ui.v2.cover.cdSweepAt
import com.maukingdom.musicplayer.ui.v2.cover.coverCandidates
import com.maukingdom.musicplayer.ui.v2.cover.coverModeFor
import com.maukingdom.musicplayer.ui.v2.cover.crossfadeAt
import com.maukingdom.musicplayer.ui.v2.cover.eraStyleFor
import com.maukingdom.musicplayer.ui.v2.cover.generateArt
import com.maukingdom.musicplayer.ui.v2.cover.grantedSlots
import com.maukingdom.musicplayer.ui.v2.cover.kenBurnsAt
import com.maukingdom.musicplayer.ui.v2.cover.petalsAt
import com.maukingdom.musicplayer.ui.v2.cover.shouldAnimate
import com.maukingdom.musicplayer.ui.v2.cover.vhsShiftAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverModelTest {

    @Test
    fun eraStylesFromNames() {
        val expected = mapOf(
            "Old Songs" to EraStyle.SEPIA,
            "OLDIES" to EraStyle.SEPIA,
            "Classic Hindi" to EraStyle.SEPIA,
            "classics" to EraStyle.SEPIA,
            "Hindi 90s" to EraStyle.VHS,
            "1990s Hits" to EraStyle.VHS,
            "Bollywood 2000s" to EraStyle.CD,
            "2000" to EraStyle.CD,
            "New Releases" to EraStyle.MODERN,
            "newest" to EraStyle.MODERN,
            "Marathi Gaani" to EraStyle.MARATHI,
            "MARATHI" to EraStyle.MARATHI,
            "Ganpati Aarti" to EraStyle.GANPATI,
            "ganesh utsav" to EraStyle.GANPATI,
            "Bappa Morya" to EraStyle.GANPATI,
            "Road Trip" to EraStyle.NEUTRAL,
            "" to EraStyle.NEUTRAL
        )
        expected.forEach { (name, style) -> assertEquals(name, style, eraStyleFor(name)) }
    }

    @Test
    fun eraStyleWordRulesAndPriority() {
        // "old"/"classic"/"new" must start a word.
        assertEquals(EraStyle.NEUTRAL, eraStyleFor("Bold Beats"))
        assertEquals(EraStyle.NEUTRAL, eraStyleFor("Renewal"))
        assertEquals(EraStyle.NEUTRAL, eraStyleFor("Neoclassical"))
        // Most specific wins.
        assertEquals(EraStyle.VHS, eraStyleFor("Old Hindi 90s"))
        assertEquals(EraStyle.GANPATI, eraStyleFor("Marathi Ganpati Songs"))
        assertEquals(EraStyle.MARATHI, eraStyleFor("Marathi 90s"))
        assertEquals(EraStyle.VHS, eraStyleFor("New 90s mix"))
        // Punctuation separates words.
        assertEquals(EraStyle.MODERN, eraStyleFor("Hits-New!"))
    }

    @Test
    fun pictureCountToMode() {
        assertEquals(CoverMode.GENERATED, coverModeFor(0, 140f))
        assertEquals(CoverMode.KEN_BURNS, coverModeFor(1, 220f))
        assertEquals(CoverMode.CROSSFADE, coverModeFor(2, 140f))
        assertEquals(CoverMode.CROSSFADE, coverModeFor(3, 140f))
        assertEquals(CoverMode.MOSAIC, coverModeFor(4, 140f))
        // Small rows and the big header show one picture at a time.
        assertEquals(CoverMode.CROSSFADE, coverModeFor(4, 56f))
        assertEquals(CoverMode.CROSSFADE, coverModeFor(4, 220f))
    }

    @Test
    fun candidatesPreferDifferentAlbums() {
        val refs = listOf(
            CoverCandidateRef(1, "a", "Dil Se"),
            CoverCandidateRef(1, "b", "dil se "),
            CoverCandidateRef(1, "c", "Taal"),
            CoverCandidateRef(1, "d", "Lagaan"),
            CoverCandidateRef(2, "x", "Solo")
        )
        val map = coverCandidates(refs, perPlaylist = 8)
        assertEquals(listOf("a", "c", "d", "b"), map[1L])
        assertEquals(listOf("x"), map[2L])
        assertEquals(listOf("a", "c"), coverCandidates(refs, perPlaylist = 2)[1L])
    }

    @Test
    fun generatedArtIsStablePerPlaylist() {
        assertEquals(generateArt(42L), generateArt(42L))
        assertNotEquals(generateArt(42L), generateArt(43L))
        val art = generateArt(7L)
        assertTrue(art.rings.isNotEmpty())
        art.rings.forEach { assertTrue(it.radius in 0f..1f && it.tone in 0..3) }
        assertTrue(art.centerX in 0.3f..0.7f && art.centerY in 0.3f..0.7f)
    }

    @Test
    fun petalsAreStableAndCounted() {
        assertEquals(petalsAt(3.5f, 11L), petalsAt(3.5f, 11L))
        val count = petalsAt(0f, 11L).size
        assertTrue(count in 6..10)
        // They move over time.
        assertNotEquals(petalsAt(0f, 11L), petalsAt(1f, 11L))
    }

    @Test
    fun pauseRules() {
        assertTrue(shouldAnimate(requested = true, animationsOff = false, appResumed = true, covered = false, hasSlot = true))
        assertFalse(shouldAnimate(false, false, true, false, true))
        assertFalse("remove animations", shouldAnimate(true, true, true, false, true))
        assertFalse("not resumed", shouldAnimate(true, false, false, false, true))
        assertFalse("covered by Now Playing / playlist", shouldAnimate(true, false, true, true, true))
        assertFalse("no slot", shouldAnimate(true, false, true, false, false))
    }

    @Test
    fun atMostThreeCoversMove() {
        assertEquals(3, MAX_ANIMATED_COVERS)
        assertEquals(listOf(1L, 2L, 3L), grantedSlots(listOf(1L, 2L, 3L, 4L, 5L)))
        assertEquals(listOf(7L), grantedSlots(listOf(7L)))
        // When one leaves, the next waiting cover gets its slot.
        assertEquals(listOf(1L, 3L, 4L), grantedSlots(listOf(1L, 3L, 4L, 5L)))
    }

    @Test
    fun timingCurves() {
        // Ken Burns starts at rest and stays within 1.0..1.08.
        assertEquals(1f, kenBurnsAt(0f, 5L).scale, 1e-6f)
        for (s in 0..60) assertTrue(kenBurnsAt(s.toFloat(), 5L).scale in 1f..1.0801f)
        // Crossfade holds ~5 s, then fades to the next.
        assertEquals(0, crossfadeAt(1f, 3).current)
        assertEquals(0f, crossfadeAt(4.9f, 3).fade, 0f)
        assertTrue(crossfadeAt(5.6f, 3).fade in 0.01f..0.99f)
        assertEquals(1, crossfadeAt(6.3f, 3).current)
        // VHS shift: at most one short burst per 6.5 s window.
        val shifted = (0 until 6500).count { vhsShiftAt(it / 1000f, 3L) != 0f }
        assertTrue("shift ms = $shifted", shifted in 1..18)
        // CD sweep: light only for a short part of each 7 s.
        val lit = (0 until 70).count { cdSweepAt(it / 10f, 2L) != null }
        assertTrue(lit in 10..16)
        assertNull(cdSweepAt(3f, 0L))
    }
}
