package com.maukingdom.musicplayer.ui

import com.maukingdom.musicplayer.data.FolderAccessException
import com.maukingdom.musicplayer.data.FolderAccessReason
import com.maukingdom.musicplayer.data.FolderImportResult
import com.maukingdom.musicplayer.data.ImportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FolderOperationKind {
    IMPORT,
    SYNC
}

enum class FolderFailureReason {
    PERMISSION_MISSING,
    FOLDER_UNAVAILABLE,
    NOT_A_FOLDER,
    PROVIDER_LOADING,
    PROVIDER_ERROR,
    NOT_LINKED,
    UNKNOWN
}

data class FolderOperationSummary(
    val playlistId: Long,
    val scannedAudioFiles: Int,
    val addedToPlaylist: Int,
    val newSongsInserted: Int,
    val existingSongsReused: Int,
    val alreadyKnown: Int,
    val alreadyInPlaylist: Int,
    val failures: List<ImportFailure>
)

sealed interface FolderOperationState {
    data object Idle : FolderOperationState

    data class Running(
        val kind: FolderOperationKind,
        val playlistId: Long?
    ) : FolderOperationState

    data class Succeeded(
        val kind: FolderOperationKind,
        val summary: FolderOperationSummary
    ) : FolderOperationState

    data class Failed(
        val kind: FolderOperationKind,
        val playlistId: Long?,
        val reason: FolderFailureReason,
        val message: String
    ) : FolderOperationState
}

/**
 * Runs one folder import or sync at a time and exposes its outcome.
 *
 * Plain Kotlin so it can be unit-tested on the JVM. [isBusy] and [setBusy]
 * share the "a library operation is running" flag with the caller, so folder
 * operations and other imports never overlap. An empty but readable folder
 * is a success with zero counts; errors are never turned into empty results.
 */
class FolderSyncController(
    private val scope: CoroutineScope,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit
) {
    private val mutableState =
        MutableStateFlow<FolderOperationState>(FolderOperationState.Idle)
    val state: StateFlow<FolderOperationState> = mutableState.asStateFlow()

    /**
     * Starts [operation] unless another operation is running. Returns false,
     * without side effects, when it refuses to start.
     */
    fun run(
        kind: FolderOperationKind,
        playlistId: Long?,
        operation: suspend () -> FolderImportResult
    ): Boolean {
        if (isBusy() || mutableState.value is FolderOperationState.Running) {
            return false
        }

        val running = FolderOperationState.Running(kind, playlistId)
        setBusy(true)
        mutableState.value = running

        val job = scope.launch {
            mutableState.value = try {
                FolderOperationState.Succeeded(kind, operation().toSummary())
            } catch (error: CancellationException) {
                // Must come first: CancellationException is an
                // IllegalStateException. Cancellation is never reported.
                throw error
            } catch (error: Exception) {
                error.toFailed(kind, playlistId)
            }
        }

        // Also runs if the scope was cancelled before the job started.
        job.invokeOnCompletion {
            mutableState.compareAndSet(running, FolderOperationState.Idle)
            setBusy(false)
        }

        return true
    }

    /** Returns to [FolderOperationState.Idle] unless an operation is running. */
    fun clear() {
        if (mutableState.value !is FolderOperationState.Running) {
            mutableState.value = FolderOperationState.Idle
        }
    }
}

internal fun FolderImportResult.toSummary(): FolderOperationSummary {
    return FolderOperationSummary(
        playlistId = sync.playlistId,
        scannedAudioFiles = scannedAudioFiles,
        addedToPlaylist = sync.addedToPlaylist,
        newSongsInserted = sync.newSongsInserted,
        existingSongsReused = sync.existingSongsReused,
        alreadyKnown = sync.skippedAlreadyKnown,
        alreadyInPlaylist = sync.alreadyInPlaylist,
        failures = failures
    )
}

private fun Exception.toFailed(
    kind: FolderOperationKind,
    playlistId: Long?
): FolderOperationState.Failed {
    val reason = when {
        this is FolderAccessException -> reason.toFailureReason()
        this is IllegalStateException &&
            message?.contains("not linked", ignoreCase = true) == true ->
            FolderFailureReason.NOT_LINKED
        else -> FolderFailureReason.UNKNOWN
    }

    return FolderOperationState.Failed(
        kind = kind,
        playlistId = playlistId,
        reason = reason,
        message = message?.takeIf { it.isNotBlank() }
            ?: "The folder operation could not be completed."
    )
}

private fun FolderAccessReason.toFailureReason(): FolderFailureReason {
    return when (this) {
        FolderAccessReason.PERMISSION_MISSING -> FolderFailureReason.PERMISSION_MISSING
        FolderAccessReason.FOLDER_UNAVAILABLE -> FolderFailureReason.FOLDER_UNAVAILABLE
        FolderAccessReason.NOT_A_FOLDER -> FolderFailureReason.NOT_A_FOLDER
        FolderAccessReason.PROVIDER_LOADING -> FolderFailureReason.PROVIDER_LOADING
        FolderAccessReason.PROVIDER_ERROR -> FolderFailureReason.PROVIDER_ERROR
    }
}

/**
 * Short banner text, e.g. "Hindi 90s: 3 new songs added, 1 file could not be read".
 */
internal fun folderSummaryText(
    playlistName: String?,
    summary: FolderOperationSummary
): String {
    val name = playlistName?.trim()?.takeIf { it.isNotEmpty() } ?: "Folder playlist"

    val added = when (val count = summary.addedToPlaylist) {
        0 -> "no new songs"
        1 -> "1 new song added"
        else -> "$count new songs added"
    }

    val failed = when (val count = summary.failures.size) {
        0 -> ""
        1 -> ", 1 file could not be read"
        else -> ", $count files could not be read"
    }

    return "$name: $added$failed"
}
