package com.maukingdom.musicplayer.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Application-facing operations for the local library and playlists.
 */
class MusicRepository(
    private val database: MusicDatabase
) {
    private val dao: LibraryDao = database.libraryDao()

    val songs: Flow<List<SongEntity>> = dao.observeSongs()
    val playlists: Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observePlaylistSongs(playlistId: Long): Flow<List<SongEntity>> {
        return dao.observePlaylistSongs(playlistId)
    }

    suspend fun getAllSongs(): List<SongEntity> {
        return dao.getAllSongs()
    }

    suspend fun getSong(songId: String): SongEntity? {
        return dao.getSong(songId)
    }

    suspend fun getPlaylistSongs(playlistId: Long): List<SongEntity> {
        return dao.getPlaylistSongs(playlistId)
    }

    /**
     * Inserts or refreshes imported songs without deleting existing playlist
     * entries. User-corrected descriptive metadata is preserved on re-import.
     */
    suspend fun saveImportedSongs(importedSongs: List<SongEntity>) {
        database.withTransaction {
            for (imported in importedSongs.distinctBy { it.id }) {
                val existing = dao.getSong(imported.id)

                if (existing == null) {
                    dao.insertSong(imported)
                } else {
                    val merged = if (existing.metadataEdited) {
                        imported.copy(
                            title = existing.title,
                            artist = existing.artist,
                            album = existing.album,
                            genre = existing.genre,
                            year = existing.year,
                            metadataEdited = true,
                            dateAddedMs = existing.dateAddedMs
                        )
                    } else {
                        imported.copy(
                            metadataEdited = false,
                            dateAddedMs = existing.dateAddedMs
                        )
                    }

                    dao.updateSong(merged)
                }
            }
        }
    }

    /**
     * Saves corrections in Room only. Returns false if the song no longer
     * exists. An empty title is rejected; other text fields may be empty.
     */
    suspend fun updateMetadata(
        songId: String,
        metadata: SongMetadata
    ): Boolean {
        val title = metadata.title.trim()
        require(title.isNotEmpty()) { "A song title is required." }

        val year = metadata.year
        require(year == null || year in 1..9999) {
            "Year must be between 1 and 9999."
        }

        return database.withTransaction {
            val existing = dao.getSong(songId) ?: return@withTransaction false

            dao.updateSong(
                existing.copy(
                    title = title,
                    artist = metadata.artist.trim(),
                    album = metadata.album.trim(),
                    genre = metadata.genre
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    year = year,
                    metadataEdited = true
                )
            ) > 0
        }
    }

    /**
     * Removes a song from this app's library and playlists.
     * The underlying audio file is not deleted.
     */
    suspend fun removeSong(songId: String): Boolean {
        return dao.deleteSong(songId) > 0
    }

    suspend fun createPlaylist(name: String): Long {
        val cleanedName = name.trim()
        require(cleanedName.isNotEmpty()) { "A playlist name is required." }

        return dao.insertPlaylist(
            PlaylistEntity(name = cleanedName)
        )
    }

    suspend fun renamePlaylist(
        playlistId: Long,
        name: String
    ): Boolean {
        val cleanedName = name.trim()
        require(cleanedName.isNotEmpty()) { "A playlist name is required." }

        return dao.renamePlaylist(playlistId, cleanedName) > 0
    }

    suspend fun deletePlaylist(playlistId: Long): Boolean {
        return dao.deletePlaylist(playlistId) > 0
    }

    /**
     * Adds a song once per playlist. Returns false if either record does not
     * exist or the song is already in the playlist.
     */
    suspend fun addSongToPlaylist(
        playlistId: Long,
        songId: String
    ): Boolean {
        return database.withTransaction {
            if (dao.getPlaylist(playlistId) == null ||
                dao.getSong(songId) == null
            ) {
                return@withTransaction false
            }

            val position = dao.getLastPlaylistPosition(playlistId) + 1

            dao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    position = position
                )
            ) != -1L
        }
    }

    suspend fun removeSongFromPlaylist(
        playlistId: Long,
        songId: String
    ): Boolean {
        return dao.deletePlaylistSong(playlistId, songId) > 0
    }

    /**
     * Reorders an existing playlist. The supplied IDs must contain every
     * current playlist song exactly once.
     */
    suspend fun reorderPlaylist(
        playlistId: Long,
        orderedSongIds: List<String>
    ): Boolean {
        return database.withTransaction {
            val entries = dao.getPlaylistEntries(playlistId)
            val existingIds = entries.map { it.songId }

            if (dao.getPlaylist(playlistId) == null ||
                orderedSongIds.size != existingIds.size ||
                orderedSongIds.toSet().size != orderedSongIds.size ||
                orderedSongIds.toSet() != existingIds.toSet()
            ) {
                return@withTransaction false
            }

            val entriesById = entries.associateBy { it.songId }

            orderedSongIds.forEachIndexed { index, songId ->
                val entry = entriesById.getValue(songId)
                dao.updatePlaylistSong(
                    entry.copy(position = index.toLong())
                )
            }

            true
        }
    }
}
