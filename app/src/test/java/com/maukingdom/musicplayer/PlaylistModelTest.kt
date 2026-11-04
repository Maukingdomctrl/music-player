package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.v2.contrastRatio
import com.maukingdom.musicplayer.ui.v2.playlist.NEVER_SYNCED
import com.maukingdom.musicplayer.ui.v2.playlist.WASH_MAX_LUMINANCE
import com.maukingdom.musicplayer.ui.v2.playlist.formatTotalDuration
import com.maukingdom.musicplayer.ui.v2.playlist.moveSong
import com.maukingdom.musicplayer.ui.v2.playlist.playlistHeaderMeta
import com.maukingdom.musicplayer.ui.v2.playlist.playlistWashColor
import com.maukingdom.musicplayer.ui.v2.playlist.playlistWashFallback
import com.maukingdom.musicplayer.ui.v2.playlist.playlistWashMid
import com.maukingdom.musicplayer.ui.v2.playlist.syncedLabel
import com.maukingdom.musicplayer.ui.v2.relativeLuminance
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistModelTest {

    private val min = 60_000L

    @Test
    fun totalDurationFormat() {
        assertEquals("3 h 05 min", formatTotalDuration(listOf(60 * min, 60 * min, 65 * min)))
        assertEquals("45 min", formatTotalDuration(listOf(20 * min, 25 * min)))
        assertEquals("1 min", formatTotalDuration(listOf(60_000L)))
        assertEquals("1 min", formatTotalDuration(listOf(10_000L)))
        assertEquals("0 min", formatTotalDuration(emptyList()))
        assertEquals("0 min", formatTotalDuration(listOf(0L, -5_000L)))
        // Negative (unknown) durations are ignored, not subtracted.
        assertEquals("4 min", formatTotalDuration(listOf(4 * min, -10 * min)))
        assertEquals("1 h 00 min", formatTotalDuration(listOf(60 * min)))
    }

    @Test
    fun headerMeta() {
        assertEquals("SD folder · 2 songs · 45 min", playlistHeaderMeta(true, listOf(20 * min, 25 * min)))
        assertEquals("Playlist · 1 song · 4 min", playlistHeaderMeta(false, listOf(4 * min)))
        assertEquals("Playlist · 0 songs", playlistHeaderMeta(false, emptyList()))
    }

    private val zone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    private fun label(synced: Long?, now: Long) = syncedLabel(synced, now, zone, Locale.UK)

    @Test
    fun syncedLabels() {
        val now = at(2026, Calendar.NOVEMBER, 4, 15, 0)
        assertEquals(NEVER_SYNCED, label(null, now))
        assertEquals("Synced just now", label(now - 30_000L, now))
        // A sync "in the future" after a clock change is just now.
        assertEquals("Synced just now", label(now + 5 * min, now))
        assertEquals("Synced 5 min ago", label(now - 5 * min, now))
        assertEquals("Synced 59 min ago", label(now - 59 * min, now))
        assertEquals("Synced 2 h ago", label(at(2026, Calendar.NOVEMBER, 4, 12, 30), now))
        assertEquals("Synced yesterday", label(at(2026, Calendar.NOVEMBER, 3, 9, 0), now))
        assertEquals("Synced 12 Oct", label(at(2026, Calendar.OCTOBER, 12, 18, 0), now))
        assertEquals("Synced 12 Oct 2025", label(at(2025, Calendar.OCTOBER, 12, 18, 0), now))
    }

    @Test
    fun syncedAcrossMidnightUnderAnHourIsMinutes() {
        val now = at(2026, Calendar.NOVEMBER, 4, 0, 10)
        assertEquals("Synced 20 min ago", label(at(2026, Calendar.NOVEMBER, 3, 23, 50), now))
        assertEquals("Synced yesterday", label(at(2026, Calendar.NOVEMBER, 3, 22, 0), now))
    }

    @Test
    fun moveUpDownAndBounds() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveSong(ids, 1, -1))
        assertEquals(listOf("a", "c", "b"), moveSong(ids, 1, 1))
        assertEquals(listOf("b", "a", "c"), moveSong(ids, 0, 1))
        assertNull(moveSong(ids, 0, -1))
        assertNull(moveSong(ids, 2, 1))
        assertNull(moveSong(ids, 5, -1))
        assertNull(moveSong(emptyList(), 0, 1))
    }

    private val bg = 0xFF0B0B0F.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun washIsReadableForWhiteBlackAndOrange() {
        for ((name, argb) in mapOf(
            "white" to 0xFFFFFFFF.toInt(),
            "black" to 0xFF000000.toInt(),
            "orange" to 0xFFFF8A3D.toInt(),
            "yellow" to 0xFFFFFF00.toInt()
        )) {
            val wash = playlistWashColor(argb, bg)
            assertTrue("$name luminance", relativeLuminance(wash) <= WASH_MAX_LUMINANCE + 1e-6)
            assertEquals("$name opaque", 0xFF, wash ushr 24)
            // Header text starts at the darker middle stop.
            val mid = playlistWashMid(wash, bg)
            assertTrue("$name mid contrast", contrastRatio(white, mid) >= 4.5f)
        }
    }

    @Test
    fun orangeWashStaysVisiblyOrange() {
        val wash = playlistWashColor(0xFFFF8A3D.toInt(), bg)
        val r = (wash shr 16) and 0xFF
        val g = (wash shr 8) and 0xFF
        val b = wash and 0xFF
        assertTrue("red leads", r > g && g > b)
        assertTrue("not grey", r - b > 60)
    }

    @Test
    fun blackCoverWashIsLighterThanBackground() {
        assertTrue(relativeLuminance(playlistWashColor(0xFF000000.toInt(), bg)) > relativeLuminance(bg))
    }

    @Test
    fun fallbackMixesAccentIntoBackground() {
        val fallback = playlistWashFallback(0xFFFF8A3D.toInt(), bg)
        assertTrue(relativeLuminance(fallback) > relativeLuminance(bg))
        assertTrue(relativeLuminance(fallback) < relativeLuminance(0xFFFF8A3D.toInt()))
    }
}
