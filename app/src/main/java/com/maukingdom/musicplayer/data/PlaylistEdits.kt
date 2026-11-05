package com.maukingdom.musicplayer.data

/**
 * Pure rules for adding, moving and removing playlist songs, and for undoing
 * those edits. The repository runs them inside one Room transaction; kept
 * free of Room so JVM tests can check them.
 */

/** Rows to insert for an append, and how many requested songs were already there. */
internal data class AppendPlan(
    val entries: List<PlaylistSongEntity>,
    val skipped: Int
)

/**
 * Appends [songIds] to [playlistId] in the given order after [lastPosition].
 * Duplicates in [songIds] count once; songs in [existingIds] are skipped and
 * counted; songs missing from [knownSongIds] (deleted meanwhile) are
 * dropped without counting.
 */
internal fun planAppend(
    playlistId: Long,
    existingIds: Set<String>,
    lastPosition: Long,
    songIds: List<String>,
    knownSongIds: Set<String>
): AppendPlan {
    var next = lastPosition + 1
    var skipped = 0
    val entries = mutableListOf<PlaylistSongEntity>()

    for (songId in songIds.distinct()) {
        when {
            songId !in knownSongIds -> Unit
            songId in existingIds -> skipped++
            else -> entries += PlaylistSongEntity(playlistId, songId, next++)
        }
    }

    return AppendPlan(entries, skipped)
}

/**
 * Puts [songIds] at the top of [playlistId] (before [firstPosition]), in
 * the given order, so the newest additions come first. Same skip and
 * dedupe rules as [planAppend]. Positions may go below zero.
 */
internal fun planPrepend(
    playlistId: Long,
    existingIds: Set<String>,
    firstPosition: Long,
    songIds: List<String>,
    knownSongIds: Set<String>
): AppendPlan {
    val append = planAppend(playlistId, existingIds, lastPosition = -1L, songIds, knownSongIds)
    val count = append.entries.size
    val start = firstPosition - count
    return append.copy(entries = append.entries.mapIndexed { index, entry -> entry.copy(position = start + index) })
}

/** A song taken out of a playlist and its index there, for undo. */
data class RemovedSong(val songId: String, val index: Int)

/** The songs of [songIds] that are in [order], with their indices, in playlist order. */
internal fun planRemoval(order: List<String>, songIds: Collection<String>): List<RemovedSong> {
    val wanted = songIds.toSet()
    return order.mapIndexedNotNull { index, id -> if (id in wanted) RemovedSong(id, index) else null }
}

/**
 * [current] with [removed] put back at their old indices. Re-creates the
 * original order exactly when [current] has not changed since the removal;
 * otherwise each song goes back as close to its old place as possible.
 * Songs already back in [current] are not duplicated.
 */
internal fun restoreOrder(current: List<String>, removed: List<RemovedSong>): List<String> {
    val present = current.toHashSet()
    val result = current.toMutableList()

    for (song in removed.sortedBy { it.index }) {
        if (song.songId in present) continue
        result.add(song.index.coerceIn(0, result.size), song.songId)
        present += song.songId
    }

    return result
}

/**
 * Everything one add / move / remove changed, so it can be undone: songs
 * appended to [addedTo], songs taken out of [removedFrom], and a playlist
 * the edit created.
 */
data class PlaylistEdit(
    val addedTo: Long? = null,
    val addedSongIds: List<String> = emptyList(),
    val skipped: Int = 0,
    val removedFrom: Long? = null,
    val removed: List<RemovedSong> = emptyList(),
    val createdPlaylistId: Long? = null
) {
    val addedCount: Int get() = addedSongIds.size
    val removedCount: Int get() = removed.size
    val changedAnything: Boolean get() =
        addedSongIds.isNotEmpty() || removed.isNotEmpty() || createdPlaylistId != null
}
