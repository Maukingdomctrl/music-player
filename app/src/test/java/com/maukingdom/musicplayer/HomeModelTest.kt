package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.v2.home.QUICK_TILE_COUNT
import com.maukingdom.musicplayer.ui.v2.home.RECENTLY_ADDED_COUNT
import com.maukingdom.musicplayer.ui.v2.home.buildHomeContent
import com.maukingdom.musicplayer.ui.v2.home.greetingFor
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {

    private fun playlist(id: Long, name: String) =
        PlaylistEntity(id = id, name = name, createdAtMs = 0L)

    private fun song(id: String, addedMs: Long) = SongEntity(
        id = id,
        uri = "content://test/$id",
        title = id,
        artist = "Artist",
        album = "Album",
        genre = null,
        year = null,
        durationMs = 1_000L,
        artworkUri = null,
        mimeType = "audio/mpeg",
        dateAddedMs = addedMs
    )

    @Test
    fun quickTilesAreNonEmptyPlaylistsWithMostSongsFirst() {
        val playlists = listOf(
            playlist(1, "Marathi"),
            playlist(2, "Hindi 90s"),
            playlist(3, "Empty"),
            playlist(4, "ganpati Songs"),
            playlist(5, "Old Classics")
        )
        val counts = mapOf(1L to 29, 2L to 42, 4L to 29, 5L to 57)

        val content = buildHomeContent(playlists, counts, emptySet(), emptyList())

        // Ties (29) fall back to case-insensitive name order.
        assertEquals(
            listOf("Old Classics", "Hindi 90s", "ganpati Songs", "Marathi"),
            content.quickTiles.map { it.name }
        )
    }

    @Test
    fun quickTilesAreCappedAtSix() {
        val playlists = (1L..10L).map { playlist(it, "P$it") }
        val counts = playlists.associate { it.id to it.id.toInt() }

        val content = buildHomeContent(playlists, counts, emptySet(), emptyList())

        assertEquals(QUICK_TILE_COUNT, content.quickTiles.size)
        assertEquals(10L, content.quickTiles.first().playlistId)
    }

    @Test
    fun foldersAreOnlyLinkedPlaylistsByNameIncludingEmptyOnes() {
        val playlists = listOf(
            playlist(1, "Marathi"),
            playlist(2, "Hindi 90s"),
            playlist(3, "My mix"),
            playlist(4, "Hindi New")
        )
        val counts = mapOf(1L to 29, 2L to 42, 3L to 5)

        val content = buildHomeContent(playlists, counts, setOf(1L, 2L, 4L), emptyList())

        assertEquals(listOf("Hindi 90s", "Hindi New", "Marathi"), content.folders.map { it.name })
        assertEquals(listOf(42, 0, 29), content.folders.map { it.songCount })
    }

    @Test
    fun recentlyAddedIsNewestFirstAndCapped() {
        val songs = (1..20).map { song("s$it", addedMs = it * 10L) }

        val content = buildHomeContent(emptyList(), emptyMap(), emptySet(), songs.shuffled())

        assertEquals(RECENTLY_ADDED_COUNT, content.recentlyAdded.size)
        assertEquals("s20", content.recentlyAdded.first().id)
        assertEquals("s9", content.recentlyAdded.last().id)
    }

    @Test
    fun emptyLibraryIsEmpty() {
        assertTrue(buildHomeContent(emptyList(), emptyMap(), emptySet(), emptyList()).isEmpty)
    }

    @Test
    fun greetingFollowsTheHour() {
        assertEquals("Good evening", greetingFor(0))
        assertEquals("Good evening", greetingFor(4))
        assertEquals("Good morning", greetingFor(5))
        assertEquals("Good morning", greetingFor(11))
        assertEquals("Good afternoon", greetingFor(12))
        assertEquals("Good afternoon", greetingFor(16))
        assertEquals("Good evening", greetingFor(17))
        assertEquals("Good evening", greetingFor(23))
    }

    @Test
    fun songCountLabelIsSingularForOne() {
        assertEquals("1 song", songCountLabel(1))
        assertEquals("0 songs", songCountLabel(0))
        assertEquals("42 songs", songCountLabel(42))
    }
}
