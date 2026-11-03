package com.maukingdom.musicplayer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MusicDatabase::class.java
    )

    private lateinit var context: Context
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "migration-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_preservesExistingData() {
        helper.createDatabase(dbName, 1).apply {
            insertV1Fixture(this)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            MusicDatabase.MIGRATION_1_2
        )

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
    fun migratedDatabase_opensWithRoom() {
        helper.createDatabase(dbName, 1).apply {
            insertSong(this, EDITED_SONG_ROW)
            close()
        }

        val database = Room.databaseBuilder(
            context,
            MusicDatabase::class.java,
            dbName
        )
            .addMigrations(MusicDatabase.MIGRATION_1_2)
            .build()

        try {
            val songs = runBlocking { database.libraryDao().getAllSongs() }

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
    fun folderTables_foreignKeyBehaviour() {
        helper.createDatabase(dbName, 1).apply {
            insertV1Fixture(this)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            2,
            true,
            MusicDatabase.MIGRATION_1_2
        )

        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(1L, count(db, "PRAGMA foreign_keys"))

            db.execSQL(
                """
                INSERT INTO folder_playlists
                    (playlistId, treeUri, linkedAtMs, lastSyncedAtMs)
                VALUES (?, ?, ?, ?)
                """,
                arrayOf<Any?>(PLAYLIST_ID, TREE_URI, 1_700_000_300_000L, null)
            )
            insertFolderDocument(db, "doc-edited", EDITED_SONG_ID)
            insertFolderDocument(db, "doc-plain", PLAIN_SONG_ID)

            // Removing a song from the playlist keeps the folder document.
            db.execSQL(
                "DELETE FROM playlist_songs WHERE playlistId = ? AND songId = ?",
                arrayOf<Any?>(PLAYLIST_ID, EDITED_SONG_ID)
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
            db.execSQL("DELETE FROM songs WHERE id = ?", arrayOf<Any?>(PLAIN_SONG_ID))
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
            db.execSQL(
                "INSERT INTO playlists (id, name, createdAtMs) VALUES (?, ?, ?)",
                arrayOf<Any?>(SECOND_PLAYLIST_ID, "Other", 1_700_000_400_000L)
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    """
                    INSERT INTO folder_playlists
                        (playlistId, treeUri, linkedAtMs, lastSyncedAtMs)
                    VALUES (?, ?, ?, ?)
                    """,
                    arrayOf<Any?>(SECOND_PLAYLIST_ID, TREE_URI, 1_700_000_500_000L, null)
                )
            }

            // Deleting the playlist cascades to the folder link and documents.
            db.execSQL("DELETE FROM playlists WHERE id = ?", arrayOf<Any?>(PLAYLIST_ID))
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

    private fun insertV1Fixture(db: SupportSQLiteDatabase) {
        insertSong(db, EDITED_SONG_ROW)
        insertSong(db, PLAIN_SONG_ROW)

        db.execSQL(
            "INSERT INTO playlists (id, name, createdAtMs) VALUES (?, ?, ?)",
            PLAYLIST_ROW.toTypedArray()
        )
        db.execSQL(
            "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)",
            arrayOf<Any?>(PLAYLIST_ID, EDITED_SONG_ID, 0L)
        )
        db.execSQL(
            "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (?, ?, ?)",
            arrayOf<Any?>(PLAYLIST_ID, PLAIN_SONG_ID, 1L)
        )
    }

    private fun insertSong(db: SupportSQLiteDatabase, row: List<Any?>) {
        db.execSQL(
            """
            INSERT INTO songs (
                id, uri, title, artist, album, genre, year, durationMs,
                artworkUri, mimeType, metadataEdited, dateAddedMs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            row.toTypedArray()
        )
    }

    private fun insertFolderDocument(
        db: SupportSQLiteDatabase,
        documentId: String,
        songId: String
    ) {
        db.execSQL(
            """
            INSERT INTO folder_documents
                (playlistId, documentId, songId, discoveredAtMs)
            VALUES (?, ?, ?, ?)
            """,
            arrayOf<Any?>(PLAYLIST_ID, documentId, songId, 1_700_000_350_000L)
        )
    }

    // Query helpers

    private fun readRows(
        db: SupportSQLiteDatabase,
        sql: String,
        vararg args: Any?
    ): List<List<Any?>> {
        return db.query(sql.trimIndent(), args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { cursor.valueAt(it) })
                }
            }
        }
    }

    private fun readRow(
        db: SupportSQLiteDatabase,
        sql: String,
        vararg args: Any?
    ): List<Any?> {
        val rows = readRows(db, sql, *args)
        assertEquals("Expected exactly one row for: $sql", 1, rows.size)
        return rows.single()
    }

    private fun count(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): Long {
        return readRow(db, sql, *args).single() as Long
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Long {
        return count(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            table
        )
    }

    /** Returns the stored value with its SQLite storage type preserved. */
    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        else -> getBlob(index)
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
