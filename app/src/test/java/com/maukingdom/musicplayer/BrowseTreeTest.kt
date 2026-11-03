package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.playback.BrowseId
import com.maukingdom.musicplayer.playback.BrowseTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTreeTest {

    private val songIds = listOf(
        "content://media/external/audio/media/101",
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic" +
            "/document/1234-ABCD%3AMusic%2FHindi%2090s%2F01%20Song.mp3",
        "content://example/odd|pipe:colon/slash%7Cpercent",
        "plain-id-without-scheme"
    )

    @Test
    fun fixedNodesRoundTrip() {
        val nodes = listOf(
            BrowseId.Root to "node:root",
            BrowseId.Playlists to "node:playlists",
            BrowseId.Recent to "node:recent",
            BrowseId.Library to "node:library"
        )

        for ((id, encoded) in nodes) {
            assertEquals(encoded, BrowseTree.encode(id))
            assertEquals(id, BrowseTree.decode(encoded))
        }
    }

    @Test
    fun playlistNodesRoundTrip() {
        for (playlistId in listOf(0L, 1L, 42L, Long.MAX_VALUE)) {
            val id = BrowseId.Playlist(playlistId)
            val encoded = BrowseTree.encode(id)

            assertEquals("node:playlist:$playlistId", encoded)
            assertEquals(id, BrowseTree.decode(encoded))
        }
    }

    @Test
    fun contextIdsRoundTripWithRealContentUris() {
        for (songId in songIds) {
            val ids = listOf(
                BrowseId.PlaylistSong(7L, songId),
                BrowseId.RecentSong(songId),
                BrowseId.LibrarySong(songId)
            )

            for (id in ids) {
                val encoded = BrowseTree.encode(id)
                assertEquals("Round trip of $encoded", id, BrowseTree.decode(encoded))
            }
        }

        assertEquals(
            "ctx:playlist:7|content://media/external/audio/media/101",
            BrowseTree.encode(BrowseId.PlaylistSong(7L, songIds[0]))
        )
    }

    @Test
    fun songIdIsEverythingAfterTheFirstPipe() {
        val decoded = BrowseTree.decode("ctx:recent|a|b|c")
        assertEquals(BrowseId.RecentSong("a|b|c"), decoded)
    }

    @Test
    fun plainSongIdsRoundTrip() {
        for (songId in songIds) {
            val id = BrowseId.Song(songId)
            assertEquals(songId, BrowseTree.encode(id))
            assertEquals(id, BrowseTree.decode(songId))
        }
    }

    @Test
    fun unknownIdsAreRejected() {
        val unknown = listOf(
            "",
            "   ",
            "node:",
            "node:unknown",
            "node:root:extra",
            "node:playlist:",
            "node:playlist:abc",
            "node:playlist:-1",
            "node:playlist:+5",
            "node:playlist:99999999999999999999",
            "ctx:",
            "ctx:recent",
            "ctx:recent|",
            "ctx:unknown|content://media/external/audio/media/1",
            "ctx:playlist:|content://media/external/audio/media/1",
            "ctx:playlist:x|content://media/external/audio/media/1"
        )

        for (raw in unknown) {
            assertNull("Expected '$raw' to be rejected", BrowseTree.decode(raw))
        }
    }

    @Test
    fun nodeIdsNeverDecodeAsSongs() {
        val nodeLike = listOf(
            "node:root",
            "node:playlists",
            "node:playlist:3",
            "node:anything-else",
            "node:"
        )

        for (raw in nodeLike) {
            val decoded = BrowseTree.decode(raw)
            assertFalse("$raw decoded as $decoded", decoded is BrowseId.Playable)
        }
    }

    @Test
    fun rootChildrenAreTheThreeSections() {
        assertEquals(
            listOf(BrowseId.Playlists, BrowseId.Recent, BrowseId.Library),
            BrowseTree.rootChildren
        )
        assertEquals("Playlists", BrowseTree.title(BrowseId.Playlists))
        assertEquals("Recently added", BrowseTree.title(BrowseId.Recent))
        assertEquals("All songs", BrowseTree.title(BrowseId.Library))
    }

    @Test
    fun folderLinkedPlaylistsComeFirstThenAToZ() {
        val playlists = listOf(
            PlaylistEntity(id = 1L, name = "zebra", createdAtMs = 0L),
            PlaylistEntity(id = 2L, name = "Hindi 90s", createdAtMs = 0L),
            PlaylistEntity(id = 3L, name = "apple", createdAtMs = 0L),
            PlaylistEntity(id = 4L, name = "Car Mix", createdAtMs = 0L)
        )

        val ordered = BrowseTree.orderPlaylists(playlists, folderLinkedIds = setOf(1L, 2L))

        assertEquals(listOf(2L, 1L, 3L, 4L), ordered.map { it.id })
    }

    @Test
    fun pagingSlicesAndHandlesOutOfRange() {
        val items = (1..10).toList()

        assertEquals(listOf(1, 2, 3, 4), BrowseTree.page(items, 0, 4))
        assertEquals(listOf(9, 10), BrowseTree.page(items, 2, 4))
        assertTrue(BrowseTree.page(items, 3, 4).isEmpty())
        assertEquals(items, BrowseTree.page(items, 0, Int.MAX_VALUE))
        assertTrue(BrowseTree.page(items, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(BrowseTree.page(items, -1, 4).isEmpty())
        assertTrue(BrowseTree.page(items, 0, 0).isEmpty())
    }
}
