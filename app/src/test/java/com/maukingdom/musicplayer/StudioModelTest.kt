package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.v2.contrastRatio
import com.maukingdom.musicplayer.ui.v2.relativeLuminance
import com.maukingdom.musicplayer.ui.v2.studio.MAX_TINT_LUMINANCE
import com.maukingdom.musicplayer.ui.v2.studio.MIN_TINT_LUMINANCE
import com.maukingdom.musicplayer.ui.v2.studio.dominantColor
import com.maukingdom.musicplayer.ui.v2.studio.formatBadge
import com.maukingdom.musicplayer.ui.v2.studio.studioClock
import com.maukingdom.musicplayer.ui.v2.studio.studioRemaining
import com.maukingdom.musicplayer.ui.v2.studio.studioSkinTint
import com.maukingdom.musicplayer.ui.v2.studio.studioTintFromCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelTest {

    @Test
    fun skinTintsFromTheMockup() {
        assertEquals(0xFFFF8A3D.toInt(), studioSkinTint(PlayerSkin.NEON_WALKMAN))
        assertEquals(0xFFFFB27A.toInt(), studioSkinTint(PlayerSkin.GLASS_COCKPIT))
        assertEquals(0xFFE8352A.toInt(), studioSkinTint(PlayerSkin.INSTRUMENT))
        assertEquals(0xFF4C8DFF.toInt(), studioSkinTint(PlayerSkin.CALM_BLUE))
        assertEquals(0xFFFFD400.toInt(), studioSkinTint(PlayerSkin.SUNLIGHT))
        assertEquals(0xFF9DFF6A.toInt(), studioSkinTint(PlayerSkin.HEAD_UNIT))
        assertEquals(0xFFFF7A1A.toInt(), studioSkinTint(PlayerSkin.MINIMAL))
    }

    @Test
    fun dominantColourFavoursVividPixels() {
        // 75 % grey wall, 25 % red jacket: the dominant colour is clearly red.
        val pixels = IntArray(64) { if (it % 4 == 0) 0xFFD02020.toInt() else 0xFF808080.toInt() }
        val dominant = dominantColor(pixels)!!
        val r = (dominant shr 16) and 0xFF
        val g = (dominant shr 8) and 0xFF
        assertTrue("r=$r g=$g", r > g + 60)
        assertNull(dominantColor(IntArray(4) { 0 }))
    }

    @Test
    fun coverTintKeepsHueAndClampsLuminance() {
        val bg = 0xFF0B0B0F.toInt()
        for (cover in listOf(0xFF200008.toInt(), 0xFFFF2050.toInt(), 0xFFFFFF60.toInt(), 0xFF103080.toInt(), 0xFF2E7D32.toInt())) {
            val tint = studioTintFromCover(cover)!!
            val lum = relativeLuminance(tint)
            assertTrue("lum $lum", lum >= MIN_TINT_LUMINANCE - 0.005 && lum <= MAX_TINT_LUMINANCE + 0.005)
            // Active shuffle/repeat icons in the tint stay visible on the background.
            assertTrue(contrastRatio(tint, bg) >= 3f)
        }
        // A dark red stays red.
        val red = studioTintFromCover(0xFF400010.toInt())!!
        assertTrue(((red shr 16) and 0xFF) > ((red shr 8) and 0xFF) + 60)
        // Greys have no colour to take: the skin tint is used instead.
        assertNull(studioTintFromCover(0xFF808080.toInt()))
        assertNull(studioTintFromCover(0xFFFFFFFF.toInt()))
    }

    @Test
    fun formatBadges() {
        assertEquals("MP3", formatBadge("audio/mpeg", null))
        assertEquals("FLAC", formatBadge("audio/flac", null))
        assertEquals("AAC", formatBadge("audio/mp4", null))
        assertEquals("OPUS", formatBadge("audio/ogg; codecs=opus", null))
        assertEquals("MP3", formatBadge(null, "content://x/Music/Tujhe%20Dekha.MP3"))
        assertNull(formatBadge(null, "content://x/document/12345"))
        assertNull(formatBadge("application/octet-stream", null))
    }

    @Test
    fun clocks() {
        assertEquals("1:55", studioClock(115_000L))
        assertEquals("12:03", studioClock(723_400L))
        assertEquals("1:02:09", studioClock(3_729_000L))
        assertEquals("0:00", studioClock(-5L))
        assertEquals("-3:08", studioRemaining(115_000L, 303_000L))
        assertEquals("--:--", studioRemaining(0L, 0L))
        assertEquals("-0:00", studioRemaining(400_000L, 303_000L))
    }
}
