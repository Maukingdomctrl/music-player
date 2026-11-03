package com.maukingdom.musicplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Optional metadata supplied by MediaStore or a document provider.
 */
data class MetadataFallback(
    val displayName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUri: String? = null,
    val mimeType: String? = null
)

/**
 * Reads available tags without changing the source file.
 *
 * Embedded artwork can be requested separately with [readEmbeddedArtwork].
 * This avoids storing potentially large image blobs in Room.
 */
class MetadataReader(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Reads one local content URI. If Android cannot open or parse the file,
     * the exception is passed to the caller for reporting.
     */
    fun read(
        uri: Uri,
        fallback: MetadataFallback = MetadataFallback()
    ): SongEntity {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(appContext, uri)

            val fileTitle = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            )
            val fileArtist = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ARTIST
            )
            val fileAlbum = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUM
            )
            val fileGenre = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_GENRE
            )
            val fileYear = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_YEAR
            )
            val fileDuration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()

            val fileMimeType = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            )

            val fallbackTitle = cleanText(fallback.displayName)
                ?.let { displayName ->
                    displayName
                        .substringBeforeLast(
                            delimiter = '.',
                            missingDelimiterValue = displayName
                        )
                        .trim()
                        .takeIf { it.isNotEmpty() }
                }

            val title = cleanText(fileTitle)
                ?: cleanText(fallback.title)
                ?: fallbackTitle
                ?: "Unknown title"

            val artist = cleanText(fileArtist)
                ?: cleanText(fallback.artist)
                ?: "Unknown artist"

            val album = cleanText(fileAlbum)
                ?: cleanText(fallback.album)
                ?: "Unknown album"

            val duration = (fileDuration ?: fallback.durationMs ?: 0L)
                .coerceAtLeast(0L)

            val year = fileYear
                ?.trim()
                ?.take(4)
                ?.toIntOrNull()
                ?.takeIf { it in 1..9999 }

            val id = uri.toString()

            return SongEntity(
                id = id,
                uri = id,
                title = title,
                artist = artist,
                album = album,
                genre = cleanText(fileGenre),
                year = year,
                durationMs = duration,
                artworkUri = fallback.artworkUri,
                mimeType = cleanText(fileMimeType)
                    ?: cleanText(fallback.mimeType)
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Returns embedded artwork bytes when the file provides them.
     * Returns null if artwork is absent or the file cannot be read.
     */
    fun readEmbeddedArtwork(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(appContext, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun cleanText(value: String?): String? {
        val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (cleaned.equals("<unknown>", ignoreCase = true)) {
            return null
        }

        return cleaned
    }
}
