package com.maukingdom.musicplayer.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

enum class FolderAccessReason {
    PERMISSION_MISSING,
    FOLDER_UNAVAILABLE,
    NOT_A_FOLDER,
    PROVIDER_LOADING,
    PROVIDER_ERROR
}

/**
 * A linked folder could not be listed. Callers should show [message] and must
 * not treat the folder as empty.
 */
class FolderAccessException(
    val reason: FolderAccessReason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** One top-level file in a linked folder. [documentId] is the exact SAF ID. */
data class FolderEntry(
    val documentId: String,
    val displayName: String?,
    val mimeType: String?
)

data class FolderListing(
    val folderName: String,
    val audioFiles: List<FolderEntry>
)

/**
 * Lists audio files directly inside a user-selected SAF document tree.
 *
 * Only the top level is read; subfolders are ignored. Nothing is opened,
 * changed or deleted, and persisted permissions are never released here.
 */
class FolderScanner(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    fun hasPersistedReadPermission(treeUri: Uri): Boolean {
        return resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }
    }

    /**
     * Blocking; call on Dispatchers.IO. Throws [FolderAccessException] for
     * every failure. An empty but readable folder returns no audio files.
     */
    fun list(treeUri: Uri): FolderListing {
        if (!hasPersistedReadPermission(treeUri)) {
            throw FolderAccessException(
                FolderAccessReason.PERMISSION_MISSING,
                PERMISSION_MISSING_MESSAGE
            )
        }

        return try {
            listGranted(treeUri)
        } catch (error: FolderAccessException) {
            throw error
        } catch (error: SecurityException) {
            throw FolderAccessException(
                FolderAccessReason.PERMISSION_MISSING,
                PERMISSION_MISSING_MESSAGE,
                error
            )
        } catch (error: Exception) {
            // IllegalArgumentException, IllegalStateException,
            // UnsupportedOperationException and other provider failures.
            throw folderUnavailable(error)
        }
    }

    private fun listGranted(treeUri: Uri): FolderListing {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            rootDocumentId
        )

        val root = resolver.query(
            rootUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                FolderEntry(
                    documentId = rootDocumentId,
                    displayName = cursor.stringOrNull(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    mimeType = cursor.stringOrNull(
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                )
            }
        } ?: throw folderUnavailable()

        if (root.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw FolderAccessException(
                FolderAccessReason.NOT_A_FOLDER,
                "The selected item is not a folder."
            )
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            rootDocumentId
        )

        val children = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        ) ?: throw folderUnavailable()

        val audioFiles = children.use { cursor ->
            val extras = cursor.extras

            if (extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true) {
                throw FolderAccessException(
                    FolderAccessReason.PROVIDER_LOADING,
                    "The folder is still loading. Try again in a moment."
                )
            }

            if (extras?.containsKey(DocumentsContract.EXTRA_ERROR) == true) {
                throw FolderAccessException(
                    FolderAccessReason.PROVIDER_ERROR,
                    extras.getString(DocumentsContract.EXTRA_ERROR)
                        ?.takeIf { it.isNotBlank() }
                        ?: "The folder could not be read."
                )
            }

            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    val displayName = cursor.stringAt(nameColumn)
                    val mimeType = cursor.stringAt(mimeColumn)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        continue
                    }

                    if (isAudioCandidate(mimeType, displayName)) {
                        add(FolderEntry(documentId, displayName, mimeType))
                    }
                }
            }
        }

        return FolderListing(
            folderName = root.displayName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: folderNameFallback(rootDocumentId),
            audioFiles = audioFiles.sortedWith(folderSortKey)
        )
    }

    private fun folderUnavailable(cause: Throwable? = null): FolderAccessException {
        return FolderAccessException(
            FolderAccessReason.FOLDER_UNAVAILABLE,
            "The folder is not available. Check that the SD card is inserted.",
            cause
        )
    }

    private fun Cursor.stringOrNull(columnName: String): String? {
        return stringAt(getColumnIndex(columnName))
    }

    private fun Cursor.stringAt(column: Int): String? {
        return if (column < 0 || isNull(column)) null else getString(column)
    }

    private companion object {
        const val PERMISSION_MISSING_MESSAGE =
            "Access to this folder was lost. Select the folder again."
    }
}

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka"
)

/**
 * Playlists, cue sheets, lyrics, notes and pictures that often sit next to
 * songs. Some providers label playlists as audio (audio/x-mpegurl,
 * audio/x-scpls), so these are excluded whatever the MIME type says.
 */
private val NON_AUDIO_EXTENSIONS = setOf(
    "m3u", "m3u8", "pls", "cue", "txt", "lrc", "nfo",
    "jpg", "jpeg", "png"
)

/**
 * True for audio MIME types. When the provider gives no useful MIME type,
 * the file extension decides. Directories, and the playlist, text and
 * image files in [NON_AUDIO_EXTENSIONS], are never audio.
 */
internal fun isAudioCandidate(mimeType: String?, displayName: String?): Boolean {
    val mime = mimeType?.trim()?.lowercase()

    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return false

    val extension = displayName
        ?.trim()
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()

    if (extension in NON_AUDIO_EXTENSIONS) return false
    if (mime != null && mime.startsWith("audio/")) return true

    val usesExtension = mime.isNullOrEmpty() ||
        mime == "application/octet-stream" ||
        mime == "application/ogg"

    if (!usesExtension) return false

    return extension in AUDIO_EXTENSIONS
}

/**
 * Folder name from a tree document ID such as
 * "1234-ABCD:Music/MyCarMusic/Hindi 90s" (gives "Hindi 90s").
 */
internal fun folderNameFallback(documentId: String): String {
    return documentId
        .substringAfter(':', missingDelimiterValue = documentId)
        .trimEnd('/')
        .substringAfterLast('/')
        .trim()
        .ifEmpty { "Folder" }
}

/** Case-insensitive by display name, then by document ID. */
internal val folderSortKey: Comparator<FolderEntry> =
    compareBy<FolderEntry, String>(String.CASE_INSENSITIVE_ORDER) {
        it.displayName.orEmpty()
    }.thenBy { it.documentId }
