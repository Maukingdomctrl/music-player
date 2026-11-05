package com.maukingdom.musicplayer.ui.v2.library

import androidx.compose.runtime.Immutable
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.FolderOperationKind
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel

/*
 * Sorting, grouping and labels for the Your Library tab. Pure (no Android
 * or Compose runtime), so it is unit tested.
 */

/** The chips, in display order. */
enum class LibraryFilter(val label: String) {
    PLAYLISTS("Playlists"),
    FOLDERS("Folders"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums")
}

enum class LibrarySort(val label: String) {
    RECENT("Recently added"),
    A_TO_Z("A–Z"),
    MOST_SONGS("Most songs"),
    ARTIST("Artist")
}

/** The sort orders a chip offers; the first is its default. */
fun sortsFor(filter: LibraryFilter): List<LibrarySort> = when (filter) {
    LibraryFilter.PLAYLISTS,
    LibraryFilter.FOLDERS -> listOf(LibrarySort.RECENT, LibrarySort.A_TO_Z, LibrarySort.MOST_SONGS)
    LibraryFilter.SONGS -> listOf(LibrarySort.RECENT, LibrarySort.A_TO_Z, LibrarySort.ARTIST)
    LibraryFilter.ARTISTS,
    LibraryFilter.ALBUMS -> listOf(LibrarySort.A_TO_Z, LibrarySort.MOST_SONGS)
}

/** The next sort for a tap on the sort row; an invalid [current] restarts at the default. */
fun nextSort(filter: LibraryFilter, current: LibrarySort): LibrarySort {
    val options = sortsFor(filter)
    val index = options.indexOf(current)
    return options[(index + 1) % options.size]
}

/** The sort to use for [filter] when [saved] may belong to another chip. */
fun validSort(filter: LibraryFilter, saved: LibrarySort?): LibrarySort =
    saved?.takeIf { it in sortsFor(filter) } ?: sortsFor(filter).first()

/**
 * Per-chip sorts as one saveable string, e.g. "PLAYLISTS=A_TO_Z,SONGS=ARTIST".
 * Unknown or stale entries are ignored when decoding.
 */
fun encodeSorts(sorts: Map<LibraryFilter, LibrarySort>): String =
    sorts.entries.joinToString(",") { "${it.key.name}=${it.value.name}" }

fun decodeSorts(encoded: String): Map<LibraryFilter, LibrarySort> =
    encoded.split(',').mapNotNull { entry ->
        val parts = entry.split('=')
        if (parts.size != 2) return@mapNotNull null
        val filter = LibraryFilter.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
        val sort = LibrarySort.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        if (sort in sortsFor(filter)) filter to sort else null
    }.toMap()

/** One playlist row. */
@Immutable
data class PlaylistItem(
    val id: Long,
    val name: String,
    val songCount: Int,
    val isFolder: Boolean,
    val createdAtMs: Long,
    /** The Favourites playlist: a heart badge instead of the folder mark. */
    val isFavourites: Boolean = false
)

/** One artist or album row: its songs in play order. [key] is stable and case-insensitive. */
@Immutable
data class SongGroup(
    val key: String,
    val name: String,
    /** Albums only: the artist, or "Various artists" when the songs disagree. */
    val artist: String?,
    val songs: List<SongEntity>
) {
    val coverSongId: String get() = songs.first().id
}

private val nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER

/** Playlists for the Playlists chip ([foldersOnly] = false) or the Folders chip. */
fun playlistItems(
    playlists: List<PlaylistEntity>,
    songCounts: Map<Long, Int>,
    folderPlaylistIds: Set<Long>,
    foldersOnly: Boolean,
    sort: LibrarySort,
    favouritesId: Long? = null
): List<PlaylistItem> {
    val items = playlists
        .filter { !foldersOnly || it.id in folderPlaylistIds }
        .map {
            PlaylistItem(
                id = it.id,
                name = it.name,
                songCount = songCounts[it.id] ?: 0,
                isFolder = it.id in folderPlaylistIds,
                createdAtMs = it.createdAtMs,
                isFavourites = it.id == favouritesId
            )
        }

    val byName = compareBy<PlaylistItem, String>(nameOrder) { it.name }.thenBy { it.id }
    val comparator = when (sort) {
        LibrarySort.A_TO_Z -> byName
        LibrarySort.MOST_SONGS -> compareByDescending<PlaylistItem> { it.songCount }.then(byName)
        else -> compareByDescending<PlaylistItem> { it.createdAtMs }.thenByDescending { it.id }
    }
    return items.sortedWith(comparator)
}

/** Songs for the Songs chip. */
fun sortSongs(songs: List<SongEntity>, sort: LibrarySort): List<SongEntity> {
    val byTitle = compareBy<SongEntity, String>(nameOrder) { it.title }
        .thenBy(nameOrder) { it.artist }
        .thenBy { it.id }
    val comparator = when (sort) {
        LibrarySort.A_TO_Z -> byTitle
        LibrarySort.ARTIST -> compareBy<SongEntity, String>(nameOrder) { artistName(it.artist) }.then(byTitle)
        else -> compareByDescending<SongEntity> { it.dateAddedMs }.thenBy { it.id }
    }
    return songs.sortedWith(comparator)
}

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"
const val VARIOUS_ARTISTS = "Various artists"

private fun artistName(raw: String): String = raw.trim().ifEmpty { UNKNOWN_ARTIST }
private fun albumName(raw: String): String = raw.trim().ifEmpty { UNKNOWN_ALBUM }

/** Artists, case-insensitively merged ("ABBA" = "abba"); songs by title. */
fun groupByArtist(songs: List<SongEntity>, sort: LibrarySort): List<SongGroup> =
    group(songs, sort, keyOf = { artistName(it.artist) }, withArtist = false)

/** Albums by name, case-insensitively merged; songs in the order they were added. */
fun groupByAlbum(songs: List<SongEntity>, sort: LibrarySort): List<SongGroup> =
    group(songs, sort, keyOf = { albumName(it.album) }, withArtist = true)

private fun group(
    songs: List<SongEntity>,
    sort: LibrarySort,
    keyOf: (SongEntity) -> String,
    withArtist: Boolean
): List<SongGroup> {
    val songOrder = if (withArtist) {
        compareBy<SongEntity> { it.dateAddedMs }.thenBy { it.id }
    } else {
        compareBy<SongEntity, String>(nameOrder) { it.title }.thenBy { it.id }
    }

    val groups = songs
        .groupBy { keyOf(it).lowercase() }
        .map { (key, members) ->
            val ordered = members.sortedWith(songOrder)
            // Display the spelling most songs use; ties go to the first alphabetically.
            val name = members.groupingBy { keyOf(it) }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
            val artist = if (withArtist) {
                val artists = members.map { artistName(it.artist) }.distinctBy { it.lowercase() }
                if (artists.size == 1) artists.first() else VARIOUS_ARTISTS
            } else {
                null
            }
            SongGroup(key = key, name = name, artist = artist, songs = ordered)
        }

    val byName = compareBy<SongGroup, String>(nameOrder) { it.name }.thenBy { it.key }
    val comparator = if (sort == LibrarySort.MOST_SONGS) {
        compareByDescending<SongGroup> { it.songs.size }.then(byName)
    } else {
        byName
    }
    return groups.sortedWith(comparator)
}

/** "SD folder · 12 songs" or "Playlist · 1 song". */
fun playlistMetaText(isFolder: Boolean, songCount: Int): String =
    (if (isFolder) "SD folder" else "Playlist") + " · " + songCountLabel(songCount)

/** "Artist · 3 songs" for an album row. */
fun albumMetaText(artist: String, songCount: Int): String = "$artist · ${songCountLabel(songCount)}"

const val FOLDER_ACCESS_LOST = "Folder access lost · tap to re-select"

/**
 * The slim progress banner text: [folderKind] is the running folder
 * operation (null when only another import runs).
 */
fun progressText(folderKind: FolderOperationKind?, playlistName: String?): String = when (folderKind) {
    FolderOperationKind.SYNC -> if (playlistName != null) "Syncing $playlistName…" else "Syncing folder…"
    FolderOperationKind.IMPORT -> "Importing SD-card folder…"
    null -> "Adding music…"
}
