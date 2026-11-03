package com.maukingdom.musicplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.maukingdom.musicplayer.data.MetadataReader
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.PlayerSkin
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** What an artwork URI refers to. */
sealed interface ArtworkTarget {
    data class Song(val songId: String) : ArtworkTarget
    data class Playlist(val playlistId: Long) : ArtworkTarget
}

/**
 * Pure URI and cache-name helpers for [ArtworkProvider]. No Android types,
 * so they can be unit-tested on the JVM.
 *
 * URIs: content://<AUTHORITY>/song/<base64url(songId)> and
 * content://<AUTHORITY>/playlist/<playlistId>. Paths are never treated as
 * file paths: a song segment is only ever decoded into a database key.
 */
object ArtworkPaths {

    const val AUTHORITY = "com.maukingdom.musicplayer.artwork"

    private const val SONG = "song"
    private const val PLAYLIST = "playlist"

    /** Generous upper bound for an encoded song ID segment. */
    private const val MAX_ENCODED_LENGTH = 4096

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun songUri(songId: String): String =
        "content://$AUTHORITY/$SONG/${encodeSongId(songId)}"

    fun playlistUri(playlistId: Long): String =
        "content://$AUTHORITY/$PLAYLIST/$playlistId"

    fun encodeSongId(songId: String): String =
        encoder.encodeToString(songId.toByteArray(Charsets.UTF_8))

    /**
     * Returns null unless [segment] is canonical unpadded base64url of a
     * non-blank UTF-8 string.
     */
    fun decodeSongId(segment: String): String? {
        if (segment.isEmpty() || segment.length > MAX_ENCODED_LENGTH) return null
        if (!segment.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
            return null
        }

        val decoded = try {
            String(decoder.decode(segment), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (decoded.isBlank()) return null

        // Reject alternative spellings of the same ID.
        return decoded.takeIf { encodeSongId(it) == segment }
    }

    /**
     * Parses decoded URI path segments. Exactly two segments are accepted:
     * "song"/<base64url> or "playlist"/<digits>. Anything else is null.
     */
    fun parse(pathSegments: List<String>): ArtworkTarget? {
        if (pathSegments.size != 2) return null

        val (type, value) = pathSegments

        return when (type) {
            SONG -> decodeSongId(value)?.let { ArtworkTarget.Song(it) }
            PLAYLIST -> {
                if (value.isEmpty() || !value.all { it in '0'..'9' }) {
                    null
                } else {
                    value.toLongOrNull()?.let { ArtworkTarget.Playlist(it) }
                }
            }
            else -> null
        }
    }

    /** Lower-case hex SHA-256 of [parts] joined with a separator. */
    fun sha256Hex(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8))

        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}

/**
 * Read-only artwork for media browsers such as Android Auto, which run in
 * another process and cannot read this app's song URIs directly.
 *
 * Song artwork comes from the file's embedded picture, else the MediaStore
 * album art, else a generated tile in the current skin's colours. Images are
 * at most 512 px, JPEG quality 85, and cached under cacheDir/artwork.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        private const val TAG = "ArtworkProvider"
        private const val MAX_SIZE = 512
        private const val JPEG_QUALITY = 85
        private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    }

    private val appContext: Context
        get() = checkNotNull(context).applicationContext

    private val repository by lazy {
        MusicRepository(MusicDatabase.getInstance(appContext))
    }

    private val preferences by lazy { PlaybackPreferences(appContext) }
    private val metadataReader by lazy { MetadataReader(appContext) }

    private val cacheDir by lazy {
        File(appContext.cacheDir, "artwork").apply { mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        return if (targetOf(uri) != null) "image/jpeg" else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw SecurityException("Artwork is read-only.")
        }

        val target = targetOf(uri)
            ?: throw FileNotFoundException("Unknown artwork URI.")

        val file = runBlocking(Dispatchers.IO) {
            when (target) {
                is ArtworkTarget.Song -> songArtwork(target.songId)
                is ArtworkTarget.Playlist -> playlistArtwork(target.playlistId)
            }
        } ?: throw FileNotFoundException("No such song or playlist.")

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Artwork is read-only.")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Artwork is read-only.")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Artwork is read-only.")
    }

    private fun targetOf(uri: Uri): ArtworkTarget? {
        if (uri.scheme != "content" || uri.authority != ArtworkPaths.AUTHORITY) {
            return null
        }

        return ArtworkPaths.parse(uri.pathSegments)
    }

    /** Runs on Dispatchers.IO. Null if the song is not in the library. */
    private suspend fun songArtwork(songId: String): File? {
        val song = repository.getSong(songId) ?: return null

        val key = ArtworkPaths.sha256Hex(songId)
        val cached = File(cacheDir, "$key.jpg")
        if (cached.isUsable()) return cached

        // Remembers that the file has no picture, so it is not re-read each time.
        val noArtwork = File(cacheDir, "$key.none")

        if (!noArtwork.exists()) {
            val bitmap = sourceArtwork(song)?.let(::decodeScaled)

            if (bitmap != null && writeJpeg(bitmap, cached)) {
                return cached
            }

            noArtwork.createNewFile()
        }

        val skin = currentSkin()
        val tile = File(
            cacheDir,
            ArtworkPaths.sha256Hex("tile", skin.id, songId, song.title) + ".jpg"
        )

        if (!tile.isUsable()) {
            writeJpeg(ArtworkTile.render(song.title, song.artist, skin), tile)
        }

        return tile.takeIf { it.isUsable() }
    }

    /** Runs on Dispatchers.IO. Null if the playlist does not exist. */
    private suspend fun playlistArtwork(playlistId: Long): File? {
        val playlist = repository.getPlaylist(playlistId) ?: return null
        val skin = currentSkin()

        val tile = File(
            cacheDir,
            ArtworkPaths.sha256Hex(
                "playlist",
                skin.id,
                playlistId.toString(),
                playlist.name
            ) + ".jpg"
        )

        if (!tile.isUsable()) {
            writeJpeg(ArtworkTile.render(playlist.name, "PLAYLIST", skin), tile)
        }

        return tile.takeIf { it.isUsable() }
    }

    /** Embedded picture first, then the MediaStore album art URI. */
    private fun sourceArtwork(song: SongEntity): ByteArray? {
        metadataReader.readEmbeddedArtwork(Uri.parse(song.uri))
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val artworkUri = song.artworkUri?.takeIf { it.isNotBlank() } ?: return null

        return try {
            appContext.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { input ->
                val bytes = input.readNBytesCompat(MAX_SOURCE_BYTES + 1)
                bytes.takeIf { it.isNotEmpty() && it.size <= MAX_SOURCE_BYTES }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Album art could not be read.", error)
            null
        }
    }

    private suspend fun currentSkin(): PlayerSkin {
        return try {
            PlayerSkin.fromId(preferences.appPreferences.first().playerSkinId)
        } catch (error: Exception) {
            PlayerSkin.NEON_WALKMAN
        }
    }

    /** Decodes with subsampling, then scales so the longer side is ≤ 512 px. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE &&
            bounds.outHeight / (sample * 2) >= MAX_SIZE
        ) {
            sample *= 2
        }

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_SIZE) return decoded

        val scale = MAX_SIZE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )

        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Writes to a temporary file and renames it, so readers never see half a file. */
    private fun writeJpeg(bitmap: Bitmap, target: File): Boolean {
        val temp = File.createTempFile("art", ".tmp", cacheDir)

        return try {
            FileOutputStream(temp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "JPEG encoding failed."
                }
            }

            temp.renameTo(target) || target.isUsable()
        } catch (error: Exception) {
            Log.w(TAG, "Artwork could not be cached.", error)
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun File.isUsable(): Boolean = isFile && length() > 0L

    /** Reads at most [limit] bytes (InputStream.readNBytes needs API 33). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)

        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }

        return output.toByteArray()
    }
}
