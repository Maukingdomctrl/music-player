package com.maukingdom.musicplayer.ui.v2.playlistpicker

import androidx.compose.runtime.Immutable
import com.maukingdom.musicplayer.data.PlaylistEdit
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel
import com.maukingdom.musicplayer.ui.v2.library.playlistMetaText
import com.maukingdom.musicplayer.ui.v2.queue.QueueUndo

/** Pure rules for the "Add to playlist" / "Move to playlist" sheet. JVM-tested. */

/** Above this many playlists the sheet shows a search field. */
const val PICKER_SEARCH_THRESHOLD = 8

enum class PickerMode { ADD, MOVE }

enum class PlaylistEditKind { ADD, MOVE, REMOVE }

/**
 * What the sheet was opened for: [songIds] in the order they are listed,
 * and for [PickerMode.MOVE] the playlist they leave ([sourcePlaylistId]).
 */
@Immutable
data class PickerRequest(
    val mode: PickerMode,
    val songIds: List<String>,
    val sourcePlaylistId: Long? = null
) {
    init {
        require(mode == PickerMode.ADD || sourcePlaylistId != null) { "Move needs a source playlist." }
    }

    val isSingleSong: Boolean get() = songIds.size == 1
}

/** One playlist the sheet lists. [alreadyAdded] only for a single song that is already in it. */
@Immutable
data class PickerRow(
    val id: Long,
    val name: String,
    val isFolder: Boolean,
    val songCount: Int,
    val alreadyAdded: Boolean
) {
    val meta: String get() = playlistMetaText(isFolder, songCount)
}

/** Snackbar text and, when anything changed, the edit to undo. */
data class PlaylistEditResult(val message: String, val edit: PlaylistEdit?)

/** Snackbar text for Play next / Add to queue and, when it can be undone, the undo. */
data class QueueEditResult(val message: String, val undo: QueueUndo?)

/** Songs a "Select files" import added, offered for a playlist. */
@Immutable
data class ImportOffer(val songIds: List<String>) {
    val message: String get() = "${songCountLabel(songIds.size)} added to library"
}

/** The offer for [importedSongIds], or null when nothing was imported. */
fun importOffer(importedSongIds: List<String>): ImportOffer? =
    importedSongIds.distinct().takeIf { it.isNotEmpty() }?.let(::ImportOffer)

fun pickerTitle(mode: PickerMode): String =
    if (mode == PickerMode.MOVE) "Move to playlist" else "Add to playlist"

fun pickerSubtitle(songCount: Int): String = songCountLabel(songCount)

fun showPickerSearch(playlistCount: Int): Boolean = playlistCount > PICKER_SEARCH_THRESHOLD

/**
 * The sheet's playlists, A–Z (case-insensitive, then by id), filtered by
 * [query]. Move mode hides the source playlist. [containing] (playlists
 * that hold the song) marks rows only for a single song.
 */
fun pickerRows(
    request: PickerRequest,
    playlists: List<PlaylistEntity>,
    counts: Map<Long, Int>,
    folderIds: Set<Long>,
    containing: Set<Long>,
    query: String
): List<PickerRow> {
    val needle = query.trim()

    return playlists
        .asSequence()
        .filter { request.mode == PickerMode.ADD || it.id != request.sourcePlaylistId }
        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, PlaylistEntity::name).thenBy { it.id })
        .map { playlist ->
            PickerRow(
                id = playlist.id,
                name = playlist.name,
                isFolder = playlist.id in folderIds,
                songCount = counts[playlist.id] ?: 0,
                alreadyAdded = request.isSingleSong && playlist.id in containing
            )
        }
        .toList()
}

/** TalkBack label for a row: "Add to Hindi New", "Move to …", or "Already in …". */
fun pickerRowDescription(row: PickerRow, mode: PickerMode): String = when {
    row.alreadyAdded -> "Already in ${row.name}"
    mode == PickerMode.MOVE -> "Move to ${row.name}"
    else -> "Add to ${row.name}"
}

/** The one-line warning shown before moving a song out of folder playlist [sourceName]. */
fun moveFromFolderNote(sourceName: String, songCount: Int): String =
    if (songCount == 1) {
        "It won't come back when $sourceName syncs."
    } else {
        "They won't come back when $sourceName syncs."
    }

/** Snackbar text for [edit] on playlist [name]. */
fun playlistEditMessage(kind: PlaylistEditKind, edit: PlaylistEdit, name: String): String {
    val skippedNote = when (edit.skipped) {
        0 -> ""
        1 -> " (1 was already there)"
        else -> " (${edit.skipped} were already there)"
    }

    return when (kind) {
        PlaylistEditKind.ADD -> when {
            edit.addedCount == 0 && edit.skipped == 0 && edit.createdPlaylistId != null -> "Created $name"
            edit.addedCount == 0 && edit.skipped == 1 -> "Already in $name"
            edit.addedCount == 0 -> "All ${edit.skipped} songs were already in $name"
            edit.addedCount == 1 && edit.skipped == 0 -> "Added to $name"
            else -> "Added ${songCountLabel(edit.addedCount)} to $name$skippedNote"
        }

        PlaylistEditKind.MOVE -> when {
            edit.removedCount == 0 -> "Nothing to move"
            edit.removedCount == 1 && edit.skipped == 0 -> "Moved to $name"
            else -> "Moved ${songCountLabel(edit.removedCount)} to $name$skippedNote"
        }

        PlaylistEditKind.REMOVE -> when (edit.removedCount) {
            0 -> "Nothing to remove"
            else -> "Removed ${songCountLabel(edit.removedCount)} from $name"
        }
    }
}

/** [selected] with [songId] added or taken out, keeping list order. */
fun toggleSelection(selected: List<String>, songId: String): List<String> =
    if (songId in selected) selected - songId else selected + songId

/** The selected songs in the order they are listed ([listed]). */
fun selectionInListOrder(listed: List<String>, selected: Collection<String>): List<String> {
    val set = selected.toHashSet()
    return listed.filter { it in set }
}

/** Selected ids that are still listed (songs can leave the list while selected). */
fun pruneSelection(selected: List<String>, listed: Collection<String>): List<String> {
    val set = listed.toHashSet()
    return selected.filter { it in set }
}

fun selectionTitle(count: Int): String = "$count selected"
