package com.maukingdom.musicplayer.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * One audio document found in a linked folder. [documentId] is the exact SAF
 * document ID. [song] has metadata already read and its ID already resolved
 * by the caller; the repository matches existing songs only by [SongEntity.id].
 */
data class ScannedFolderDocument(
    val documentId: String,
    val song: SongEntity
)

data class FolderSyncResult(
    val playlistId: Long,
    val addedToPlaylist: Int,
    val skippedAlreadyKnown: Int,
    val newSongsInserted: Int,
    val existingSongsReused: Int,
    val alreadyInPlaylist: Int
)

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

    suspend fun getFolderLink(playlistId: Long): FolderPlaylistEntity? {
        return dao.getFolderLink(playlistId)
    }

    suspend fun getKnownDocumentIds(playlistId: Long): List<String> {
        return dao.getKnownDocumentIds(playlistId)
    }

    suspend fun getFolderLinkByTreeUri(treeUri: String): FolderPlaylistEntity? {
        return dao.getFolderLinkByTreeUri(treeUri)
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

    /**
     * Creates a playlist linked to [treeUri] and adds [initialDocuments].
     * If [treeUri] is already linked, the existing playlist is reused without
     * renaming it and the documents are added with the same additive rules.
     * Everything happens in one transaction.
     */
    suspend fun createFolderPlaylist(
        name: String,
        treeUri: String,
        linkedAtMs: Long,
        initialDocuments: List<ScannedFolderDocument>
    ): FolderSyncResult {
        val cleanedName = name.trim()
        require(cleanedName.isNotEmpty()) { "A playlist name is required." }

        return database.withTransaction {
            val existingLink = dao.getFolderLinkByTreeUri(treeUri)

            val playlistId = if (existingLink != null) {
                existingLink.playlistId
            } else {
                val newPlaylistId = dao.insertPlaylist(
                    PlaylistEntity(name = cleanedName)
                )

                dao.insertFolderLink(
                    FolderPlaylistEntity(
                        playlistId = newPlaylistId,
                        treeUri = treeUri,
                        linkedAtMs = linkedAtMs,
                        lastSyncedAtMs = null
                    )
                )

                newPlaylistId
            }

            val result = applyFolderDocuments(
                playlistId = playlistId,
                documents = initialDocuments,
                discoveredAtMs = linkedAtMs
            )

            dao.updateLastSynced(playlistId, linkedAtMs)

            result
        }
    }

    /**
     * Adds newly discovered folder documents to a folder-linked playlist.
     * Documents seen before are skipped, so songs the user removed from the
     * playlist or library are not added back. Nothing is deleted or reordered.
     */
    suspend fun applyFolderSync(
        playlistId: Long,
        documents: List<ScannedFolderDocument>,
        syncedAtMs: Long
    ): FolderSyncResult {
        return database.withTransaction {
            checkNotNull(dao.getFolderLink(playlistId)) {
                "Playlist is not linked to a folder."
            }

            val result = applyFolderDocuments(
                playlistId = playlistId,
                documents = documents,
                discoveredAtMs = syncedAtMs
            )

            dao.updateLastSynced(playlistId, syncedAtMs)

            result
        }
    }

    /**
     * Per-document folder sync rules. Must be called inside a transaction.
     * Existing songs are reused by exact ID and never updated.
     */
    private suspend fun applyFolderDocuments(
        playlistId: Long,
        documents: List<ScannedFolderDocument>,
        discoveredAtMs: Long
    ): FolderSyncResult {
        var addedToPlaylist = 0
        var skippedAlreadyKnown = 0
        var newSongsInserted = 0
        var existingSongsReused = 0
        var alreadyInPlaylist = 0

        var nextPosition = dao.getLastPlaylistPosition(playlistId) + 1

        for (document in documents.distinctBy { it.documentId }) {
            if (dao.isDocumentKnown(playlistId, document.documentId)) {
                skippedAlreadyKnown++
                continue
            }

            val song = document.song

            if (dao.insertSong(song) != -1L) {
                newSongsInserted++
            } else {
                existingSongsReused++
            }

            val recorded = dao.insertFolderDocument(
                FolderDocumentEntity(
                    playlistId = playlistId,
                    documentId = document.documentId,
                    songId = song.id,
                    discoveredAtMs = discoveredAtMs
                )
            )
            check(recorded != -1L) {
                "Folder document was not recorded: ${document.documentId}"
            }

            val added = dao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = song.id,
                    position = nextPosition
                )
            ) != -1L

            if (added) {
                addedToPlaylist++
                nextPosition++
            } else {
                alreadyInPlaylist++
            }
        }

        return FolderSyncResult(
            playlistId = playlistId,
            addedToPlaylist = addedToPlaylist,
            skippedAlreadyKnown = skippedAlreadyKnown,
            newSongsInserted = newSongsInserted,
            existingSongsReused = existingSongsReused,
            alreadyInPlaylist = alreadyInPlaylist
        )
    }
}
