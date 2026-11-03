package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.SnapshotCompat
import com.maukingdom.musicplayer.playback.PlaybackRecovery
import com.maukingdom.musicplayer.playback.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {

    // Media3 PlaybackException codes.
    private val fileNotFound = 2005
    private val noPermission = 2006
    private val ioUnspecified = 2000
    private val containerMalformed = 3001
    private val containerUnsupported = 3003
    private val formatUnsupported = 4005
    private val decoderInitFailed = 4001
    private val unspecified = 1000
    private val audioTrackInitFailed = 5001

    @Test
    fun sourceErrorsAreRecognised() {
        for (code in listOf(ioUnspecified, fileNotFound, noPermission, containerMalformed,
            containerUnsupported, formatUnsupported, 4003)) {
            assertTrue("code $code", PlaybackRecovery.isSourceError(code))
        }

        for (code in listOf(unspecified, decoderInitFailed, audioTrackInitFailed, 6000, 7000)) {
            assertFalse("code $code", PlaybackRecovery.isSourceError(code))
        }
    }

    @Test
    fun sourceErrorWithANextSongSkips() {
        val decision = PlaybackRecovery.decide(fileNotFound, hasNext = true, consecutiveSkips = 0)

        assertEquals(RecoveryAction.SKIP_TO_NEXT, decision.action)
        assertEquals(1, decision.consecutiveSkips)
    }

    @Test
    fun lastSongStops() {
        val decision = PlaybackRecovery.decide(fileNotFound, hasNext = false, consecutiveSkips = 2)

        assertEquals(RecoveryAction.STOP, decision.action)
        assertEquals(2, decision.consecutiveSkips)
    }

    @Test
    fun otherErrorsAreLeftAlone() {
        val decision = PlaybackRecovery.decide(audioTrackInitFailed, hasNext = true, consecutiveSkips = 3)

        assertEquals(RecoveryAction.IGNORE, decision.action)
        assertEquals(3, decision.consecutiveSkips)
    }

    @Test
    fun givesUpAfterFiveSkipsInARow() {
        var skips = 0
        val actions = mutableListOf<RecoveryAction>()

        repeat(7) {
            val decision = PlaybackRecovery.decide(fileNotFound, hasNext = true, consecutiveSkips = skips)
            actions += decision.action
            skips = decision.consecutiveSkips
        }

        assertEquals(
            List(5) { RecoveryAction.SKIP_TO_NEXT } +
                RecoveryAction.GIVE_UP +
                RecoveryAction.SKIP_TO_NEXT, // counting starts again after giving up
            actions
        )
        assertEquals(PlaybackRecovery.MAX_CONSECUTIVE_SKIPS, 5)
    }

    @Test
    fun giveUpWinsEvenOnTheLastSong() {
        val decision = PlaybackRecovery.decide(noPermission, hasNext = false, consecutiveSkips = 5)
        assertEquals(RecoveryAction.GIVE_UP, decision.action)
        assertEquals(0, decision.consecutiveSkips)
    }

    @Test
    fun playingMoreThanTwoSecondsResetsTheCount() {
        assertEquals(0, PlaybackRecovery.skipsAfterPlaying(4, 2_001L))
        assertEquals(4, PlaybackRecovery.skipsAfterPlaying(4, 2_000L))
        assertEquals(4, PlaybackRecovery.skipsAfterPlaying(4, 500L))
    }

    @Test
    fun restoreChecksOnlyTheFirstSongAndTwoAfterIt() {
        assertEquals(setOf(10, 11, 12), PlaybackRecovery.restoreCheckIndices(2_000, 10))
        assertEquals(setOf(0, 1, 2), PlaybackRecovery.restoreCheckIndices(5, 0))
        assertEquals(setOf(3, 4), PlaybackRecovery.restoreCheckIndices(5, 3))
        assertEquals(setOf(4), PlaybackRecovery.restoreCheckIndices(5, 4))
        assertEquals(setOf(0), PlaybackRecovery.restoreCheckIndices(1, 0))
        // Out-of-range saved indexes are clamped like the restore does.
        assertEquals(setOf(4), PlaybackRecovery.restoreCheckIndices(5, 99))
        assertEquals(setOf(0, 1, 2), PlaybackRecovery.restoreCheckIndices(5, -3))
        assertEquals(emptySet<Int>(), PlaybackRecovery.restoreCheckIndices(0, 0))
    }

    @Test
    fun oldSnapshotsAreReadUntilTheQueueFileIsWritten() {
        val legacy = """["content://a","content://b"]"""
        val current = """["content://c"]"""

        // Upgrading from the previous version: only the old key exists.
        assertEquals(legacy, SnapshotCompat.pick(queueFileValue = null, legacyValue = legacy))
        // After the first queue write, the queue file wins.
        assertEquals(current, SnapshotCompat.pick(queueFileValue = current, legacyValue = legacy))
        assertEquals(current, SnapshotCompat.pick(queueFileValue = current, legacyValue = null))
        // An explicitly cleared queue stays cleared.
        assertEquals("[]", SnapshotCompat.pick(queueFileValue = "[]", legacyValue = legacy))
        // Fresh install.
        assertNull(SnapshotCompat.pick(null, null))
    }
}
