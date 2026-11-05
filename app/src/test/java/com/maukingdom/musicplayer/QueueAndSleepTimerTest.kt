package com.maukingdom.musicplayer

import androidx.media3.common.C
import androidx.media3.common.Player
import com.maukingdom.musicplayer.playback.SleepTimerMath
import com.maukingdom.musicplayer.playback.SleepTimerState
import com.maukingdom.musicplayer.ui.PlaybackUiState
import com.maukingdom.musicplayer.ui.v2.home.continueUi
import com.maukingdom.musicplayer.ui.v2.queue.QueueOrigin
import com.maukingdom.musicplayer.ui.v2.queue.QueueUndo
import com.maukingdom.musicplayer.ui.v2.queue.ResolvedOrigin
import com.maukingdom.musicplayer.ui.v2.queue.canUndoInsert
import com.maukingdom.musicplayer.ui.v2.queue.continueSubtitle
import com.maukingdom.musicplayer.ui.v2.queue.formatShortClock
import com.maukingdom.musicplayer.ui.v2.queue.insertAt
import com.maukingdom.musicplayer.ui.v2.queue.keepOnly
import com.maukingdom.musicplayer.ui.v2.queue.moveItem
import com.maukingdom.musicplayer.ui.v2.queue.playNextIndex
import com.maukingdom.musicplayer.ui.v2.queue.playingFromCaption
import com.maukingdom.musicplayer.ui.v2.queue.queueAddMessage
import com.maukingdom.musicplayer.ui.v2.queue.queueFingerprint
import com.maukingdom.musicplayer.ui.v2.queue.queueKeys
import com.maukingdom.musicplayer.ui.v2.queue.removeAt
import com.maukingdom.musicplayer.ui.v2.queue.resolveOrigin
import com.maukingdom.musicplayer.ui.v2.queue.upcomingIndices
import com.maukingdom.musicplayer.ui.v2.studio.sleepChipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAndSleepTimerTest {

    private val queue = listOf("a", "b", "c", "d")

    // Queue origin / caption

    @Test
    fun originDescribesTheQueueOnlyWhileItStillMatches() {
        val origin = QueueOrigin(playlistId = 7L, edited = false, fingerprint = queueFingerprint(queue))

        assertEquals(ResolvedOrigin(7L, false), resolveOrigin(origin, queue))
        // Same songs, different order (or replaced by Android Auto): the library.
        assertEquals(ResolvedOrigin.Library, resolveOrigin(origin, listOf("b", "a", "c", "d")))
        assertEquals(ResolvedOrigin.Library, resolveOrigin(origin, emptyList()))
        assertEquals(ResolvedOrigin.Library, resolveOrigin(null, queue))
    }

    @Test
    fun anEditedQueueShowsPlayingFromQueue() {
        val edited = insertAt(queue, 1, listOf("x"))
        val origin = QueueOrigin(playlistId = null, edited = true, fingerprint = queueFingerprint(edited))

        val resolved = resolveOrigin(origin, edited)
        assertEquals(ResolvedOrigin(null, true), resolved)
        assertEquals("PLAYING FROM" to "QUEUE", playingFromCaption("Hindi 90s", resolved.edited))
        assertEquals("PLAYING FROM PLAYLIST" to "Hindi 90s", playingFromCaption("Hindi 90s", false))
        assertEquals("PLAYING FROM" to "YOUR LIBRARY", playingFromCaption(null, false))
    }

    @Test
    fun fingerprintIsOrderSensitive() {
        assertNotEquals(queueFingerprint(listOf("a", "b")), queueFingerprint(listOf("b", "a")))
        assertEquals(queueFingerprint(listOf("a", "b")), queueFingerprint(listOf("a", "b")))
    }

    // Queue edits

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        assertEquals(2, playNextIndex(currentIndex = 1, queueSize = 4))
        assertEquals(4, playNextIndex(currentIndex = 3, queueSize = 4))
        assertEquals(0, playNextIndex(currentIndex = 0, queueSize = 0))
        assertEquals(listOf("a", "b", "x", "y", "c", "d"), insertAt(queue, 2, listOf("x", "y")))
    }

    @Test
    fun moveRemoveAndClearUpcomingMirrorThePlayer() {
        // Media3 moveMediaItem(from, to): the item ends up at index [to].
        assertEquals(listOf("a", "c", "d", "b"), moveItem(queue, 1, 3))
        assertEquals(listOf("d", "a", "b", "c"), moveItem(queue, 3, 0))
        assertEquals(queue, moveItem(queue, 1, 9))
        assertEquals(listOf("a", "c", "d"), removeAt(queue, 1))
        assertEquals(listOf("c"), keepOnly(queue, 2))
    }

    @Test
    fun undoOnlyWhileTheInsertedSongsAreStillInPlace() {
        val undo = QueueUndo(index = 2, songIds = listOf("x", "y"), previousOrigin = null)

        assertTrue(canUndoInsert(listOf("a", "b", "x", "y", "c"), undo))
        assertFalse(canUndoInsert(listOf("a", "x", "b", "y", "c"), undo))
        assertFalse(canUndoInsert(listOf("a", "b", "x"), undo))
    }

    @Test
    fun snackbarText() {
        assertEquals("Playing next", queueAddMessage(playNext = true, count = 1))
        assertEquals("Added to queue", queueAddMessage(playNext = false, count = 1))
        assertEquals("3 songs playing next", queueAddMessage(playNext = true, count = 3))
        assertEquals("Added 3 songs to queue", queueAddMessage(playNext = false, count = 3))
    }

    @Test
    fun upcomingFollowsPlayOrderWithoutWrapping() {
        // In order.
        assertEquals(listOf(2, 3), upcomingIndices(1, 4) { (it + 1).takeIf { n -> n < 4 } })
        // A shuffle order 2 -> 0 -> 3 -> 1 starting at 0.
        val next = mapOf(2 to 0, 0 to 3, 3 to 1)
        assertEquals(listOf(3, 1), upcomingIndices(0, 4) { next[it] })
        // A broken order that loops never hangs.
        assertEquals(listOf(1), upcomingIndices(0, 2) { if (it == 0) 1 else 0 })
    }

    @Test
    fun keysStayUniqueForRepeatedSongs() {
        assertEquals(listOf("a#0", "b#0", "a#1"), queueKeys(listOf("a", "b", "a")))
    }

    // Continue listening

    @Test
    fun continueSubtitleShowsSourceAndTimeLeft() {
        assertEquals("From Hindi 90s · 2:14 left", continueSubtitle("Hindi 90s", false, 60_000L, 194_000L))
        assertEquals("From your queue · 0:05 left", continueSubtitle("Hindi 90s", true, 1_000L, 6_000L))
        assertEquals("From your library", continueSubtitle(null, false, 0L, 0L))
        assertEquals("1:02:03", formatShortClock(3_723_000L))
    }

    @Test
    fun continueCardOnlyWhenPausedWithAQueue() {
        val paused = PlaybackUiState(
            connected = true, title = "Tum Hi Ho", currentSongId = "s1", queueSize = 3,
            isPlaying = false, positionMs = 50_000L, durationMs = 200_000L
        )

        val card = continueUi(paused, "Hindi 90s", false)!!
        assertEquals("Tum Hi Ho", card.title)
        assertEquals("From Hindi 90s · 2:30 left", card.subtitle)
        assertEquals(0.25f, card.progress, 0.0001f)

        assertNull(continueUi(paused.copy(isPlaying = true), "Hindi 90s", false))
        assertNull(continueUi(paused.copy(queueSize = 0), "Hindi 90s", false))
        assertNull(continueUi(paused.copy(connected = false), "Hindi 90s", false))
    }

    // Sleep timer

    @Test
    fun remainingTimeFormat() {
        assertEquals("23:41 left", SleepTimerMath.formatRemaining(23 * 60_000L + 41_000L))
        // Rounds up, so a running timer never shows 0:00.
        assertEquals("0:01 left", SleepTimerMath.formatRemaining(1L))
        assertEquals("60:00 left", SleepTimerMath.formatRemaining(60 * 60_000L))
        assertEquals("0:00 left", SleepTimerMath.formatRemaining(-5L))
    }

    @Test
    fun fadeCurveIsGentleAndEndsSilent() {
        assertEquals(1f, SleepTimerMath.fadeVolume(20_000L), 0f)
        assertEquals(1f, SleepTimerMath.fadeVolume(10_000L), 0f)
        assertEquals(0.25f, SleepTimerMath.fadeVolume(5_000L), 0.0001f)
        assertEquals(0f, SleepTimerMath.fadeVolume(0L), 0f)
        // Always going down as time runs out.
        val samples = (10 downTo 0).map { SleepTimerMath.fadeVolume(it * 1_000L) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun endOfSongMode() {
        assertEquals(SleepTimerState.EndOfSong, SleepTimerMath.stateFor(SleepTimerMath.ARG_END_OF_SONG, 0L))
        assertTrue(SleepTimerMath.songFinished(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertTrue(SleepTimerMath.songFinished(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        // Skipping to the next song is not the song ending.
        assertFalse(SleepTimerMath.songFinished(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertEquals(4_000L, SleepTimerMath.songRemaining(200_000L, 196_000L))
        assertNull(SleepTimerMath.songRemaining(C.TIME_UNSET, 0L))
        assertEquals("End of song", sleepChipText(SleepTimerState.EndOfSong, 0L))
    }

    @Test
    fun countdownStateAndChip() {
        val state = SleepTimerMath.stateFor(15, nowElapsedMs = 1_000L)
        assertEquals(SleepTimerState.Countdown(15, 1_000L + 15 * 60_000L), state)
        assertEquals("15:00 left", sleepChipText(state, 1_000L))
        assertEquals(SleepTimerState.Off, SleepTimerMath.stateFor(SleepTimerMath.ARG_OFF, 0L))
        assertNull(sleepChipText(SleepTimerState.Off, 0L))
    }

    @Test
    fun checksAreRareBeforeTheFadeAndFrequentDuring() {
        assertEquals(1_000L, SleepTimerMath.nextCheckDelay(5 * 60_000L))
        assertEquals(100L, SleepTimerMath.nextCheckDelay(9_000L))
        assertEquals(500L, SleepTimerMath.nextCheckDelay(10_500L))
    }
}
