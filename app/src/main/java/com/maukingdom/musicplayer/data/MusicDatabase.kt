package com.maukingdom.musicplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQL copied verbatim from schemas/.../2.json.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_playlists` (`playlistId` INTEGER NOT NULL, `treeUri` TEXT NOT NULL, `linkedAtMs` INTEGER NOT NULL, `lastSyncedAtMs` INTEGER, PRIMARY KEY(`playlistId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_playlists_treeUri` ON `folder_playlists` (`treeUri`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_documents` (`playlistId` INTEGER NOT NULL, `documentId` TEXT NOT NULL, `songId` TEXT, `discoveredAtMs` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `documentId`), FOREIGN KEY(`playlistId`) REFERENCES `folder_playlists`(`playlistId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folder_documents_songId` ON `folder_documents` (`songId`)"
                )
            }
        }

        fun getInstance(context: Context): MusicDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_player.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
