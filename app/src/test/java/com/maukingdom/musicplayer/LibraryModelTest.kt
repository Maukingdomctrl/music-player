package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.FolderOperationKind
import com.maukingdom.musicplayer.ui.v2.library.LibraryFilter
import com.maukingdom.musicplayer.ui.v2.library.LibrarySort
import com.maukingdom.musicplayer.ui.v2.library.UNKNOWN_ALBUM
import com.maukingdom.musicplayer.ui.v2.library.UNKNOWN_ARTIST
import com.maukingdom.musicplayer.ui.v2.library.VARIOUS_ARTISTS
import com.maukingdom.musicplayer.ui.v2.library.albumMetaText
import com.maukingdom.musicplayer.ui.v2.library.decodeSorts
import com.maukingdom.musicplayer.ui.v2.library.encodeSorts
import com.maukingdom.musicplayer.ui.v2.library.groupByAlbum
import com.maukingdom.musicplayer.ui.v2.library.groupByArtist
import com.maukingdom.musicplayer.ui.v2.library.nextSort
import com.maukingdom.musicplayer.ui.v2.library.playlistItems
import com.maukingdom.musicplayer.ui.v2.library.playlistMetaText
import com.maukingdom.musicplayer.ui.v2.library.progressText
import com.maukingdom.musicplayer.ui.v2.library.sortSongs
import com.maukingdom.musicplayer.ui.v2.library.sortsFor
import com.maukingdom.musicplayer.ui.v2.library.validSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryModelTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "A",
        album: String = "X",
        added: Long = 0L
    ) = SongEntity(
        id = id, uri = "content://$id", title = title, artist = artist, album = album,
        genre = null, year = null, durationMs = 1_000L, artworkUri = null, mimeType = null,
        dateAddedMs = added
    )

    private val playlists = listOf(
        PlaylistEntity(id = 1, name = "road trip", createdAtMs = 100),
        PlaylistEntity(id = 2, name = "Hindi 90s", createdAtMs = 300),
        PlaylistEntity(id = 3, name = "Alpha", createdAtMs = 200)
    )
    private val counts = mapOf(1L to 5, 2L to 40, 3L to 5)
    private val folders = setOf(2L)

    @Test
    fun playlistSortOrders() {
        fun ids(sort: LibrarySort) = playlistItems(playlists, counts, folders, false, sort).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids(LibrarySort.RECENT))
        // Case-insensitive: "Alpha" < "Hindi 90s" < "road trip".
        assertEquals(listOf(3L, 2L, 1L), ids(LibrarySort.A_TO_Z))
        // Ties on count fall back to the name.
        assertEquals(listOf(2L, 3L, 1L), ids(LibrarySort.MOST_SONGS))
    }

    @Test
    fun foldersChipShowsOnlyFolderPlaylists() {
        val items = playlistItems(playlists, counts, folders, true, LibrarySort.RECENT)
        assertEquals(listOf(2L), items.map { it.id })
        assertEquals(true, items.single().isFolder)
        assertEquals(0, playlistItems(playlists, emptyMap(), folders, true, LibrarySort.RECENT).single().songCount)
    }

    @Test
    fun songSortOrders() {
        val songs = listOf(
            song("1", "beta", artist = "Zed", added = 10),
            song("2", "Alpha", artist = "mike", added = 30),
            song("3", "charlie", artist = "Mike", added = 20)
        )
        assertEquals(listOf("2", "3", "1"), sortSongs(songs, LibrarySort.RECENT).map { it.id })
        assertEquals(listOf("2", "1", "3"), sortSongs(songs, LibrarySort.A_TO_Z).map { it.id })
        assertEquals(listOf("2", "3", "1"), sortSongs(songs, LibrarySort.ARTIST).map { it.id })
    }

    @Test
    fun artistsMergeCaseInsensitively() {
        val songs = listOf(
            song("1", "b", artist = "ABBA"),
            song("2", "a", artist = "abba"),
            song("3", "c", artist = "ABBA"),
            song("4", "d", artist = "  ")
        )
        val groups = groupByArtist(songs, LibrarySort.A_TO_Z)
        assertEquals(listOf("ABBA", UNKNOWN_ARTIST), groups.map { it.name })
        // Most-used spelling wins; songs by title; cover = first song.
        assertEquals(listOf("2", "1", "3"), groups.first().songs.map { it.id })
        assertEquals("2", groups.first().coverSongId)
    }

    @Test
    fun albumsGroupWithArtistOrVarious() {
        val songs = listOf(
            song("1", "t1", artist = "Kumar", album = "Dil", added = 2),
            song("2", "t2", artist = "Alka", album = "dil", added = 1),
            song("3", "t3", artist = "Kumar", album = "Solo", added = 3),
            song("4", "t4", artist = "Kumar", album = "Solo", added = 4),
            song("5", "t5", artist = "Kumar", album = "Solo", added = 5),
            song("6", "t6", artist = "Kumar", album = "")
        )
        val az = groupByAlbum(songs, LibrarySort.A_TO_Z)
        assertEquals(3, az.size)
        val dil = az.first { it.key == "dil" }
        assertEquals(VARIOUS_ARTISTS, dil.artist)
        assertEquals(listOf("2", "1"), dil.songs.map { it.id })
        assertEquals("Kumar", az.first { it.key == "solo" }.artist)
        assertEquals(UNKNOWN_ALBUM, az.first { it.key == UNKNOWN_ALBUM.lowercase() }.name)

        val most = groupByAlbum(songs, LibrarySort.MOST_SONGS)
        assertEquals("solo", most.first().key)
    }

    @Test
    fun metaTextAndSingularSong() {
        assertEquals("SD folder · 12 songs", playlistMetaText(true, 12))
        assertEquals("Playlist · 1 song", playlistMetaText(false, 1))
        assertEquals("Playlist · 0 songs", playlistMetaText(false, 0))
        assertEquals("Kumar · 1 song", albumMetaText("Kumar", 1))
    }

    @Test
    fun sortsCycleAndStayValid() {
        assertEquals(LibrarySort.A_TO_Z, nextSort(LibraryFilter.PLAYLISTS, LibrarySort.RECENT))
        assertEquals(LibrarySort.RECENT, nextSort(LibraryFilter.SONGS, LibrarySort.ARTIST))
        assertEquals(LibrarySort.A_TO_Z, nextSort(LibraryFilter.ARTISTS, LibrarySort.MOST_SONGS))
        // A sort the chip does not offer restarts at its default.
        assertEquals(LibrarySort.A_TO_Z, nextSort(LibraryFilter.ALBUMS, LibrarySort.RECENT))
        assertEquals(LibrarySort.A_TO_Z, validSort(LibraryFilter.ARTISTS, LibrarySort.RECENT))
        assertEquals(LibrarySort.RECENT, validSort(LibraryFilter.SONGS, null))
        LibraryFilter.entries.forEach { assertEquals(sortsFor(it).first(), validSort(it, null)) }
    }

    @Test
    fun sortsRoundTripAndIgnoreJunk() {
        val sorts = mapOf(LibraryFilter.SONGS to LibrarySort.ARTIST, LibraryFilter.ALBUMS to LibrarySort.MOST_SONGS)
        assertEquals(sorts, decodeSorts(encodeSorts(sorts)))
        assertEquals(emptyMap<LibraryFilter, LibrarySort>(), decodeSorts(""))
        assertEquals(
            mapOf(LibraryFilter.SONGS to LibrarySort.A_TO_Z),
            decodeSorts("SONGS=A_TO_Z,NOPE=AZ,ARTISTS=RECENT,garbage")
        )
        assertNull(decodeSorts("ARTISTS=RECENT")[LibraryFilter.ARTISTS])
    }

    @Test
    fun progressTexts() {
        assertEquals("Syncing Hindi 90s…", progressText(FolderOperationKind.SYNC, "Hindi 90s"))
        assertEquals("Syncing folder…", progressText(FolderOperationKind.SYNC, null))
        assertEquals("Importing SD-card folder…", progressText(FolderOperationKind.IMPORT, null))
        assertEquals("Adding music…", progressText(null, null))
    }
}
