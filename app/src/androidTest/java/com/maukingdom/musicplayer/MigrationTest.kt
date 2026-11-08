package com.maukingdom.musicplayer

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-test-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        context.getDatabasePath(dbName),
        AndroidSQLiteDriver(),
        MusicDatabase::class
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_preservesExistingData() = runBlocking {
        helper.createDatabase(1).apply {
            insertV1Fixture(this)
            close()
        }

        val db = helper.runMigrationsAndValidate(2, listOf(MusicDatabase.MIGRATION_1_2))

        try {
            assertEquals(
                EDITED_SONG_ROW,
                readRow(db, "$SONG_COLUMNS WHERE id = ?", EDITED_SONG_ID)
            )
            assertEquals(
                PLAIN_SONG_ROW,
                readRow(db, "$SONG_COLUMNS WHERE id = ?", PLAIN_SONG_ID)
            )
            assertEquals(2L, count(db, "SELECT COUNT(*) FROM songs"))

            assertEquals(
                PLAYLIST_ROW,
                readRow(
                    db,
                    "SELECT id, name, createdAtMs FROM playlists WHERE id = ?",
                    PLAYLIST_ID
                )
            )
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM playlists"))

            assertEquals(
                listOf(
                    listOf<Any?>(PLAYLIST_ID, EDITED_SONG_ID, 0L),
                    listOf<Any?>(PLAYLIST_ID, PLAIN_SONG_ID, 1L)
                ),
                readRows(
                    db,
                    """
                    SELECT playlistId, songId, position FROM playlist_songs
                    ORDER BY position ASC
                    """
                )
            )

            assertEquals(1L, tableExists(db, "folder_playlists"))
            assertEquals(1L, tableExists(db, "folder_documents"))
            assertEquals(0L, count(db, "SELECT COUNT(*) FROM folder_playlists"))
            assertEquals(0L, count(db, "SELECT COUNT(*) FROM folder_documents"))
        } finally {
            db.close()
        }
    }

    @Test
    fun migratedDatabase_opensWithRoom() = runBlocking {
        helper.createDatabase(1).apply {
            insertSong(this, EDITED_SONG_ROW)
            close()
        }

        // The app's own configuration: Room 3, platform driver, MIGRATION_1_2.
        val database = MusicDatabase.build(context, dbName)

        try {
            val songs = database.libraryDao().getAllSongs()

            assertEquals(
                listOf(
                    SongEntity(
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
                ),
                songs
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun folderTables_foreignKeyBehaviour() = runBlocking {
        helper.createDatabase(1).apply {
            insertV1Fixture(this)
            close()
        }

        val db = helper.runMigrationsAndValidate(2, listOf(MusicDatabase.MIGRATION_1_2))

        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(1L, count(db, "PRAGMA foreign_keys"))

            db.exec(
                """
                INSERT INTO folder_playlists
                    (playlistId, treeUri, linkedAtMs, lastSyncedAtMs)
                VALUES (?, ?, ?, ?)
                """,
                PLAYLIST_ID, TREE_URI, 1_700_000_300_000L, null
            )
            insertFolderDocument(db, "doc-edited", EDITED_SONG_ID)
            insertFolderDocument(db, "doc-plain", PLAIN_SONG_ID)

            // Removing a song from the playlist keeps the folder document.
            db.exec(
                "DELETE FROM playlist_songs WHERE playlistId = ? AND songId = ?",
                PLAYLIST_ID, EDITED_SONG_ID
            )
            assertEquals(
                listOf<Any?>(EDITED_SONG_ID),
                readRow(
                    db,
                    "SELECT songId FROM folder_documents WHERE documentId = ?",
                    "doc-edited"
                )
            )

            // Deleting a song nulls the folder document's songId and still
            // cascades its playlist_songs rows.
            db.exec("DELETE FROM songs WHERE id = ?", PLAIN_SONG_ID)
            assertEquals(
                listOf<Any?>(null),
                readRow(
                    db,
                    "SELECT songId FROM folder_documents WHERE documentId = ?",
                    "doc-plain"
                )
            )
            assertEquals(
                0L,
                count(
                    db,
                    "SELECT COUNT(*) FROM playlist_songs WHERE songId = ?",
                    PLAIN_SONG_ID
                )
            )

            // A tree URI can only be linked to one playlist.
            db.exec(
                "INSERT INTO playlists (id, name, createdAtMs) VALUES (?, ?, ?)",
                SECOND_PLAYLIST_ID, "Other", 1_700_000_400_000L
            )
            val duplicateError = runCatching {
                db.exec(
                    """
                    INSERT INTO folder_playlists
                        (playlistId, treeUri, linkedAtMs, lastSyncedAtMs)
                    VALUES (?, ?, ?, ?)
                    """,
                    SECOND_PLAYLIST_ID, TREE_URI, 1_700_000_500_000L, null
                )
            }.exceptionOrNull()
            assertTrue(
                "Expected a UNIQUE constraint failure, got $duplicateError",
                duplicateError != null && isConstraintFailure(duplicateError)
            )

            // Deleting the playlist cascades to the folder link and documents.
            db.exec("DELETE FROM playlists WHERE id = ?", PLAYLIST_ID)
            assertEquals(
                0L,
                count(
                    db,
                    "SELECT COUNT(*) FROM folder_playlists WHERE playlistId = ?",
                    PLAYLIST_ID
                )
            )
            assertEquals(
                0L,
                count(
                    db,
                    "SELECT COUNT(*) FROM folder_documents WHERE playlistId = ?",
                    PLAYLIST_ID
                )
            )

            // The remaining song is untouched.
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM songs"))
            assertEquals(
                EDITED_SONG_ROW,
                readRow(db, "$SONG_COLUMNS WHERE id = ?", EDITED_SONG_ID)
            )
        } finally {
            db.close()
        }
    }

    // Fixture (raw SQL matching 1.json columns)

    private fun insertV1Fixture(db: SQLiteConnection) {
        insertSong(db, EDITED_SONG_ROW)
        insertSong(db, PLAIN_SONG_ROW)

        db.exec(
            "INSERT INTO playlists (id, name, createdAtMs) VALUES (?, ?, ?)",
            *PLAYLIST_ROW.toTypedArray()
        )
        db.exec(
            "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)",
            PLAYLIST_ID, EDITED_SONG_ID, 0L
        )
        db.exec(
            "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)",
            PLAYLIST_ID, PLAIN_SONG_ID, 1L
        )
    }

    private fun insertSong(db: SQLiteConnection, row: List<Any?>) {
        db.exec(
            """
            INSERT INTO songs (
                id, uri, title, artist, album, genre, year, durationMs,
                artworkUri, mimeType, metadataEdited, dateAddedMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            *row.toTypedArray()
        )
    }

    private fun insertFolderDocument(
        db: SQLiteConnection,
        documentId: String,
        songId: String
    ) {
        db.exec(
            """
            INSERT INTO folder_documents
                (playlistId, documentId, songId, discoveredAtMs)
            VALUES (?, ?, ?, ?)
            """,
            PLAYLIST_ID, documentId, songId, 1_700_000_350_000L
        )
    }

    // SQL helpers (Room 3 / androidx.sqlite driver API)

    /** Runs one statement with bound [args]. */
    private fun SQLiteConnection.exec(sql: String, vararg args: Any?) {
        prepare(sql.trimIndent()).use { statement ->
            statement.bindAll(args)
            statement.step()
        }
    }

    private fun SQLiteStatement.bindAll(args: Array<out Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> bindNull(index)
                is Long -> bindLong(index, value)
                is Int -> bindLong(index, value.toLong())
                is Boolean -> bindLong(index, if (value) 1L else 0L)
                is Double -> bindDouble(index, value)
                is String -> bindText(index, value)
                is ByteArray -> bindBlob(index, value)
                else -> error("Unsupported bind type ${value::class}")
            }
        }
    }

    private fun readRows(
        db: SQLiteConnection,
        sql: String,
        vararg args: Any?
    ): List<List<Any?>> {
        return db.prepare(sql.trimIndent()).use { statement ->
            statement.bindAll(args)
            buildList {
                while (statement.step()) {
                    add((0 until statement.getColumnCount()).map { statement.valueAt(it) })
                }
            }
        }
    }

    private fun readRow(
        db: SQLiteConnection,
        sql: String,
        vararg args: Any?
    ): List<Any?> {
        val rows = readRows(db, sql, *args)
        assertEquals("Expected exactly one row for: $sql", 1, rows.size)
        return rows.single()
    }

    private fun count(db: SQLiteConnection, sql: String, vararg args: Any?): Long {
        return readRow(db, sql, *args).single() as Long
    }

    private fun tableExists(db: SQLiteConnection, table: String): Long {
        return count(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            table
        )
    }

    /** Returns the stored value with its SQLite storage type preserved. */
    private fun SQLiteStatement.valueAt(index: Int): Any? = when (getColumnType(index)) {
        SQLITE_DATA_NULL -> null
        SQLITE_DATA_INTEGER -> getLong(index)
        SQLITE_DATA_FLOAT -> getDouble(index)
        SQLITE_DATA_TEXT -> getText(index)
        else -> getBlob(index)
    }

    /** The platform driver may surface the failure as either exception type. */
    private fun isConstraintFailure(error: Throwable): Boolean {
        if (error is SQLiteConstraintException) return true
        if (error is SQLiteException &&
            error.message.orEmpty().contains("constraint", ignoreCase = true)
        ) return true
        return error.cause?.let(::isConstraintFailure) ?: false
    }

    private companion object {
        const val PLAYLIST_ID = 7L
        const val SECOND_PLAYLIST_ID = 8L
        const val EDITED_SONG_ID = "content://media/external/audio/media/101"
        const val PLAIN_SONG_ID = "content://media/external/audio/media/102"
        const val TREE_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCar"

        const val SONG_COLUMNS = """
            SELECT id, uri, title, artist, album, genre, year, durationMs,
                artworkUri, mimeType, metadataEdited, dateAddedMs
            FROM songs
        """

        val EDITED_SONG_ROW = listOf<Any?>(
            EDITED_SONG_ID,
            EDITED_SONG_ID,
            "Corrected title",
            "Corrected singer",
            "Corrected movie",
            "Film",
            2001L,
            215_000L,
            "content://media/external/audio/albumart/7",
            "audio/mpeg",
            1L,
            1_700_000_000_000L
        )

        val PLAIN_SONG_ROW = listOf<Any?>(
            PLAIN_SONG_ID,
            PLAIN_SONG_ID,
            "Plain title",
            "Plain singer",
            "Plain movie",
            null,
            null,
            180_000L,
            null,
            "audio/flac",
            0L,
            1_700_000_100_000L
        )

        val PLAYLIST_ROW = listOf<Any?>(PLAYLIST_ID, "Road songs", 1_700_000_200_000L)
    }
}
