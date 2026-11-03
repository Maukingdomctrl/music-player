package com.maukingdom.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The result of importing or syncing a linked folder. Files listed in
 * [failures] were not recorded, so they are tried again on the next sync.
 */
data class FolderImportResult(
    val sync: FolderSyncResult,
    val scannedAudioFiles: Int,
    val failures: List<ImportFailure>
)

/**
 * Finds the existing library record for an incoming URI during one import
 * or sync run. Pure Kotlin, so it is unit-tested on the JVM.
 *
 * Rules (unchanged from before):
 * 1. Exactly one record whose ID or playback URI equals the incoming URI.
 *    Several such records: no match.
 * 2. Otherwise, exactly one record whose equivalent MediaStore URI equals
 *    the incoming one's. None or several: no match.
 *
 * [mediaUriOf] (a binder call on Android) is asked at most once per distinct
 * URI string per run, instead of once per known song for every new file.
 */
internal class SongMatcher(
    knownSongs: List<SongEntity>,
    private val mediaUriOf: (String) -> String?
) {
    private val songs = knownSongs.toMutableList()
    private val mediaUris = HashMap<String, String?>()

    fun match(incomingUri: String): SongEntity? {
        val exactMatches = songs.filter { song ->
            song.id == incomingUri || song.uri == incomingUri
        }

        if (exactMatches.size == 1) return exactMatches.single()
        if (exactMatches.size > 1) return null

        val incomingMediaUri = cachedMediaUri(incomingUri) ?: return null

        return songs.filter { song -> cachedMediaUri(song.uri) == incomingMediaUri }
            .singleOrNull()
    }

    /**
     * Replaces the record with the same ID, or adds it, so later files in
     * the same run see it. Never removes other records.
     */
    fun remember(song: SongEntity) {
        val index = songs.indexOfFirst { it.id == song.id }

        if (index >= 0) {
            songs[index] = song
        } else {
            songs += song
        }
    }

    private fun cachedMediaUri(uri: String): String? {
        // containsKey, because "no MediaStore URI" (null) is cached too.
        if (mediaUris.containsKey(uri)) return mediaUris[uri]
        return mediaUriOf(uri).also { mediaUris[uri] = it }
    }
}

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
    private val folderScanner = FolderScanner(appContext)

    /** Serialises folder imports and syncs so they never interleave. */
    private val folderMutex = Mutex()

    suspend fun scanMediaStore(): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<SongEntity>()
        val failures = mutableListOf<ImportFailure>()

        /*
         * The list includes existing records and records read earlier in this
         * import. This permits conservative matching within the same import
         * without changing or merging existing database records.
         */
        val matcher = newMatcher()

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
                        val matchedSong = matcher.match(uri.toString())
                        val preparedSong = preserveExistingIdentity(
                            readSong = readSong,
                            existingSong = matchedSong
                        )

                        imported += preparedSong
                        matcher.remember(preparedSong)
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
        val matcher = newMatcher()

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
                val matchedSong = matcher.match(uri.toString())
                val preparedSong = preserveExistingIdentity(
                    readSong = readSong,
                    existingSong = matchedSong
                )

                imported += preparedSong
                matcher.remember(preparedSong)
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
     * Keeps read access to a user-selected folder and creates (or reuses) the
     * playlist linked to it. Throws [FolderAccessException] if the folder
     * cannot be listed.
     */
    suspend fun importFolder(
        treeUri: Uri,
        nowMs: Long = System.currentTimeMillis()
    ): FolderImportResult = folderMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                resolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (error: SecurityException) {
                throw FolderAccessException(
                    FolderAccessReason.PERMISSION_MISSING,
                    "Access to this folder could not be kept. Select the folder again.",
                    error
                )
            }

            val listing = folderScanner.list(treeUri)
            val treeUriString = treeUri.toString()

            val knownDocumentIds = repository.getFolderLinkByTreeUri(treeUriString)
                ?.let { link -> repository.getKnownDocumentIds(link.playlistId).toSet() }
                .orEmpty()

            val pending = listing.audioFiles.filterNot { it.documentId in knownDocumentIds }
            val failures = mutableListOf<ImportFailure>()
            val documents = readFolderDocuments(treeUri, pending, failures)

            val sync = repository.createFolderPlaylist(
                name = listing.folderName,
                treeUri = treeUriString,
                linkedAtMs = nowMs,
                initialDocuments = documents
            )

            FolderImportResult(
                sync = sync.copy(
                    skippedAlreadyKnown = sync.skippedAlreadyKnown +
                        (listing.audioFiles.size - pending.size)
                ),
                scannedAudioFiles = listing.audioFiles.size,
                failures = failures
            )
        }
    }

    /**
     * Adds audio files that are new in the playlist's linked folder. Files
     * seen before are skipped without reading their metadata.
     */
    suspend fun syncFolderPlaylist(
        playlistId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): FolderImportResult = folderMutex.withLock {
        withContext(Dispatchers.IO) {
            val link = repository.getFolderLink(playlistId)
                ?: throw IllegalStateException("Playlist is not linked to a folder.")

            val treeUri = Uri.parse(link.treeUri)
            val listing = folderScanner.list(treeUri)

            val knownDocumentIds = repository.getKnownDocumentIds(playlistId).toSet()
            val pending = listing.audioFiles.filterNot { it.documentId in knownDocumentIds }
            val failures = mutableListOf<ImportFailure>()
            val documents = readFolderDocuments(treeUri, pending, failures)

            val sync = repository.applyFolderSync(
                playlistId = playlistId,
                documents = documents,
                syncedAtMs = nowMs
            )

            FolderImportResult(
                sync = sync.copy(
                    skippedAlreadyKnown = sync.skippedAlreadyKnown +
                        (listing.audioFiles.size - pending.size)
                ),
                scannedAudioFiles = listing.audioFiles.size,
                failures = failures
            )
        }
    }

    /**
     * Reads metadata for each folder entry. A file that cannot be read is
     * reported and left out, so it is not recorded and is retried later.
     */
    private suspend fun readFolderDocuments(
        treeUri: Uri,
        entries: List<FolderEntry>,
        failures: MutableList<ImportFailure>
    ): List<ScannedFolderDocument> {
        if (entries.isEmpty()) return emptyList()

        val matcher = newMatcher()
        val documents = mutableListOf<ScannedFolderDocument>()

        for (entry in entries) {
            try {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    entry.documentId
                )

                val readSong = metadataReader.read(
                    childUri,
                    MetadataFallback(
                        displayName = entry.displayName,
                        mimeType = entry.mimeType
                    )
                )
                val matchedSong = matcher.match(childUri.toString())
                val preparedSong = preserveExistingIdentity(
                    readSong = readSong,
                    existingSong = matchedSong
                )

                documents += ScannedFolderDocument(
                    documentId = entry.documentId,
                    song = preparedSong
                )
                matcher.remember(preparedSong)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += ImportFailure(
                    source = entry.displayName ?: entry.documentId,
                    message = error.message ?: "Could not read audio."
                )
            }
        }

        return documents
    }

    /**
     * A matcher for one import or sync run, over the library as it is now.
     * Must be called on Dispatchers.IO (it reads the database).
     */
    private suspend fun newMatcher(): SongMatcher {
        return SongMatcher(repository.getAllSongs()) { uri ->
            equivalentMediaStoreUri(Uri.parse(uri))
        }
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
