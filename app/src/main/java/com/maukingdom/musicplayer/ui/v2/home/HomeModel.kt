package com.maukingdom.musicplayer.ui.v2.home

import androidx.compose.runtime.Immutable
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity

/** A 56 dp quick tile: one playlist. */
@Immutable
data class QuickTile(
    val playlistId: Long,
    val name: String
)

/** A 140 dp card for a playlist linked to an SD-card folder. */
@Immutable
data class FolderCard(
    val playlistId: Long,
    val name: String,
    val songCount: Int
)

/** Everything the Home screen shows. Built by [buildHomeContent]. */
@Immutable
data class HomeContent(
    val quickTiles: List<QuickTile>,
    val folders: List<FolderCard>,
    val recentlyAdded: List<SongEntity>
) {
    val isEmpty: Boolean
        get() = quickTiles.isEmpty() && folders.isEmpty() && recentlyAdded.isEmpty()
}

const val QUICK_TILE_COUNT = 6
const val RECENTLY_ADDED_COUNT = 12

/**
 * Builds the Home sections from library data. Pure, so it is unit tested.
 *
 * - Quick tiles: the "top" playlists. There is no play history, so top
 *   means: non-empty playlists, most songs first, then by name.
 * - Folders: every SD-card-linked playlist (empty ones too, so a folder
 *   that was just linked shows up), by name.
 * - Recently added: newest songs first.
 */
fun buildHomeContent(
    playlists: List<PlaylistEntity>,
    songCounts: Map<Long, Int>,
    folderPlaylistIds: Set<Long>,
    songs: List<SongEntity>
): HomeContent {
    val byName = compareBy<PlaylistEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.id }

    val quickTiles = playlists
        .filter { (songCounts[it.id] ?: 0) > 0 }
        .sortedWith(
            compareByDescending<PlaylistEntity> { songCounts[it.id] ?: 0 }.then(byName)
        )
        .take(QUICK_TILE_COUNT)
        .map { QuickTile(playlistId = it.id, name = it.name) }

    val folders = playlists
        .filter { it.id in folderPlaylistIds }
        .sortedWith(byName)
        .map { FolderCard(it.id, it.name, songCounts[it.id] ?: 0) }

    val recentlyAdded = songs
        .sortedWith(compareByDescending<SongEntity> { it.dateAddedMs }.thenBy { it.id })
        .take(RECENTLY_ADDED_COUNT)

    return HomeContent(quickTiles, folders, recentlyAdded)
}

/** "Good morning" 05–11, "Good afternoon" 12–16, otherwise "Good evening". */
fun greetingFor(hourOfDay: Int): String = when (hourOfDay) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

fun songCountLabel(count: Int): String = if (count == 1) "1 song" else "$count songs"
