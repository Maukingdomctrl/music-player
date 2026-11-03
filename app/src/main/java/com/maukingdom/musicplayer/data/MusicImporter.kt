package com.maukingdom.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The result of an import operation. Individual failures are reported so
 * callers can explain partial imports to the user.
 */
data class ImportResult(
    val importedCount: Int,
    val failures: List<ImportFailure>
)

data class ImportFailure(
    val source: String,
    val message: String
)

/**
 * Imports local MediaStore audio or user-selected SAF document URIs.
 *
 * Identity matching is deliberately conservative:
 *
 * 1. An exact URI match is accepted.
 * 2. On Android 10 and later, MediaStore.getMediaUri() may provide an
 *    equivalent MediaStore URI for supported document providers.
 * 3. If matching is ambiguous or cannot be established, the new URI is
 *    retained as a separate library entry.
 *
 * Existing duplicate records are never merged or deleted here.
 */
class MusicImporter(
    context: Context,
    private val repository: MusicRepository,
    private val metadataReader: MetadataReader = MetadataReader(context)
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun scanMediaStore(): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<SongEntity>()
        val failures = mutableListOf<ImportFailure>()

        /*
         * The list includes existing records and records read earlier in this
         * import. This permits conservative matching within the same import
         * without changing or merging existing database records.
         */
        val knownSongs = repository.getAllSongs().toMutableList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        try {
            val cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )

            if (cursor == null) {
                return@withContext ImportResult(
                    importedCount = 0,
                    failures = listOf(
                        ImportFailure(
                            source = "MediaStore",
                            message = "The media provider returned no result."
                        )
                    )
                )
            }

            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )
                val displayNameColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DISPLAY_NAME
                )
                val titleColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )
                val artistColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )
                val albumColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM
                )
                val albumIdColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM_ID
                )
                val durationColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DURATION
                )
                val mimeTypeColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.MIME_TYPE
                )

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val albumId = if (it.isNull(albumIdColumn)) {
                        null
                    } else {
                        it.getLong(albumIdColumn).takeIf { value -> value > 0 }
                    }

                    val artworkUri = albumId?.let { value ->
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            value
                        ).toString()
                    }

                    val fallback = MetadataFallback(
                        displayName = it.getString(displayNameColumn),
                        title = it.getString(titleColumn),
                        artist = it.getString(artistColumn),
                        album = it.getString(albumColumn),
                        durationMs = if (it.isNull(durationColumn)) {
                            null
                        } else {
                            it.getLong(durationColumn)
                        },
                        artworkUri = artworkUri,
                        mimeType = it.getString(mimeTypeColumn)
                    )

                    try {
                        val readSong = metadataReader.read(uri, fallback)
                        val matchedSong = matchExistingSong(uri, knownSongs)
                        val preparedSong = preserveExistingIdentity(
                            readSong = readSong,
                            existingSong = matchedSong
                        )

                        imported += preparedSong
                        rememberImportedSong(knownSongs, preparedSong)
                    } catch (error: Exception) {
                        failures += ImportFailure(
                            source = uri.toString(),
                            message = error.message ?: "Could not read audio."
                        )
                    }
                }
            }
        } catch (error: SecurityException) {
            failures += ImportFailure(
                source = "MediaStore",
                message = "Audio-library permission is missing or was revoked."
            )
        } catch (error: Exception) {
            failures += ImportFailure(
                source = "MediaStore",
                message = error.message ?: "Could not scan the audio library."
            )
        }

        repository.saveImportedSongs(imported)

        ImportResult(
            importedCount = imported.map { it.id }.distinct().size,
            failures = failures
        )
    }

    /**
     * Imports document URIs returned by the system file picker.
     *
     * Persistable access is attempted only when the provider supplied the
     * required flags. A selected document may still be imported without a
     * persistable grant, but access might not survive a restart.
     */
    suspend fun importDocuments(
        uris: List<Uri>,
        resultFlags: Int
    ): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<SongEntity>()
        val failures = mutableListOf<ImportFailure>()
        val knownSongs = repository.getAllSongs().toMutableList()

        for (uri in uris.distinct()) {
            if (uri.scheme != "content") {
                failures += ImportFailure(
                    source = uri.toString(),
                    message = "Only content-provider document URIs are supported."
                )
                continue
            }

            val hasReadGrant =
                resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            val hasPersistableGrant =
                resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0

            if (hasReadGrant && hasPersistableGrant) {
                try {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (error: SecurityException) {
                    failures += ImportFailure(
                        source = uri.toString(),
                        message = "Persistent access could not be retained."
                    )
                }
            } else {
                failures += ImportFailure(
                    source = uri.toString(),
                    message = "Persistent access was not offered by the file provider."
                )
            }

            try {
                val fallback = MetadataFallback(
                    displayName = readDisplayName(uri),
                    mimeType = resolver.getType(uri)
                )

                val readSong = metadataReader.read(uri, fallback)
                val matchedSong = matchExistingSong(uri, knownSongs)
                val preparedSong = preserveExistingIdentity(
                    readSong = readSong,
                    existingSong = matchedSong
                )

                imported += preparedSong
                rememberImportedSong(knownSongs, preparedSong)
            } catch (error: Exception) {
                failures += ImportFailure(
                    source = uri.toString(),
                    message = error.message ?: "Could not read the selected file."
                )
            }
        }

        repository.saveImportedSongs(imported)

        ImportResult(
            importedCount = imported.map { it.id }.distinct().size,
            failures = failures
        )
    }

    /**
     * Finds an existing record only when there is one unambiguous match.
     *
     * Exact stored IDs or playback URIs are checked first. If multiple
     * existing records match, none is selected automatically.
     */
    private fun matchExistingSong(
        incomingUri: Uri,
        knownSongs: List<SongEntity>
    ): SongEntity? {
        val incomingString = incomingUri.toString()

        val exactMatches = knownSongs.filter { song ->
            song.id == incomingString || song.uri == incomingString
        }

        if (exactMatches.size == 1) {
            return exactMatches.single()
        }

        if (exactMatches.size > 1) {
            return null
        }

        val incomingMediaUri = equivalentMediaStoreUri(incomingUri)
            ?: return null

        val mediaMatches = knownSongs.filter { song ->
            equivalentMediaStoreUri(Uri.parse(song.uri)) == incomingMediaUri
        }

        return mediaMatches.singleOrNull()
    }

    /**
     * Returns the URI itself for MediaStore URIs, or asks Android for an
     * equivalent MediaStore URI for supported document providers.
     *
     * This does not infer identity from filenames or metadata, and does not
     * assume that different MediaStore URI strings are equivalent.
     */
    private fun equivalentMediaStoreUri(uri: Uri): String? {
        if (uri.scheme != "content") return null

        if (uri.authority == "media") {
            return uri.toString()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        return try {
            if (!DocumentsContract.isDocumentUri(appContext, uri)) {
                null
            } else {
                MediaStore.getMediaUri(appContext, uri)?.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Retains an existing record ID so Room playlist foreign keys and saved
     * queue IDs continue to refer to the same record.
     *
     * If the existing playback URI can still be opened, keep it. Otherwise,
     * use the newly imported URI, which was just successfully read.
     */
    private fun preserveExistingIdentity(
        readSong: SongEntity,
        existingSong: SongEntity?
    ): SongEntity {
        if (existingSong == null) return readSong

        val playbackUri = if (canOpen(existingSong.uri)) {
            existingSong.uri
        } else {
            readSong.uri
        }

        return readSong.copy(
            id = existingSong.id,
            uri = playbackUri
        )
    }

    private fun canOpen(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            resolver.openAssetFileDescriptor(uri, "r")?.use {
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Replaces the in-memory representation of the same library ID, but
     * does not remove any other record that might represent the same file.
     */
    private fun rememberImportedSong(
        knownSongs: MutableList<SongEntity>,
        importedSong: SongEntity
    ) {
        val index = knownSongs.indexOfFirst { it.id == importedSong.id }

        if (index >= 0) {
            knownSongs[index] = importedSong
        } else {
            knownSongs += importedSong
        }
    }

    private fun readDisplayName(uri: Uri): String? {
        return try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (column >= 0 && cursor.moveToFirst()) {
                    cursor.getString(column)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
