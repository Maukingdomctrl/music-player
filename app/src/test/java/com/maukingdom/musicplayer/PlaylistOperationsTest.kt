package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistSongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistOperationsTest {

    @Test
    fun playlistEntriesCanBeOrderedByPosition() {
        val entries = listOf(
            PlaylistSongEntity(
                playlistId = 1L,
                songId = "song-c",
                position = 2L
            ),
            PlaylistSongEntity(
                playlistId = 1L,
                songId = "song-a",
                position = 0L
            ),
            PlaylistSongEntity(
                playlistId = 1L,
                songId = "song-b",
                position = 1L
            )
        )

        val orderedIds = entries.sortedBy { it.position }.map { it.songId }

        assertEquals(
            listOf("song-a", "song-b", "song-c"),
            orderedIds
        )
    }

    @Test
    fun sameSongCanHaveEntriesInDifferentPlaylists() {
        val first = PlaylistSongEntity(
            playlistId = 1L,
            songId = "song-a",
            position = 0L
        )

        val second = PlaylistSongEntity(
            playlistId = 2L,
            songId = "song-a",
            position = 0L
        )

        assertNotEquals(first.playlistId, second.playlistId)
        assertEquals(first.songId, second.songId)
    }
}
