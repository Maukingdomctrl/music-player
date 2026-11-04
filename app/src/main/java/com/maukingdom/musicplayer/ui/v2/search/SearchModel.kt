package com.maukingdom.musicplayer.ui.v2.search

import androidx.compose.runtime.Immutable
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.playback.VoiceSearch
import com.maukingdom.musicplayer.ui.v2.blendOver
import com.maukingdom.musicplayer.ui.v2.contrastRatio
import com.maukingdom.musicplayer.ui.v2.library.LibrarySort
import com.maukingdom.musicplayer.ui.v2.library.PlaylistItem
import com.maukingdom.musicplayer.ui.v2.library.SongGroup
import com.maukingdom.musicplayer.ui.v2.library.groupByAlbum
import com.maukingdom.musicplayer.ui.v2.library.groupByArtist

/*
 * Search tab results and browse tiles. Pure (no Android or Compose
 * runtime), so it is unit tested. Matching and ranking are VoiceSearch's,
 * so typed search behaves like voice search and Android Auto search.
 */

const val MAX_PLAYLIST_RESULTS = 4
const val MAX_SONG_RESULTS = 20
const val MAX_ARTIST_RESULTS = 5
const val MAX_ALBUM_RESULTS = 5

/** What the Search tab shows for a query. */
@Immutable
sealed interface SearchContent {
    /** Blank query: the browse grid. */
    data object Browse : SearchContent

    /** Each section is already capped and ordered; empty sections are hidden. */
    data class Results(
        val query: String,
        val playlists: List<PlaylistItem>,
        val songs: List<SongEntity>,
        val artists: List<SongGroup>,
        val albums: List<SongGroup>
    ) : SearchContent {
        val isEmpty: Boolean
            get() = playlists.isEmpty() && songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
    }
}

/**
 * Results for [query], in section order Playlists, Songs, Artists, Albums.
 *
 * - Playlists and songs: VoiceSearch.searchResults ranking (exact name or
 *   title first, then the closest "contains" match; songs also match by
 *   artist and album), capped per section.
 * - Artists and albums: groups whose normalised name contains the query,
 *   exact matches first, then A–Z; grouped like the Library tab.
 */
fun buildSearchResults(
    query: String,
    playlists: List<PlaylistEntity>,
    songCounts: Map<Long, Int>,
    folderPlaylistIds: Set<Long>,
    songs: List<SongEntity>
): SearchContent {
    val q = VoiceSearch.normalize(query)
    if (q.isEmpty()) return SearchContent.Browse

    val ranked = VoiceSearch.searchResults(query, playlists, folderPlaylistIds, songs, limit = Int.MAX_VALUE)

    val playlistHits = ranked.playlists.take(MAX_PLAYLIST_RESULTS).map {
        PlaylistItem(
            id = it.id,
            name = it.name,
            songCount = songCounts[it.id] ?: 0,
            isFolder = it.id in folderPlaylistIds,
            createdAtMs = it.createdAtMs
        )
    }

    fun groupsMatching(groups: List<SongGroup>, max: Int): List<SongGroup> = groups
        .map { it to VoiceSearch.normalize(it.name) }
        .filter { (_, name) -> name.contains(q) }
        // Stable sort: exact names first, A–Z within each.
        .sortedBy { (_, name) -> name != q }
        .map { it.first }
        .take(max)

    val artistSongs = songs.filter { VoiceSearch.normalize(it.artist).contains(q) }
    val albumSongs = songs.filter { VoiceSearch.normalize(it.album).contains(q) }

    return SearchContent.Results(
        query = query.trim(),
        playlists = playlistHits,
        songs = ranked.songs.take(MAX_SONG_RESULTS),
        artists = groupsMatching(groupByArtist(artistSongs, LibrarySort.A_TO_Z), MAX_ARTIST_RESULTS),
        albums = groupsMatching(groupByAlbum(albumSongs, LibrarySort.A_TO_Z), MAX_ALBUM_RESULTS)
    )
}

/** One browse tile. */
@Immutable
data class BrowseTile(val playlistId: Long, val name: String, val isFolder: Boolean)

/** Folder-linked playlists A–Z, then the other non-empty playlists A–Z. */
fun browseTiles(
    playlists: List<PlaylistEntity>,
    songCounts: Map<Long, Int>,
    folderPlaylistIds: Set<Long>
): List<BrowseTile> {
    val byName = compareBy<PlaylistEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    val folders = playlists.filter { it.id in folderPlaylistIds }.sortedWith(byName)
    val others = playlists
        .filter { it.id !in folderPlaylistIds && (songCounts[it.id] ?: 0) > 0 }
        .sortedWith(byName)
    return (folders + others).map { BrowseTile(it.id, it.name, it.id in folderPlaylistIds) }
}

/** Dark, saturated tile colours; each has at least 6:1 contrast with white. */
val TILE_FALLBACK_PALETTE: List<Int> = listOf(
    0xFF8C1D40.toInt(), // crimson
    0xFF1E4C8C.toInt(), // blue
    0xFF2B6B4A.toInt(), // green
    0xFF6A3FA0.toInt(), // violet
    0xFF8A4510.toInt(), // amber
    0xFF0E6A6E.toInt(), // teal
    0xFF9C2F6B.toInt(), // magenta
    0xFF3E4A9A.toInt()  // indigo
)

/** A stable tile colour for [name] until its cover colour is known. */
fun tileFallbackColor(name: String): Int =
    TILE_FALLBACK_PALETTE[Math.floorMod(name.lowercase().hashCode(), TILE_FALLBACK_PALETTE.size)]

/** White tile names need this much contrast (16 sp is not "large text"). */
const val TILE_MIN_CONTRAST = 4.5f

/**
 * The tile colour for a playlist wash colour: the wash itself when white
 * text already has [TILE_MIN_CONTRAST]:1, otherwise mixed toward [bgArgb]
 * until it does.
 */
fun tileColorFromWash(washArgb: Int, bgArgb: Int): Int {
    val white = -0x1
    var colour = washArgb
    var towardBg = 0f
    while (contrastRatio(white, colour) < TILE_MIN_CONTRAST && towardBg < 1f) {
        towardBg = (towardBg + 0.05f).coerceAtMost(1f)
        colour = blendOver(washArgb, 1f - towardBg, bgArgb)
    }
    return colour
}
