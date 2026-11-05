package com.maukingdom.musicplayer.playback

import com.maukingdom.musicplayer.data.PlaylistEntity

/**
 * IDs used by media browsers such as Android Auto.
 *
 * Browsable nodes start with "node:" and playable entries start with "ctx:",
 * so neither can be mistaken for a song ID (song IDs are content URIs, which
 * start with "content://"). A playable entry remembers the list it was shown
 * in, so playing it can queue that whole list.
 *
 * Pure Kotlin: no Android or Media3 types, so it can be unit-tested on the JVM.
 */
sealed interface BrowseId {

    /** Something that can be browsed into. */
    sealed interface Node : BrowseId

    /** Something that plays a single song. */
    sealed interface Playable : BrowseId {
        val songId: String
    }

    data object Root : Node
    data object Playlists : Node
    data object Recent : Node
    data object Library : Node
    /** The Favourites playlist's songs (empty until the first heart). */
    data object Favourites : Node
    data class Playlist(val playlistId: Long) : Node

    data class PlaylistSong(
        val playlistId: Long,
        override val songId: String
    ) : Playable

    data class RecentSong(override val songId: String) : Playable
    data class LibrarySong(override val songId: String) : Playable

    /** A plain SongEntity.id, as used for queue items. */
    data class Song(override val songId: String) : Playable
}

object BrowseTree {

    const val ROOT = "node:root"
    const val PLAYLISTS = "node:playlists"
    const val RECENT = "node:recent"
    const val LIBRARY = "node:library"
    const val FAVOURITES = "node:favourites"

    const val ROOT_TITLE = "MyCar Music"
    const val PLAYLISTS_TITLE = "Playlists"
    const val RECENT_TITLE = "Recently added"
    const val LIBRARY_TITLE = "All songs"
    const val FAVOURITES_TITLE = "Favourites"

    /** Number of songs listed under "Recently added". */
    const val RECENT_LIMIT = 50

    private const val NODE_PREFIX = "node:"
    private const val PLAYLIST_NODE_PREFIX = "node:playlist:"
    private const val CTX_PREFIX = "ctx:"
    private const val CTX_PLAYLIST = "ctx:playlist:"
    private const val CTX_RECENT = "ctx:recent"
    private const val CTX_LIBRARY = "ctx:library"
    private const val SEPARATOR = '|'

    /** The children of the root node, in display order. Favourites is last, so the others keep their places. */
    val rootChildren: List<BrowseId.Node> = listOf(
        BrowseId.Playlists,
        BrowseId.Recent,
        BrowseId.Library,
        BrowseId.Favourites
    )

    fun encode(id: BrowseId): String = when (id) {
        BrowseId.Root -> ROOT
        BrowseId.Playlists -> PLAYLISTS
        BrowseId.Recent -> RECENT
        BrowseId.Library -> LIBRARY
        BrowseId.Favourites -> FAVOURITES
        is BrowseId.Playlist -> "$PLAYLIST_NODE_PREFIX${id.playlistId}"
        is BrowseId.PlaylistSong -> "$CTX_PLAYLIST${id.playlistId}$SEPARATOR${id.songId}"
        is BrowseId.RecentSong -> "$CTX_RECENT$SEPARATOR${id.songId}"
        is BrowseId.LibrarySong -> "$CTX_LIBRARY$SEPARATOR${id.songId}"
        is BrowseId.Song -> id.songId
    }

    /**
     * Returns null for blank IDs and for any unrecognised "node:" or "ctx:"
     * ID. Any other non-blank ID is treated as a plain song ID; whether that
     * song exists is checked later against the library.
     */
    fun decode(raw: String): BrowseId? {
        if (raw.isBlank()) return null

        if (raw.startsWith(NODE_PREFIX)) {
            return decodeNode(raw)
        }

        if (raw.startsWith(CTX_PREFIX)) {
            return decodeContext(raw)
        }

        return BrowseId.Song(raw)
    }

    /** Title shown for a fixed node; playlist titles come from the database. */
    fun title(node: BrowseId.Node): String? = when (node) {
        BrowseId.Root -> ROOT_TITLE
        BrowseId.Playlists -> PLAYLISTS_TITLE
        BrowseId.Recent -> RECENT_TITLE
        BrowseId.Library -> LIBRARY_TITLE
        BrowseId.Favourites -> FAVOURITES_TITLE
        is BrowseId.Playlist -> null
    }

    /** Folder-linked playlists first, then the rest; each group A–Z by name. */
    fun orderPlaylists(
        playlists: List<PlaylistEntity>,
        folderLinkedIds: Set<Long>
    ): List<PlaylistEntity> {
        return playlists.sortedWith(
            compareBy<PlaylistEntity> { it.id !in folderLinkedIds }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.id }
        )
    }

    /** One page of [items]; an out-of-range page is empty. */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()

        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()

        val to = minOf(items.size.toLong(), from + pageSize).toInt()
        return items.subList(from.toInt(), to)
    }

    private fun decodeNode(raw: String): BrowseId.Node? = when {
        raw == ROOT -> BrowseId.Root
        raw == PLAYLISTS -> BrowseId.Playlists
        raw == RECENT -> BrowseId.Recent
        raw == LIBRARY -> BrowseId.Library
        raw == FAVOURITES -> BrowseId.Favourites
        raw.startsWith(PLAYLIST_NODE_PREFIX) ->
            parsePlaylistId(raw.removePrefix(PLAYLIST_NODE_PREFIX))
                ?.let { BrowseId.Playlist(it) }
        else -> null
    }

    private fun decodeContext(raw: String): BrowseId.Playable? {
        val separator = raw.indexOf(SEPARATOR)
        if (separator < 0) return null

        val context = raw.substring(0, separator)
        val songId = raw.substring(separator + 1)
        if (songId.isBlank()) return null

        return when {
            context == CTX_RECENT -> BrowseId.RecentSong(songId)
            context == CTX_LIBRARY -> BrowseId.LibrarySong(songId)
            context.startsWith(CTX_PLAYLIST) ->
                parsePlaylistId(context.removePrefix(CTX_PLAYLIST))
                    ?.let { BrowseId.PlaylistSong(it, songId) }
            else -> null
        }
    }

    /** Digits only, so "+5", " 5" or "5x" are rejected. */
    private fun parsePlaylistId(text: String): Long? {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        return text.toLongOrNull()
    }
}
