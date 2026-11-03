package com.maukingdom.musicplayer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.PlaybackSnapshot
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {

    private lateinit var context: Context
    private lateinit var database: MusicDatabase
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        database = Room.inMemoryDatabaseBuilder(
            context,
            MusicDatabase::class.java
        ).build()

        repository = MusicRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun metadataCorrectionsSurviveReimport() = runBlocking {
        val original = song(
            id = "content://test/song/1",
            title = "Original title"
        )

        repository.saveImportedSongs(listOf(original))

        assertTrue(
            repository.updateMetadata(
                songId = original.id,
                metadata = SongMetadata(
                    title = "Corrected title",
                    artist = "Corrected singer",
                    album = "Corrected movie",
                    genre = "Film",
                    year = 2001
                )
            )
        )

        repository.saveImportedSongs(
            listOf(
                original.copy(
                    title = "New source title",
                    durationMs = 200_000L
                )
            )
        )

        val saved = repository.getSong(original.id)
        assertNotNull(saved)
        assertEquals("Corrected title", saved?.title)
        assertEquals("Corrected singer", saved?.artist)
        assertEquals("Corrected movie", saved?.album)
        assertEquals("Film", saved?.genre)
        assertEquals(2001, saved?.year)
        assertEquals(200_000L, saved?.durationMs)
        assertTrue(saved?.metadataEdited == true)
    }

    @Test
    fun playlistOperationsKeepOrderAndRejectDuplicates() = runBlocking {
        val first = song("content://test/song/1", "First")
        val second = song("content://test/song/2", "Second")

        repository.saveImportedSongs(listOf(first, second))

        val playlistId = repository.createPlaylist("Road songs")

        assertTrue(repository.addSongToPlaylist(playlistId, first.id))
        assertTrue(repository.addSongToPlaylist(playlistId, second.id))
        assertFalse(repository.addSongToPlaylist(playlistId, first.id))

        assertTrue(
            repository.reorderPlaylist(
                playlistId,
                listOf(second.id, first.id)
            )
        )

        assertEquals(
            listOf(second.id, first.id),
            repository.getPlaylistSongs(playlistId).map { it.id }
        )

        assertFalse(
            repository.reorderPlaylist(
                playlistId,
                listOf(first.id, first.id)
            )
        )

        assertTrue(repository.removeSongFromPlaylist(playlistId, first.id))

        assertEquals(
            listOf(second.id),
            repository.getPlaylistSongs(playlistId).map { it.id }
        )
    }

    @Test
    fun deletingLibrarySongRemovesPlaylistEntry() = runBlocking {
        val track = song("content://test/song/1", "Track")
        repository.saveImportedSongs(listOf(track))

        val playlistId = repository.createPlaylist("Playlist")
        assertTrue(repository.addSongToPlaylist(playlistId, track.id))

        assertTrue(repository.removeSong(track.id))
        assertTrue(repository.getPlaylistSongs(playlistId).isEmpty())
    }

    @Test
    fun playbackSnapshotRoundTripsThroughDataStore() = runBlocking {
        val preferences = PlaybackPreferences(context)
        val original = preferences.getPlaybackSnapshot()

        try {
            val snapshot = PlaybackSnapshot(
                queueSongIds = listOf(
                    "content://test/song/1",
                    "content://test/song/2"
                ),
                selectedIndex = 1,
                positionMs = 42_000L,
                shuffleEnabled = true,
                repeatMode = PlaybackSnapshot.REPEAT_ALL,
                shuffleOrder = listOf(
                    "content://test/song/2",
                    "content://test/song/1"
                )
            )

            preferences.savePlaybackSnapshot(snapshot)

            assertEquals(snapshot, preferences.getPlaybackSnapshot())
        } finally {
            preferences.savePlaybackSnapshot(original)
        }
    }

    @Test
    fun playbackSnapshotClampsInvalidValues() = runBlocking {
        val preferences = PlaybackPreferences(context)
        val original = preferences.getPlaybackSnapshot()

        try {
            preferences.savePlaybackSnapshot(
                PlaybackSnapshot(
                    queueSongIds = listOf("content://test/song/1"),
                    selectedIndex = 9,
                    positionMs = -50L,
                    repeatMode = 99
                )
            )

            val saved = preferences.getPlaybackSnapshot()

            assertEquals(0, saved.selectedIndex)
            assertEquals(0L, saved.positionMs)
            assertEquals(PlaybackSnapshot.REPEAT_ALL, saved.repeatMode)
        } finally {
            preferences.savePlaybackSnapshot(original)
        }
    }

    private fun song(id: String, title: String): SongEntity {
        return SongEntity(
            id = id,
            uri = id,
            title = title,
            artist = "Singer",
            album = "Movie",
            genre = null,
            year = null,
            durationMs = 180_000L,
            artworkUri = null,
            mimeType = "audio/mpeg"
        )
    }
}
