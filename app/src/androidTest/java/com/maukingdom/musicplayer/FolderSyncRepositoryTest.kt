package com.maukingdom.musicplayer

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maukingdom.musicplayer.data.FolderDocumentEntity
import com.maukingdom.musicplayer.data.FolderPlaylistEntity
import com.maukingdom.musicplayer.data.FolderSyncResult
import com.maukingdom.musicplayer.data.LibraryDao
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaylistSongEntity
import com.maukingdom.musicplayer.data.ScannedFolderDocument
import com.maukingdom.musicplayer.data.SongEntity
import androidx.sqlite.SQLiteException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderSyncRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: MusicDatabase
    private lateinit var dao: LibraryDao
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        database = Room.inMemoryDatabaseBuilder(
            context,
            MusicDatabase::class.java
        )
            .setDriver(AndroidSQLiteDriver())
            .build()

        dao = database.libraryDao()
        repository = MusicRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // 1
    @Test
    fun createFolderPlaylist_createsOnePlaylistAndExactLink() = runBlocking {
        val result = repository.createFolderPlaylist(
            name = "  Car music  ",
            treeUri = TREE_URI,
            linkedAtMs = 1_000L,
            initialDocuments = emptyList()
        )

        assertEquals(syncResult(result.playlistId), result)
        assertEquals(1L, count("SELECT COUNT(*) FROM playlists"))
        assertEquals(1L, count("SELECT COUNT(*) FROM folder_playlists"))
        assertEquals("Car music", dao.getPlaylist(result.playlistId)?.name)
        assertEquals(
            FolderPlaylistEntity(
                playlistId = result.playlistId,
                treeUri = TREE_URI,
                linkedAtMs = 1_000L,
                lastSyncedAtMs = 1_000L
            ),
            dao.getFolderLink(result.playlistId)
        )

        val blankName = runCatching {
            repository.createFolderPlaylist("   ", OTHER_TREE_URI, 2_000L, emptyList())
        }.exceptionOrNull()
        assertTrue(blankName is IllegalArgumentException)
        assertEquals(1L, count("SELECT COUNT(*) FROM playlists"))
        assertEquals(1L, count("SELECT COUNT(*) FROM folder_playlists"))
    }

    // 2
    @Test
    fun createFolderPlaylist_sameTreeUriReusesPlaylistWithoutRenaming() = runBlocking {
        val first = repository.createFolderPlaylist(
            "Car music",
            TREE_URI,
            1_000L,
            listOf(doc("doc-1", "song-1"))
        )

        val second = repository.createFolderPlaylist(
            "Different name",
            TREE_URI,
            2_000L,
            listOf(doc("doc-1", "song-1"), doc("doc-2", "song-2"))
        )

        assertEquals(first.playlistId, second.playlistId)
        assertEquals(
            syncResult(
                second.playlistId,
                added = 1,
                skipped = 1,
                newSongs = 1
            ),
            second
        )
        assertEquals("Car music", dao.getPlaylist(first.playlistId)?.name)
        assertEquals(1L, count("SELECT COUNT(*) FROM playlists"))
        assertEquals(1L, count("SELECT COUNT(*) FROM folder_playlists"))

        val link = dao.getFolderLink(first.playlistId)
        assertEquals(1_000L, link?.linkedAtMs)
        assertEquals(2_000L, link?.lastSyncedAtMs)
        assertEquals(
            listOf("song-1", "song-2"),
            entries(first.playlistId).map { it.songId }
        )
    }

    // 3
    @Test
    fun createFolderPlaylist_differentTreeUrisSameNameCreateTwoPlaylists() = runBlocking {
        val first = repository.createFolderPlaylist("Car", TREE_URI, 1_000L, emptyList())
        val second = repository.createFolderPlaylist("Car", OTHER_TREE_URI, 1_000L, emptyList())

        assertNotEquals(first.playlistId, second.playlistId)
        assertEquals(2L, count("SELECT COUNT(*) FROM playlists"))
        assertEquals(2L, count("SELECT COUNT(*) FROM folder_playlists"))
        assertEquals(TREE_URI, dao.getFolderLink(first.playlistId)?.treeUri)
        assertEquals(OTHER_TREE_URI, dao.getFolderLink(second.playlistId)?.treeUri)
    }

    // 4
    @Test
    fun applyFolderSync_appendsNewDocumentsWithConsecutivePositions() = runBlocking {
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            listOf(doc("doc-1", "song-1"))
        ).playlistId

        val result = repository.applyFolderSync(
            playlistId,
            listOf(doc("doc-2", "song-2"), doc("doc-3", "song-3")),
            2_000L
        )

        assertEquals(syncResult(playlistId, added = 2, newSongs = 2), result)
        assertEquals(
            listOf(
                PlaylistSongEntity(playlistId, "song-1", 0L),
                PlaylistSongEntity(playlistId, "song-2", 1L),
                PlaylistSongEntity(playlistId, "song-3", 2L)
            ),
            entries(playlistId)
        )
        assertEquals(
            listOf(
                FolderDocumentEntity(playlistId, "doc-1", "song-1", 1_000L),
                FolderDocumentEntity(playlistId, "doc-2", "song-2", 2_000L),
                FolderDocumentEntity(playlistId, "doc-3", "song-3", 2_000L)
            ),
            ledger(playlistId)
        )
        assertEquals(2_000L, dao.getFolderLink(playlistId)?.lastSyncedAtMs)
    }

    // 5
    @Test
    fun applyFolderSync_sameDocumentsAgainChangeNothing() = runBlocking {
        val documents = listOf(
            doc("doc-1", "song-1"),
            doc("doc-2", "song-2"),
            doc("doc-3", "song-3")
        )
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            documents
        ).playlistId

        val songsBefore = dao.getAllSongs()
        val entriesBefore = entries(playlistId)
        val ledgerBefore = ledger(playlistId)
        val playlistBefore = dao.getPlaylist(playlistId)

        val result = repository.applyFolderSync(playlistId, documents, 2_000L)

        assertEquals(syncResult(playlistId, skipped = 3), result)
        assertEquals(songsBefore, dao.getAllSongs())
        assertEquals(entriesBefore, entries(playlistId))
        assertEquals(ledgerBefore, ledger(playlistId))
        assertEquals(playlistBefore, dao.getPlaylist(playlistId))
        assertEquals(2_000L, dao.getFolderLink(playlistId)?.lastSyncedAtMs)
    }

    // 6
    @Test
    fun applyFolderSync_appendsAfterManualEntriesAndKeepsTheirPositions() = runBlocking {
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            listOf(doc("doc-a", "song-a"))
        ).playlistId

        repository.saveImportedSongs(listOf(song("song-m")))
        assertTrue(repository.addSongToPlaylist(playlistId, "song-m"))
        assertTrue(repository.reorderPlaylist(playlistId, listOf("song-m", "song-a")))
        dao.updatePlaylistSong(PlaylistSongEntity(playlistId, "song-a", 7L))

        val result = repository.applyFolderSync(
            playlistId,
            listOf(doc("doc-b", "song-b"), doc("doc-c", "song-c")),
            2_000L
        )

        assertEquals(syncResult(playlistId, added = 2, newSongs = 2), result)
        assertEquals(
            listOf(
                PlaylistSongEntity(playlistId, "song-m", 0L),
                PlaylistSongEntity(playlistId, "song-a", 7L),
                PlaylistSongEntity(playlistId, "song-b", 8L),
                PlaylistSongEntity(playlistId, "song-c", 9L)
            ),
            entries(playlistId)
        )
    }

    // 7
    @Test
    fun applyFolderSync_neverOverwritesExistingSongRow() = runBlocking {
        val corrected = SongEntity(
            id = "song-1",
            uri = "content://media/external/audio/media/1",
            title = "Corrected title",
            artist = "Corrected singer",
            album = "Corrected movie",
            genre = "Film",
            year = 2001,
            durationMs = 111_000L,
            artworkUri = "content://custom/artwork/1",
            mimeType = "audio/flac",
            metadataEdited = true,
            dateAddedMs = 5L
        )
        dao.insertSong(corrected)

        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            emptyList()
        ).playlistId

        val scanned = SongEntity(
            id = "song-1",
            uri = "content://com.android.externalstorage.documents/document/other",
            title = "Raw tag title",
            artist = "Raw artist",
            album = "Raw album",
            genre = null,
            year = null,
            durationMs = 999_000L,
            artworkUri = null,
            mimeType = "audio/mpeg",
            metadataEdited = false,
            dateAddedMs = 9_999L
        )

        val result = repository.applyFolderSync(
            playlistId,
            listOf(ScannedFolderDocument("doc-1", scanned)),
            2_000L
        )

        assertEquals(syncResult(playlistId, added = 1, reused = 1), result)
        assertEquals(corrected, dao.getSong("song-1"))
        assertEquals(listOf(corrected), dao.getAllSongs())
    }

    // 8
    @Test
    fun syncWithExistingLibrarySongLinksWithoutDuplicating() = runBlocking {
        val existing = song("song-1", title = "Library title")
        dao.insertSong(existing)

        val result = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            listOf(ScannedFolderDocument("doc-1", song("song-1", title = "Folder title")))
        )

        assertEquals(syncResult(result.playlistId, added = 1, reused = 1), result)
        assertEquals(listOf(existing), dao.getAllSongs())
        assertEquals(
            listOf(PlaylistSongEntity(result.playlistId, "song-1", 0L)),
            entries(result.playlistId)
        )
        assertEquals(
            listOf(FolderDocumentEntity(result.playlistId, "doc-1", "song-1", 1_000L)),
            ledger(result.playlistId)
        )
    }

    // 9 and 10
    @Test
    fun removedPlaylistEntryKeepsLedgerAndIsNotRestored() = runBlocking {
        val documents = listOf(doc("doc-1", "song-1"), doc("doc-2", "song-2"))
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            documents
        ).playlistId

        assertTrue(repository.removeSongFromPlaylist(playlistId, "song-1"))

        // 9: the ledger row remains.
        assertEquals(
            listOf(
                FolderDocumentEntity(playlistId, "doc-1", "song-1", 1_000L),
                FolderDocumentEntity(playlistId, "doc-2", "song-2", 1_000L)
            ),
            ledger(playlistId)
        )

        // 10: syncing again does not restore it.
        val result = repository.applyFolderSync(playlistId, documents, 2_000L)

        assertEquals(syncResult(playlistId, skipped = 2), result)
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, "song-2", 1L)),
            entries(playlistId)
        )
    }

    // 11
    @Test
    fun libraryDeleteNullsLedgerSongIdAndIsNotReAdded() = runBlocking {
        val documents = listOf(doc("doc-1", "song-1"))
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            documents
        ).playlistId

        assertTrue(repository.removeSong("song-1"))

        assertEquals(
            listOf(FolderDocumentEntity(playlistId, "doc-1", null, 1_000L)),
            ledger(playlistId)
        )
        assertTrue(entries(playlistId).isEmpty())

        val result = repository.applyFolderSync(playlistId, documents, 2_000L)

        assertEquals(syncResult(playlistId, skipped = 1), result)
        assertNull(dao.getSong("song-1"))
        assertTrue(entries(playlistId).isEmpty())
        assertEquals(
            listOf(FolderDocumentEntity(playlistId, "doc-1", null, 1_000L)),
            ledger(playlistId)
        )
    }

    // 12
    @Test
    fun failedBatchRollsBackEverything() = runBlocking {
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            listOf(doc("doc-base", "song-base"))
        ).playlistId

        // In-memory Room 3 databases use a single connection, so the TEMP
        // trigger is visible to the repository's transactions.
        database.useWriterConnection { connection ->
            connection.executeSQL(
                """
                CREATE TEMP TRIGGER fail_doc BEFORE INSERT ON folder_documents
                WHEN NEW.documentId = 'doc-poison'
                BEGIN SELECT RAISE(ABORT, 'forced'); END;
                """.trimIndent()
            )
        }

        val syncError = runCatching {
            repository.applyFolderSync(
                playlistId,
                listOf(doc("doc-ok", "song-ok"), doc("doc-poison", "song-poison")),
                2_000L
            )
        }.exceptionOrNull()

        assertTrue(
            "Expected a constraint failure, got $syncError",
            syncError != null && isConstraintFailure(syncError)
        )
        assertTrue(syncError?.message.orEmpty().contains("forced"))
        assertNull(dao.getSong("song-ok"))
        assertNull(dao.getSong("song-poison"))
        assertEquals(listOf("song-base"), dao.getAllSongs().map { it.id })
        assertEquals(
            listOf(FolderDocumentEntity(playlistId, "doc-base", "song-base", 1_000L)),
            ledger(playlistId)
        )
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, "song-base", 0L)),
            entries(playlistId)
        )
        assertEquals(1_000L, dao.getFolderLink(playlistId)?.lastSyncedAtMs)

        val createError = runCatching {
            repository.createFolderPlaylist(
                "Poisoned",
                OTHER_TREE_URI,
                3_000L,
                listOf(doc("doc-ok-2", "song-ok-2"), doc("doc-poison", "song-poison"))
            )
        }.exceptionOrNull()

        assertTrue(
            "Expected a constraint failure, got $createError",
            createError != null && isConstraintFailure(createError)
        )
        assertNull(dao.getFolderLinkByTreeUri(OTHER_TREE_URI))
        assertEquals(1L, count("SELECT COUNT(*) FROM playlists"))
        assertEquals(1L, count("SELECT COUNT(*) FROM folder_playlists"))
        assertEquals(1L, count("SELECT COUNT(*) FROM folder_documents"))
        assertEquals(1L, count("SELECT COUNT(*) FROM playlist_songs"))
        assertNull(dao.getSong("song-ok-2"))
    }

    // 13
    @Test
    fun duplicateDocumentIdInOneBatchIsAddedOnce() = runBlocking {
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            emptyList()
        ).playlistId

        val result = repository.applyFolderSync(
            playlistId,
            listOf(doc("doc-1", "song-a"), doc("doc-1", "song-b")),
            2_000L
        )

        assertEquals(syncResult(playlistId, added = 1, newSongs = 1), result)
        assertEquals(
            listOf(FolderDocumentEntity(playlistId, "doc-1", "song-a", 2_000L)),
            ledger(playlistId)
        )
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, "song-a", 0L)),
            entries(playlistId)
        )
        assertNull(dao.getSong("song-b"))
    }

    // 14
    @Test
    fun twoDocumentsForSameSongGiveOneEntryAndTwoLedgerRows() = runBlocking {
        val playlistId = repository.createFolderPlaylist(
            "Car",
            TREE_URI,
            1_000L,
            emptyList()
        ).playlistId

        val result = repository.applyFolderSync(
            playlistId,
            listOf(doc("doc-1", "song-s"), doc("doc-2", "song-s")),
            2_000L
        )

        assertEquals(
            syncResult(
                playlistId,
                added = 1,
                newSongs = 1,
                reused = 1,
                alreadyInPlaylist = 1
            ),
            result
        )
        assertEquals(
            listOf(
                FolderDocumentEntity(playlistId, "doc-1", "song-s", 2_000L),
                FolderDocumentEntity(playlistId, "doc-2", "song-s", 2_000L)
            ),
            ledger(playlistId)
        )
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, "song-s", 0L)),
            entries(playlistId)
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM songs"))
    }

    // 15
    @Test
    fun applyFolderSyncOnManualPlaylistFailsWithoutWriting() = runBlocking {
        val playlistId = repository.createPlaylist("Manual")

        val error = runCatching {
            repository.applyFolderSync(
                playlistId,
                listOf(doc("doc-1", "song-1")),
                2_000L
            )
        }.exceptionOrNull()

        assertTrue(
            "Expected IllegalStateException, got $error",
            error is IllegalStateException
        )
        assertEquals("Playlist is not linked to a folder.", error?.message)
        assertEquals(0L, count("SELECT COUNT(*) FROM songs"))
        assertEquals(0L, count("SELECT COUNT(*) FROM playlist_songs"))
        assertEquals(0L, count("SELECT COUNT(*) FROM folder_documents"))
        assertEquals(0L, count("SELECT COUNT(*) FROM folder_playlists"))
        assertEquals("Manual", dao.getPlaylist(playlistId)?.name)
    }

    // Helpers

    private suspend fun entries(playlistId: Long): List<PlaylistSongEntity> {
        return dao.getPlaylistEntries(playlistId)
    }

    private suspend fun ledger(playlistId: Long): List<FolderDocumentEntity> {
        return database.useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT playlistId, documentId, songId, discoveredAtMs
                FROM folder_documents
                WHERE playlistId = ?
                ORDER BY documentId ASC
                """.trimIndent()
            ) { statement ->
                statement.bindLong(1, playlistId)
                buildList {
                    while (statement.step()) {
                        add(
                            FolderDocumentEntity(
                                playlistId = statement.getLong(0),
                                documentId = statement.getText(1),
                                songId = if (statement.isNull(2)) null else statement.getText(2),
                                discoveredAtMs = statement.getLong(3)
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun count(sql: String): Long {
        return database.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                assertTrue(statement.step())
                statement.getLong(0)
            }
        }
    }

    /** The platform driver may surface the failure as either exception type. */
    private fun isConstraintFailure(error: Throwable): Boolean {
        if (error is SQLiteConstraintException) return true
        if (error is SQLiteException &&
            error.message.orEmpty().contains("constraint", ignoreCase = true)
        ) return true
        return error.cause?.let(::isConstraintFailure) ?: false
    }

    private fun syncResult(
        playlistId: Long,
        added: Int = 0,
        skipped: Int = 0,
        newSongs: Int = 0,
        reused: Int = 0,
        alreadyInPlaylist: Int = 0
    ): FolderSyncResult {
        return FolderSyncResult(
            playlistId = playlistId,
            addedToPlaylist = added,
            skippedAlreadyKnown = skipped,
            newSongsInserted = newSongs,
            existingSongsReused = reused,
            alreadyInPlaylist = alreadyInPlaylist
        )
    }

    private fun doc(documentId: String, songId: String): ScannedFolderDocument {
        return ScannedFolderDocument(documentId, song(songId))
    }

    private fun song(id: String, title: String = "Title $id"): SongEntity {
        return SongEntity(
            id = id,
            uri = "content://test/$id",
            title = title,
            artist = "Singer",
            album = "Movie",
            genre = null,
            year = null,
            durationMs = 180_000L,
            artworkUri = null,
            mimeType = "audio/mpeg",
            dateAddedMs = 1L
        )
    }

    private companion object {
        const val TREE_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCar"
        const val OTHER_TREE_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FTrip"
    }
}
