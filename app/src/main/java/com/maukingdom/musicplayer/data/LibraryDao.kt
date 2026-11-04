package com.maukingdom.musicplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // Songs

    @Query(
        """
        SELECT * FROM songs
        ORDER BY title COLLATE NOCASE ASC, artist COLLATE NOCASE ASC
        """
    )
    fun observeSongs(): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        ORDER BY title COLLATE NOCASE ASC, artist COLLATE NOCASE ASC
        """
    )
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSong(songId: String): SongEntity?

    @Query(
        """
        SELECT * FROM songs
        ORDER BY dateAddedMs DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getRecentSongs(limit: Int): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    @Update
    suspend fun updateSong(song: SongEntity): Int

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String): Int

    // Playlists

    @Query(
        """
        SELECT * FROM playlists
        ORDER BY name COLLATE NOCASE ASC, id ASC
        """
    )
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    /** Song count per playlist; empty playlists are left out. Read-only. */
    @Query(
        """
        SELECT playlistId, COUNT(*) AS songCount
        FROM playlist_songs
        GROUP BY playlistId
        """
    )
    fun observePlaylistSongCounts(): Flow<List<PlaylistSongCount>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: Long): PlaylistEntity?

    @Query(
        """
        SELECT * FROM playlists
        ORDER BY name COLLATE NOCASE ASC, id ASC
        """
    )
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long): Int

    // Playlist contents

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs
            ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC, songs.id ASC
        """
    )
    fun observePlaylistSongs(playlistId: Long): Flow<List<SongEntity>>

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs
            ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC, songs.id ASC
        """
    )
    suspend fun getPlaylistSongs(playlistId: Long): List<SongEntity>

    @Query(
        """
        SELECT * FROM playlist_songs
        WHERE playlistId = :playlistId
        ORDER BY position ASC, songId ASC
        """
    )
    suspend fun getPlaylistEntries(playlistId: Long): List<PlaylistSongEntity>

    @Query(
        """
        SELECT COALESCE(MAX(position), -1)
        FROM playlist_songs
        WHERE playlistId = :playlistId
        """
    )
    suspend fun getLastPlaylistPosition(playlistId: Long): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(entry: PlaylistSongEntity): Long

    @Update
    suspend fun updatePlaylistSong(entry: PlaylistSongEntity): Int

    @Query(
        """
        DELETE FROM playlist_songs
        WHERE playlistId = :playlistId AND songId = :songId
        """
    )
    suspend fun deletePlaylistSong(playlistId: Long, songId: String): Int

    // Folder-linked playlists

    @Query("SELECT * FROM folder_playlists WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getFolderLink(playlistId: Long): FolderPlaylistEntity?

    @Query("SELECT * FROM folder_playlists WHERE treeUri = :treeUri LIMIT 1")
    suspend fun getFolderLinkByTreeUri(treeUri: String): FolderPlaylistEntity?

    @Query("SELECT playlistId FROM folder_playlists")
    fun observeFolderLinkedPlaylistIds(): Flow<List<Long>>

    @Query("SELECT playlistId FROM folder_playlists")
    suspend fun getFolderLinkedPlaylistIds(): List<Long>

    @Query(
        """
        SELECT playlistId FROM folder_playlists
        WHERE lastSyncedAtMs IS NOT NULL
        ORDER BY lastSyncedAtMs DESC, playlistId DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentlySyncedFolderPlaylistId(): Long?

    @Query(
        """
        SELECT playlist_songs.playlistId FROM playlist_songs
        INNER JOIN folder_playlists
            ON folder_playlists.playlistId = playlist_songs.playlistId
        WHERE playlist_songs.songId = :songId
        ORDER BY playlist_songs.playlistId ASC
        LIMIT 1
        """
    )
    suspend fun getFirstFolderPlaylistIdContaining(songId: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolderLink(link: FolderPlaylistEntity)

    @Query("UPDATE folder_playlists SET treeUri = :treeUri WHERE playlistId = :playlistId")
    suspend fun updateFolderTreeUri(playlistId: Long, treeUri: String): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM folder_documents
            WHERE playlistId = :playlistId AND songId = :songId
        )
        """
    )
    suspend fun isSongRecordedInFolder(playlistId: Long, songId: String): Boolean

    @Query(
        """
        UPDATE folder_playlists
        SET lastSyncedAtMs = :syncedAtMs
        WHERE playlistId = :playlistId
        """
    )
    suspend fun updateLastSynced(playlistId: Long, syncedAtMs: Long): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM folder_documents
            WHERE playlistId = :playlistId AND documentId = :documentId
        )
        """
    )
    suspend fun isDocumentKnown(playlistId: Long, documentId: String): Boolean

    @Query("SELECT documentId FROM folder_documents WHERE playlistId = :playlistId")
    suspend fun getKnownDocumentIds(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderDocument(document: FolderDocumentEntity): Long
}
