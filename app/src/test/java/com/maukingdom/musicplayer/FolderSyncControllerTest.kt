package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.FolderAccessException
import com.maukingdom.musicplayer.data.FolderAccessReason
import com.maukingdom.musicplayer.data.FolderImportResult
import com.maukingdom.musicplayer.data.FolderSyncResult
import com.maukingdom.musicplayer.data.ImportFailure
import com.maukingdom.musicplayer.ui.FolderFailureReason
import com.maukingdom.musicplayer.ui.FolderOperationKind
import com.maukingdom.musicplayer.ui.FolderOperationState
import com.maukingdom.musicplayer.ui.FolderOperationSummary
import com.maukingdom.musicplayer.ui.FolderSyncController
import com.maukingdom.musicplayer.ui.folderSummaryText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderSyncControllerTest {

    /** Controller wired to a fake busy flag, recording every setBusy call. */
    private class Harness(testScope: TestScope, busyInitially: Boolean = false) {
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScope.testScheduler)
        )
        var busy = busyInitially
        val busyCalls = mutableListOf<Boolean>()

        val controller = FolderSyncController(
            scope = scope,
            isBusy = { busy },
            setBusy = { value ->
                busy = value
                busyCalls += value
            }
        )
    }

    // 1
    @Test
    fun successMapsAllCountsIntoSummary() = runTest {
        val harness = Harness(this)
        val failures = listOf(ImportFailure("broken.mp3", "Could not read audio."))

        assertTrue(
            harness.controller.run(FolderOperationKind.SYNC, 42L) {
                FolderImportResult(
                    sync = FolderSyncResult(
                        playlistId = 42L,
                        addedToPlaylist = 3,
                        skippedAlreadyKnown = 5,
                        newSongsInserted = 2,
                        existingSongsReused = 1,
                        alreadyInPlaylist = 4
                    ),
                    scannedAudioFiles = 9,
                    failures = failures
                )
            }
        )
        advanceUntilIdle()

        assertEquals(
            FolderOperationState.Succeeded(
                kind = FolderOperationKind.SYNC,
                summary = FolderOperationSummary(
                    playlistId = 42L,
                    scannedAudioFiles = 9,
                    addedToPlaylist = 3,
                    newSongsInserted = 2,
                    existingSongsReused = 1,
                    alreadyKnown = 5,
                    alreadyInPlaylist = 4,
                    failures = failures
                )
            ),
            harness.controller.state.value
        )
    }

    // 2
    @Test
    fun emptyFolderIsSuccessWithZeroCounts() = runTest {
        val harness = Harness(this)

        harness.controller.run(FolderOperationKind.IMPORT, null) {
            FolderImportResult(
                sync = FolderSyncResult(7L, 0, 0, 0, 0, 0),
                scannedAudioFiles = 0,
                failures = emptyList()
            )
        }
        advanceUntilIdle()

        assertEquals(
            FolderOperationState.Succeeded(
                FolderOperationKind.IMPORT,
                FolderOperationSummary(7L, 0, 0, 0, 0, 0, 0, emptyList())
            ),
            harness.controller.state.value
        )
    }

    // 3
    @Test
    fun eachAccessReasonMapsToMatchingFailureReason() = runTest {
        val expected = mapOf(
            FolderAccessReason.PERMISSION_MISSING to FolderFailureReason.PERMISSION_MISSING,
            FolderAccessReason.FOLDER_UNAVAILABLE to FolderFailureReason.FOLDER_UNAVAILABLE,
            FolderAccessReason.NOT_A_FOLDER to FolderFailureReason.NOT_A_FOLDER,
            FolderAccessReason.PROVIDER_LOADING to FolderFailureReason.PROVIDER_LOADING,
            FolderAccessReason.PROVIDER_ERROR to FolderFailureReason.PROVIDER_ERROR
        )
        assertEquals(FolderAccessReason.entries.toSet(), expected.keys)

        for ((accessReason, failureReason) in expected) {
            val harness = Harness(this)
            val message = "Message for $accessReason"

            harness.controller.run(FolderOperationKind.SYNC, 3L) {
                throw FolderAccessException(accessReason, message)
            }
            advanceUntilIdle()

            assertEquals(
                FolderOperationState.Failed(
                    kind = FolderOperationKind.SYNC,
                    playlistId = 3L,
                    reason = failureReason,
                    message = message
                ),
                harness.controller.state.value
            )
        }
    }

    // 4
    @Test
    fun notLinkedIllegalStateMapsToNotLinked() = runTest {
        val harness = Harness(this)

        harness.controller.run(FolderOperationKind.SYNC, 5L) {
            throw IllegalStateException("Playlist is not linked to a folder.")
        }
        advanceUntilIdle()

        assertEquals(
            FolderOperationState.Failed(
                FolderOperationKind.SYNC,
                5L,
                FolderFailureReason.NOT_LINKED,
                "Playlist is not linked to a folder."
            ),
            harness.controller.state.value
        )
    }

    // 5
    @Test
    fun unexpectedExceptionMapsToUnknown() = runTest {
        val harness = Harness(this)

        harness.controller.run(FolderOperationKind.IMPORT, null) {
            throw RuntimeException("Disk on fire")
        }
        advanceUntilIdle()

        assertEquals(
            FolderOperationState.Failed(
                FolderOperationKind.IMPORT,
                null,
                FolderFailureReason.UNKNOWN,
                "Disk on fire"
            ),
            harness.controller.state.value
        )
    }

    // 6
    @Test
    fun secondRunWhileRunningIsRefused() = runTest {
        val harness = Harness(this)
        val gate = CompletableDeferred<Unit>()
        var invocations = 0

        val operation: suspend () -> FolderImportResult = {
            invocations++
            gate.await()
            emptyResult(1L)
        }

        assertTrue(harness.controller.run(FolderOperationKind.SYNC, 1L, operation))
        runCurrent()
        assertEquals(
            FolderOperationState.Running(FolderOperationKind.SYNC, 1L),
            harness.controller.state.value
        )

        // Even with the external flag cleared, the Running state blocks it.
        harness.busy = false
        assertFalse(harness.controller.run(FolderOperationKind.SYNC, 1L, operation))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, invocations)
        assertTrue(harness.controller.state.value is FolderOperationState.Succeeded)
    }

    // 7
    @Test
    fun runWhileBusyIsRefusedWithoutStateChange() = runTest {
        val harness = Harness(this, busyInitially = true)
        var invoked = false

        val started = harness.controller.run(FolderOperationKind.IMPORT, null) {
            invoked = true
            emptyResult(1L)
        }
        advanceUntilIdle()

        assertFalse(started)
        assertFalse(invoked)
        assertEquals(FolderOperationState.Idle, harness.controller.state.value)
        assertTrue(harness.busyCalls.isEmpty())
    }

    // 8
    @Test
    fun busyIsSetAndClearedForSuccessAndFailure() = runTest {
        val success = Harness(this)
        success.controller.run(FolderOperationKind.SYNC, 1L) { emptyResult(1L) }
        assertEquals(listOf(true), success.busyCalls)
        advanceUntilIdle()
        assertEquals(listOf(true, false), success.busyCalls)

        val failure = Harness(this)
        failure.controller.run(FolderOperationKind.SYNC, 1L) {
            throw FolderAccessException(FolderAccessReason.FOLDER_UNAVAILABLE, "Gone")
        }
        assertEquals(listOf(true), failure.busyCalls)
        advanceUntilIdle()
        assertEquals(listOf(true, false), failure.busyCalls)
    }

    // 9
    @Test
    fun cancellationIsNotReportedAsFailure() = runTest {
        val harness = Harness(this)
        val seen = mutableListOf<FolderOperationState>()

        harness.controller.run(FolderOperationKind.SYNC, 1L) { awaitCancellation() }
        runCurrent()
        seen += harness.controller.state.value

        harness.scope.cancel()
        advanceUntilIdle()
        seen += harness.controller.state.value

        assertTrue(seen.none { it is FolderOperationState.Failed })
        assertEquals(FolderOperationState.Idle, harness.controller.state.value)
        assertEquals(listOf(true, false), harness.busyCalls)

        // A cancelled scope that never starts the job also releases the guard.
        val notStarted = Harness(this)
        notStarted.scope.cancel()
        notStarted.controller.run(FolderOperationKind.IMPORT, null) { emptyResult(1L) }
        advanceUntilIdle()

        assertEquals(FolderOperationState.Idle, notStarted.controller.state.value)
        assertEquals(listOf(true, false), notStarted.busyCalls)
    }

    @Test
    fun summaryTextUsesPlaylistNameAndCounts() {
        val summary = FolderOperationSummary(
            playlistId = 1L,
            scannedAudioFiles = 4,
            addedToPlaylist = 3,
            newSongsInserted = 3,
            existingSongsReused = 0,
            alreadyKnown = 0,
            alreadyInPlaylist = 0,
            failures = listOf(ImportFailure("x.mp3", "bad"))
        )

        assertEquals(
            "Hindi 90s: 3 new songs added, 1 file could not be read",
            folderSummaryText("Hindi 90s", summary)
        )
        assertEquals(
            "Folder playlist: no new songs",
            folderSummaryText(null, summary.copy(addedToPlaylist = 0, failures = emptyList()))
        )
    }

    private fun emptyResult(playlistId: Long) = FolderImportResult(
        sync = FolderSyncResult(playlistId, 0, 0, 0, 0, 0),
        scannedAudioFiles = 0,
        failures = emptyList()
    )
}
