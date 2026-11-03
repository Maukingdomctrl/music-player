package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.formatPlaybackTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataFormattingTest {

    @Test
    fun formatsZeroAsMinutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0L))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("3:05", formatPlaybackTime(185_000L))
    }

    @Test
    fun formatsHoursMinutesAndSeconds() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }

    @Test
    fun clampsNegativeDurationToZero() {
        assertEquals("0:00", formatPlaybackTime(-1_000L))
    }

    @Test
    fun ignoresFractionalSeconds() {
        assertEquals("0:01", formatPlaybackTime(1_999L))
    }
}
