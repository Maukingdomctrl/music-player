package com.maukingdom.musicplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pure Favourites rules. JVM-tested. */
object FavouritesRules {
    const val NAME = "Favourites"

    /** The saved id while its playlist still exists; null means "create it". */
    fun usableId(savedId: Long?, existingPlaylistIds: Set<Long>): Long? =
        savedId?.takeIf { it in existingPlaylistIds }

    /** What a heart tap does: true = add, false = remove. */
    fun shouldAdd(isFavourite: Boolean): Boolean = !isFavourite
}

/**
 * Favourites without a schema change: an ordinary playlist named
 * "Favourites" whose id is kept in DataStore. Created on the first heart,
 * and created again on the next heart if the user deleted it. Newest
 * favourites are at the top. Shared by the UI and PlaybackService.
 */
class FavouritesStore(
    private val repository: MusicRepository,
    private val preferences: PlaybackPreferences
) {
    /** The Favourites playlist id while it exists, else null. */
    val playlistId: Flow<Long?> =
        combine(preferences.favouritesPlaylistId, repository.playlists) { saved, playlists ->
            FavouritesRules.usableId(saved, playlists.mapTo(HashSet()) { it.id })
        }.distinctUntilChanged()

    /** Ids of the songs in Favourites (empty while it does not exist). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val songIds: Flow<Set<String>> = playlistId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptySet()) else repository.observePlaylistSongs(id).map { songs -> songs.mapTo(HashSet()) { it.id } }
        }
        .distinctUntilChanged()

    /** Adds [songId] if it is not a favourite, else removes it. Returns true when it is now a favourite. */
    suspend fun toggle(songId: String): Boolean {
        val id = ensurePlaylist()
        val isFavourite = repository.getPlaylistSongs(id).any { it.id == songId }
        return if (FavouritesRules.shouldAdd(isFavourite)) {
            repository.prependSongsToPlaylist(id, listOf(songId))
            true
        } else {
            repository.removeSongFromPlaylist(id, songId)
            false
        }
    }

    /** Puts [songIds] at the top of Favourites, skipping ones already there. */
    suspend fun add(songIds: List<String>): PlaylistEdit =
        repository.prependSongsToPlaylist(ensurePlaylist(), songIds)

    suspend fun remove(songIds: List<String>): PlaylistEdit {
        val id = playlistId.first() ?: return PlaylistEdit()
        return repository.removeSongsFromPlaylist(id, songIds)
    }

    /** The Favourites playlist, created (and its id saved) if missing. */
    suspend fun ensurePlaylist(): Long = creation.withLock {
        val saved = preferences.favouritesPlaylistId.first()
        val existing = repository.getAllPlaylists().mapTo(HashSet()) { it.id }
        FavouritesRules.usableId(saved, existing) ?: repository.createPlaylist(FavouritesRules.NAME).also {
            preferences.saveFavouritesPlaylistId(it)
        }
    }

    private companion object {
        /** One process: the UI and the service never create two Favourites at once. */
        val creation = Mutex()
    }
}
