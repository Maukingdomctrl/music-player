package com.maukingdom.musicplayer

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.playback.ArtworkPaths
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real ArtworkProvider through ContentResolver. The provider
 * reads the app's real database, so test rows use unique IDs and are
 * removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkProviderTest {

    private lateinit var context: Context
    private lateinit var database: MusicDatabase
    private lateinit var songId: String
    private var playlistId: Long = -1L

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = MusicDatabase.getInstance(context)

        // No real file behind it, so the provider must fall back to a tile.
        songId = "content://test.artwork/song/${UUID.randomUUID()}"

        database.libraryDao().insertSong(
            SongEntity(
                id = songId,
                uri = songId,
                title = "Artwork Test Song With A Fairly Long Title",
                artist = "Test Singer",
                album = "Test Album",
                genre = null,
                year = null,
                durationMs = 1_000L,
                artworkUri = null,
                mimeType = "audio/wav"
            )
        )

        playlistId = database.libraryDao().insertPlaylist(
            PlaylistEntity(name = "Artwork Test Playlist ${UUID.randomUUID()}")
        )
    }

    @After
    fun tearDown() = runBlocking {
        database.libraryDao().deleteSong(songId)
        database.libraryDao().deletePlaylist(playlistId)
        Unit
    }

    @Test
    fun songArtworkIsANonEmptyJpeg() {
        val bytes = readAll(Uri.parse(ArtworkPaths.songUri(songId)))

        assertTrue("Expected artwork bytes", bytes.size > 100)
        assertJpeg(bytes)
        assertEquals("image/jpeg", context.contentResolver.getType(Uri.parse(ArtworkPaths.songUri(songId))))
    }

    @Test
    fun playlistArtworkIsANonEmptyJpeg() {
        val bytes = readAll(Uri.parse(ArtworkPaths.playlistUri(playlistId)))

        assertTrue("Expected artwork bytes", bytes.size > 100)
        assertJpeg(bytes)
    }

    @Test
    fun deletedCacheFolderIsRecreatedAndArtworkStillServed() {
        val artworkDir = File(context.cacheDir, "artwork")
        val uri = Uri.parse(ArtworkPaths.songUri(songId))

        // Warm the cache, then wipe it the way Android's cache clearing does.
        readAll(uri)
        assertTrue(artworkDir.deleteRecursively())
        assertFalse(artworkDir.exists())

        val bytes = readAll(uri)

        assertTrue("Expected artwork bytes", bytes.size > 100)
        assertJpeg(bytes)
        assertTrue("Cache folder should be recreated", artworkDir.isDirectory)
    }

    @Test
    fun artworkIsServedFromMemoryWhenTheCacheFolderCannotBeCreated() {
        val artworkDir = File(context.cacheDir, "artwork")
        artworkDir.deleteRecursively()

        // A plain file where the folder should be makes mkdirs() fail.
        assertTrue(artworkDir.createNewFile())

        try {
            val song = readAll(Uri.parse(ArtworkPaths.songUri(songId)))
            val playlist = readAll(Uri.parse(ArtworkPaths.playlistUri(playlistId)))

            assertTrue("Expected song artwork bytes", song.size > 100)
            assertJpeg(song)
            // Ends with the JPEG end marker, so the whole image came through the pipe.
            assertEquals(0xD9.toByte(), song.last())
            assertTrue("Expected playlist artwork bytes", playlist.size > 100)
            assertJpeg(playlist)
            assertEquals(0xD9.toByte(), playlist.last())
        } finally {
            artworkDir.delete()
        }
    }

    @Test
    fun unknownSongOrPlaylistIsFileNotFound() {
        val unknownSong = Uri.parse(ArtworkPaths.songUri("content://test.artwork/missing/${UUID.randomUUID()}"))
        val unknownPlaylist = Uri.parse(ArtworkPaths.playlistUri(Long.MAX_VALUE))

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(unknownSong, "r")?.close()
        }
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(unknownPlaylist, "r")?.close()
        }
    }

    @Test
    fun malformedPathsAreFileNotFound() {
        val base = "content://${ArtworkPaths.AUTHORITY}"
        val malformed = listOf(
            "$base/song/..%2F..%2Fdatabases%2Fmusic_player.db",
            "$base/song/not+base64",
            "$base/../databases/music_player.db",
            "$base/playlist/abc",
            "$base/cover/1"
        )

        for (raw in malformed) {
            assertThrows("Expected $raw to be rejected", FileNotFoundException::class.java) {
                context.contentResolver.openFileDescriptor(Uri.parse(raw), "r")?.close()
            }
        }
    }

    @Test
    fun writeModesAreRefused() {
        val uri = Uri.parse(ArtworkPaths.songUri(songId))

        for (mode in listOf("w", "rw", "wt", "wa", "rwt")) {
            assertThrows("Expected mode $mode to be refused", SecurityException::class.java) {
                context.contentResolver.openFileDescriptor(uri, mode)?.close()
            }
        }
    }

    @Test
    fun insertUpdateDeleteAreRefused() {
        val uri = Uri.parse(ArtworkPaths.songUri(songId))

        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.insert(uri, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.update(uri, null, null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun readAll(uri: Uri): ByteArray {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Provider returned no descriptor for $uri")

        return descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun assertJpeg(bytes: ByteArray) {
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
    }
}
