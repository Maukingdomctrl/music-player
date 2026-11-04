package com.maukingdom.musicplayer.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A locally accessible audio file.
 *
 * The content URI is used as the stable library ID. Metadata edits are stored
 * in Room; the original audio file is never modified.
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey
    val id: String,

    val uri: String,

    val title: String,
    val artist: String,
    val album: String,

    val genre: String?,
    val year: Int?,

    val durationMs: Long,
    val artworkUri: String?,
    val mimeType: String?,

    /**
     * When true, a subsequent import keeps the user's corrected descriptive
     * metadata rather than overwriting it with tags from the source file.
     */
    val metadataEdited: Boolean = false,

    val dateAddedMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId", "position"]),
        Index(value = ["songId"])
    ]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Long
)

/**
 * Editable descriptive fields. Duration and artwork are read from the file
 * or media provider and are not changed by the metadata editor.
 */
data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String?,
    val year: Int?
)

/** Query result (not a table): how many songs a playlist holds. */
data class PlaylistSongCount(
    val playlistId: Long,
    val songCount: Int
)

/**
 * Links a playlist to a user-selected SAF document tree. At most one playlist
 * may be linked to a given tree URI.
 */
@Entity(
    tableName = "folder_playlists",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["treeUri"], unique = true)
    ]
)
data class FolderPlaylistEntity(
    @PrimaryKey
    val playlistId: Long,

    val treeUri: String,
    val linkedAtMs: Long,
    val lastSyncedAtMs: Long?
)

/**
 * A document already seen in a linked folder. [songId] becomes null if the
 * song is deleted from the library, so the document is still known and is
 * not re-imported on the next sync.
 */
@Entity(
    tableName = "folder_documents",
    primaryKeys = ["playlistId", "documentId"],
    foreignKeys = [
        ForeignKey(
            entity = FolderPlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["songId"])
    ]
)
data class FolderDocumentEntity(
    val playlistId: Long,
    val documentId: String,
    val songId: String?,
    val discoveredAtMs: Long
)
