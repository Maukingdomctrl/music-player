package com.maukingdom.musicplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maukingdom.musicplayer.data.FolderDocumentEntity
import com.maukingdom.musicplayer.data.FolderPlaylistEntity
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.PlaylistSongEntity
import com.maukingdom.musicplayer.data.SongEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Upgrading the app from Room 2.6.1 to Room 3 must keep every existing record.
 *
 * Each test writes a database file the way Room 2 left it on the phone: the
 * tables come from the committed schema JSON (the exact SQL Room 2 generated),
 * plus Room 2's `room_master_table` identity hash and SQLite `user_version`.
 * The file is then opened with the app's own Room 3 configuration
 * ([MusicDatabase.build]) and read back through the DAO.
 *
 * Runs on the JVM with Robolectric (Android 15 / API 35) and a real SQLite
 * engine. LEGACY mode is used because Robolectric's native runtime loader
 * cannot open its jar when the user home path contains a space on Windows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RoomUpgradeDataPreservationTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private val opened = mutableListOf<MusicDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "room2-upgrade-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    // Room 2 v2 database (what current users have) opened by Room 3

    @Test
    fun room2Version2Database_opensInPlace_withEveryRecordIntact() = runBlocking {
        writeRoom2Database(version = 2) { db -> insertV2Fixture(db) }

        val dao = open().libraryDao()

        assertEquals(listOf(EDITED_SONG, PLAIN_SONG), dao.getAllSongs())
        assertEquals(listOf(PLAYLIST), dao.getAllPlaylists())
        assertEquals(
            listOf(
                PlaylistSongEntity(PLAYLIST_ID, EDITED_SONG_ID, 0L),
                PlaylistSongEntity(PLAYLIST_ID, PLAIN_SONG_ID, 1L)
            ),
            dao.getPlaylistEntries(PLAYLIST_ID)
        )
        assertEquals(FOLDER_LINK, dao.getFolderLink(PLAYLIST_ID))
        assertEquals(
            listOf("doc-edited", "doc-plain"),
            dao.getKnownDocumentIds(PLAYLIST_ID).sorted()
        )
        assertTrue(dao.isSongRecordedInFolder(PLAYLIST_ID, EDITED_SONG_ID))

        // Room 3 did not rewrite or reset the file: same version, same hash.
        assertEquals(2L, pragmaUserVersion())
        assertEquals(ROOM2_IDENTITY_HASH, storedIdentityHash())
    }

    @Test
    fun room2Database_writesAfterUpgrade_surviveCloseAndReopen() = runBlocking {
        writeRoom2Database(version = 2) { db -> insertV2Fixture(db) }

        val first = open()
        val newPlaylistId = first.libraryDao().insertPlaylist(
            PlaylistEntity(name = "Added after upgrade", createdAtMs = 1_800_000_000_000L)
        )
        first.libraryDao().insertPlaylistSong(
            PlaylistSongEntity(newPlaylistId, PLAIN_SONG_ID, 0L)
        )
        first.close()

        val dao = open().libraryDao()
        assertEquals(listOf(EDITED_SONG, PLAIN_SONG), dao.getAllSongs())
        assertEquals(
            listOf("Added after upgrade", "Road songs"),
            dao.getAllPlaylists().map { it.name }
        )
        // Autoincrement continued past the Room 2 ids.
        assertTrue(newPlaylistId > PLAYLIST_ID)
        assertEquals(listOf(PLAIN_SONG), dao.getPlaylistSongs(newPlaylistId))
        assertEquals(FOLDER_LINK, dao.getFolderLink(PLAYLIST_ID))
    }

    // Room 2 v1 database (older installs) migrated by Room 3

    @Test
    fun room2Version1Database_migratesTo2_andKeepsData() = runBlocking {
        writeRoom2Database(version = 1) { db -> insertV1Fixture(db) }

        // Opening runs MIGRATION_1_2, then Room 3 validates every table
        // against the generated schema; a mismatch would throw here.
        val dao = open().libraryDao()

        assertEquals(listOf(EDITED_SONG, PLAIN_SONG), dao.getAllSongs())
        assertEquals(listOf(PLAYLIST), dao.getAllPlaylists())
        assertEquals(
            listOf(EDITED_SONG, PLAIN_SONG),
            dao.getPlaylistSongs(PLAYLIST_ID)
        )
        assertEquals(2L, pragmaUserVersion())
        assertEquals(ROOM2_IDENTITY_HASH, storedIdentityHash())

        // The tables added by the migration are usable through the DAO.
        assertNull(dao.getFolderLink(PLAYLIST_ID))
        dao.insertFolderLink(FOLDER_LINK)
        dao.insertFolderDocument(
            FolderDocumentEntity(PLAYLIST_ID, "doc-1", EDITED_SONG_ID, 1L)
        )
        assertEquals(FOLDER_LINK, dao.getFolderLinkByTreeUri(TREE_URI))
        assertEquals(listOf("doc-1"), dao.getKnownDocumentIds(PLAYLIST_ID))
    }

    /**
     * Room checks indices when it opens a migrated database, but only where
     * the SQLite build reports them (the JVM test engine does not). This
     * compares them directly: name, uniqueness and columns must match 2.json.
     */
    @Test
    fun room2Version1Database_migratedIndicesMatchVersion2Schema() = runBlocking {
        writeRoom2Database(version = 1) { db -> insertV1Fixture(db) }
        open().libraryDao().getAllSongs() // triggers open + MIGRATION_1_2

        val expected = buildSet {
            val entities = JSONObject(schemaFile(2).readText())
                .getJSONObject("database").getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    val columns = index.getJSONArray("columnNames")
                    add(
                        IndexShape(
                            table = entity.getString("tableName"),
                            name = index.getString("name"),
                            unique = index.getBoolean("unique"),
                            columns = List(columns.length()) { columns.getString(it) }
                        )
                    )
                }
            }
        }
        assertTrue("2.json declares no indices", expected.isNotEmpty())
        assertEquals(expected, actualIndices(expected.map { it.table }.toSet()))
    }

    // Behaviour the app relies on, re-checked on Room 3

    @Test
    fun foreignKeyActions_stillApplyOnUpgradedDatabase() = runBlocking {
        writeRoom2Database(version = 2) { db -> insertV2Fixture(db) }
        val database = open()
        val dao = database.libraryDao()

        // ON DELETE SET NULL: deleting a song keeps the folder document.
        dao.deleteSong(PLAIN_SONG_ID)
        assertEquals(
            listOf(null),
            database.useReaderConnection { connection ->
                connection.usePrepared(
                    "SELECT songId FROM folder_documents WHERE documentId = 'doc-plain'"
                ) { statement ->
                    buildList {
                        while (statement.step()) {
                            add(if (statement.isNull(0)) null else statement.getText(0))
                        }
                    }
                }
            }
        )
        // ON DELETE CASCADE: the song's playlist entry is gone.
        assertEquals(listOf(EDITED_SONG), dao.getPlaylistSongs(PLAYLIST_ID))

        // ON DELETE CASCADE: deleting the playlist removes its folder link
        // and folder documents, but no songs.
        dao.deletePlaylist(PLAYLIST_ID)
        assertNull(dao.getFolderLink(PLAYLIST_ID))
        assertEquals(emptyList<String>(), dao.getKnownDocumentIds(PLAYLIST_ID))
        assertEquals(listOf(EDITED_SONG), dao.getAllSongs())
    }

    @Test
    fun uniqueTreeUri_isStillEnforced() = runBlocking {
        writeRoom2Database(version = 2) { db -> insertV2Fixture(db) }
        val dao = open().libraryDao()
        val otherPlaylist = dao.insertPlaylist(PlaylistEntity(name = "Other", createdAtMs = 1L))

        val error = runCatching {
            dao.insertFolderLink(FolderPlaylistEntity(otherPlaylist, TREE_URI, 2L, null))
        }.exceptionOrNull()

        assertNotNull("Duplicate tree URI was accepted", error)
        assertTrue(
            "Expected a UNIQUE constraint failure, got $error",
            isConstraintFailure(error!!)
        )
        assertNull(dao.getFolderLink(otherPlaylist))
    }

    @Test
    fun failedWriteTransaction_rollsBackEverything() = runBlocking {
        writeRoom2Database(version = 2) { db -> insertV2Fixture(db) }
        val database = open()
        val dao = database.libraryDao()

        val error = runCatching {
            database.withWriteTransaction {
                dao.insertPlaylist(PlaylistEntity(name = "Half-written", createdAtMs = 3L))
                dao.renamePlaylist(PLAYLIST_ID, "Renamed")
                error("forced failure")
            }
        }.exceptionOrNull()

        assertEquals("forced failure", error?.message)
        assertEquals(listOf(PLAYLIST), dao.getAllPlaylists())
    }

    // Building the Room 2 file

    /**
     * Writes [dbName] exactly as Room 2.6.1 creates it for schema [version]:
     * the schema's `createSql` statements, `room_master_table`, and
     * `PRAGMA user_version`. [fill] then inserts user data with raw SQL.
     */
    private fun writeRoom2Database(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.beginTransaction()
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            fill(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun schemaFile(version: Int): File {
        // Unit tests run with the module directory as the working directory.
        val file = File("schemas/com.maukingdom.musicplayer.data.MusicDatabase/$version.json")
        check(file.isFile) { "Missing committed schema ${file.absolutePath}" }
        return file
    }

    private fun insertV1Fixture(db: SQLiteDatabase) {
        insertSong(db, EDITED_SONG)
        insertSong(db, PLAIN_SONG)
        db.execSQL(
            "INSERT INTO playlists (id, name, createdAtMs) VALUES (?, ?, ?)",
            arrayOf<Any?>(PLAYLIST.id, PLAYLIST.name, PLAYLIST.createdAtMs)
        )
        insertEntry(db, EDITED_SONG_ID, 0L)
        insertEntry(db, PLAIN_SONG_ID, 1L)
    }

    private fun insertV2Fixture(db: SQLiteDatabase) {
        insertV1Fixture(db)
        db.execSQL(
            "INSERT INTO folder_playlists (playlistId, treeUri, linkedAtMs, lastSyncedAtMs) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(
                FOLDER_LINK.playlistId,
                FOLDER_LINK.treeUri,
                FOLDER_LINK.linkedAtMs,
                FOLDER_LINK.lastSyncedAtMs
            )
        )
        for ((docId, songId) in listOf("doc-edited" to EDITED_SONG_ID, "doc-plain" to PLAIN_SONG_ID)) {
            db.execSQL(
                "INSERT INTO folder_documents (playlistId, documentId, songId, discoveredAtMs) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(PLAYLIST_ID, docId, songId, 1_700_000_350_000L)
            )
        }
    }

    private fun insertSong(db: SQLiteDatabase, song: SongEntity) {
        db.execSQL(
            """
            INSERT INTO songs (
                id, uri, title, artist, album, genre, year, durationMs,
                artworkUri, mimeType, metadataEdited, dateAddedMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                song.id, song.uri, song.title, song.artist, song.album, song.genre,
                song.year, song.durationMs, song.artworkUri, song.mimeType,
                if (song.metadataEdited) 1 else 0, song.dateAddedMs
            )
        )
    }

    private fun insertEntry(db: SQLiteDatabase, songId: String, position: Long) {
        db.execSQL(
            "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)",
            arrayOf<Any?>(PLAYLIST_ID, songId, position)
        )
    }

    // Opening with Room 3 and inspecting the file

    private fun open(): MusicDatabase =
        MusicDatabase.build(context, dbName).also { opened += it }

    private suspend fun pragmaUserVersion(): Long = scalarLong("PRAGMA user_version")

    private suspend fun storedIdentityHash(): String =
        opened.last().useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ) { statement ->
                check(statement.step()) { "room_master_table is empty" }
                statement.getText(0)
            }
        }

    private suspend fun scalarLong(sql: String): Long =
        opened.last().useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

    private data class IndexShape(
        val table: String,
        val name: String,
        val unique: Boolean,
        val columns: List<String>
    )

    /** User-created (non-autoindex) indices on [tables], read from the file. */
    private suspend fun actualIndices(tables: Set<String>): Set<IndexShape> =
        opened.last().useReaderConnection { connection ->
            buildSet {
                for (table in tables) {
                    val listed = connection.usePrepared("PRAGMA index_list(`$table`)") { st ->
                        val nameCol = (0 until st.getColumnCount()).first { st.getColumnName(it) == "name" }
                        val uniqueCol = (0 until st.getColumnCount()).first { st.getColumnName(it) == "unique" }
                        buildList {
                            while (st.step()) add(st.getText(nameCol) to (st.getLong(uniqueCol) == 1L))
                        }
                    }
                    for ((name, unique) in listed) {
                        if (name.startsWith("sqlite_autoindex")) continue
                        val columns = connection.usePrepared("PRAGMA index_info(`$name`)") { st ->
                            val seqCol = (0 until st.getColumnCount()).first { st.getColumnName(it) == "seqno" }
                            val nameCol = (0 until st.getColumnCount()).first { st.getColumnName(it) == "name" }
                            buildList { while (st.step()) add(st.getLong(seqCol) to st.getText(nameCol)) }
                                .sortedBy { it.first }.map { it.second }
                        }
                        add(IndexShape(table, name, unique, columns))
                    }
                }
            }
        }

    private fun isConstraintFailure(error: Throwable): Boolean {
        if (error is android.database.sqlite.SQLiteConstraintException) return true
        if (error is androidx.sqlite.SQLiteException &&
            error.message.orEmpty().contains("constraint", ignoreCase = true)
        ) return true
        return error.cause?.let(::isConstraintFailure) ?: false
    }

    private companion object {
        /** identityHash from schemas/.../2.json, written by Room 2.6.1. */
        const val ROOM2_IDENTITY_HASH = "3e22f105e83af72421e417907d69c738"

        const val PLAYLIST_ID = 7L
        const val EDITED_SONG_ID = "content://media/external/audio/media/101"
        const val PLAIN_SONG_ID = "content://media/external/audio/media/102"
        const val TREE_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCar"

        val EDITED_SONG = SongEntity(
            id = EDITED_SONG_ID,
            uri = EDITED_SONG_ID,
            title = "Corrected title",
            artist = "Corrected singer",
            album = "Corrected movie",
            genre = "Film",
            year = 2001,
            durationMs = 215_000L,
            artworkUri = "content://media/external/audio/albumart/7",
            mimeType = "audio/mpeg",
            metadataEdited = true,
            dateAddedMs = 1_700_000_000_000L
        )

        val PLAIN_SONG = SongEntity(
            id = PLAIN_SONG_ID,
            uri = PLAIN_SONG_ID,
            title = "Plain title",
            artist = "Plain singer",
            album = "Plain movie",
            genre = null,
            year = null,
            durationMs = 180_000L,
            artworkUri = null,
            mimeType = "audio/flac",
            metadataEdited = false,
            dateAddedMs = 1_700_000_100_000L
        )

        val PLAYLIST = PlaylistEntity(
            id = PLAYLIST_ID,
            name = "Road songs",
            createdAtMs = 1_700_000_200_000L
        )

        val FOLDER_LINK = FolderPlaylistEntity(
            playlistId = PLAYLIST_ID,
            treeUri = TREE_URI,
            linkedAtMs = 1_700_000_300_000L,
            lastSyncedAtMs = 1_700_000_400_000L
        )
    }
}
