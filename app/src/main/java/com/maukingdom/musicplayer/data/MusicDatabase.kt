package com.maukingdom.musicplayer.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        FolderPlaylistEntity::class,
        FolderDocumentEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var instance: MusicDatabase? = null

        /** Adds the folder_playlists and folder_documents tables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // SQL copied verbatim from schemas/.../2.json.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_playlists` (`playlistId` INTEGER NOT NULL, `treeUri` TEXT NOT NULL, `linkedAtMs` INTEGER NOT NULL, `lastSyncedAtMs` INTEGER, PRIMARY KEY(`playlistId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_playlists_treeUri` ON `folder_playlists` (`treeUri`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_documents` (`playlistId` INTEGER NOT NULL, `documentId` TEXT NOT NULL, `songId` TEXT, `discoveredAtMs` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `documentId`), FOREIGN KEY(`playlistId`) REFERENCES `folder_playlists`(`playlistId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folder_documents_songId` ON `folder_documents` (`songId`)"
                )
            }
        }

        /** The on-device file name; unchanged since Room 2 so existing data is reused. */
        const val DATABASE_NAME = "music_player.db"

        fun getInstance(context: Context): MusicDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, DATABASE_NAME)
                    .also { instance = it }
            }
        }

        /**
         * The one place the database is configured, shared with the tests.
         * Uses the platform SQLite driver (the same engine Room 2 used through
         * SupportSQLite), so the existing file opens in place. No destructive
         * fallback: a missing migration fails loudly instead of wiping data.
         */
        fun build(context: Context, name: String): MusicDatabase =
            Room.databaseBuilder(context, MusicDatabase::class.java, name)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
