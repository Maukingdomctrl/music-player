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
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
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

    /**
     * The artwork cache folder, re-created on every call because Android may
     * clear the app's cache at any time. Null if it cannot be created; the
     * provider then serves images from memory instead.
     */
    private fun artworkDir(): File? {
        return try {
            File(appContext.cacheDir, "artwork").takeIf { it.mkdirs() || it.isDirectory }
        } catch (error: SecurityException) {
            null
        }
    }

    /** What openFile hands back: a cached file, or JPEG bytes sent through a pipe. */
    private sealed interface Served {
        class FromFile(val file: File) : Served
        class InMemory(val jpeg: ByteArray) : Served
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

        val served = runBlocking(Dispatchers.IO) {
            when (target) {
                is ArtworkTarget.Song -> songArtwork(target.songId)
                is ArtworkTarget.Playlist -> playlistArtwork(target.playlistId)
            }
        } ?: throw FileNotFoundException("No such song or playlist.")

        return when (served) {
            is Served.FromFile -> try {
                ParcelFileDescriptor.open(served.file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (error: FileNotFoundException) {
                // Deleted between the check and the open (cache cleared).
                Log.w(TAG, "Cached artwork vanished; serving from memory.", error)
                pipeOf(served.file.readBytesOrNull() ?: throw error)
            }

            is Served.InMemory -> pipeOf(served.jpeg)
        }
    }

    /**
     * Returns the read end of a pipe and writes [bytes] from a background
     * thread, since a pipe buffer is smaller than most JPEGs.
     */
    private fun pipeOf(bytes: ByteArray): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()

        thread(name = "ArtworkPipe", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(bytes) }
            } catch (error: IOException) {
                // The reader closed early; nothing to do.
                Log.w(TAG, "Artwork pipe closed early.", error)
            }
        }

        return readEnd
    }

    private fun File.readBytesOrNull(): ByteArray? =
        try {
            readBytes().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
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
    private suspend fun songArtwork(songId: String): Served? {
        val song = repository.getSong(songId) ?: return null
        val dir = artworkDir()

        val key = ArtworkPaths.sha256Hex(songId)
        val cached = dir?.let { File(it, "$key.jpg") }
        if (cached != null && cached.isUsable()) return Served.FromFile(cached)

        // Remembers that the file has no picture, so it is not re-read each time.
        val noArtwork = dir?.let { File(it, "$key.none") }

        if (noArtwork == null || !noArtwork.exists()) {
            val bitmap = sourceArtwork(song)?.let(::decodeScaled)

            if (bitmap != null) {
                if (cached != null && writeJpeg(bitmap, cached)) {
                    return Served.FromFile(cached)
                }

                jpegBytes(bitmap)?.let { return Served.InMemory(it) }
            } else {
                noArtwork?.createQuietly()
            }
        }

        val skin = currentSkin()
        val tile = dir?.let {
            File(it, ArtworkPaths.sha256Hex("tile", skin.id, songId, song.title) + ".jpg")
        }

        return cachedOrRendered(tile) { ArtworkTile.render(song.title, song.artist, skin) }
    }

    /** Runs on Dispatchers.IO. Null if the playlist does not exist. */
    private suspend fun playlistArtwork(playlistId: Long): Served? {
        val playlist = repository.getPlaylist(playlistId) ?: return null
        val skin = currentSkin()

        val tile = artworkDir()?.let {
            File(
                it,
                ArtworkPaths.sha256Hex(
                    "playlist",
                    skin.id,
                    playlistId.toString(),
                    playlist.name
                ) + ".jpg"
            )
        }

        return cachedOrRendered(tile) { ArtworkTile.render(playlist.name, "PLAYLIST", skin) }
    }

    /**
     * Serves [file] if it is cached, else renders, caches and serves it. If
     * there is no cache folder or the write fails, the JPEG is served from
     * memory. Null only if encoding fails.
     */
    private fun cachedOrRendered(file: File?, render: () -> Bitmap): Served? {
        if (file != null && file.isUsable()) return Served.FromFile(file)

        val bitmap = render()
        if (file != null && writeJpeg(bitmap, file)) return Served.FromFile(file)

        return jpegBytes(bitmap)?.let { Served.InMemory(it) }
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray? {
        return try {
            val output = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "JPEG encoding failed."
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            Log.w(TAG, "Artwork could not be encoded.", error)
            null
        }
    }

    private fun File.createQuietly() {
        try {
            createNewFile()
        } catch (error: IOException) {
            Log.w(TAG, "No-artwork marker could not be written.", error)
        }
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
        val temp = try {
            File.createTempFile("art", ".tmp", target.parentFile)
        } catch (error: IOException) {
            Log.w(TAG, "Artwork cache is not writable.", error)
            return false
        }

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
