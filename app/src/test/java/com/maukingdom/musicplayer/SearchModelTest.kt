package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.v2.contrastRatio
import com.maukingdom.musicplayer.ui.v2.playlist.playlistWashColor
import com.maukingdom.musicplayer.ui.v2.search.MAX_ALBUM_RESULTS
import com.maukingdom.musicplayer.ui.v2.search.MAX_ARTIST_RESULTS
import com.maukingdom.musicplayer.ui.v2.search.MAX_PLAYLIST_RESULTS
import com.maukingdom.musicplayer.ui.v2.search.MAX_SONG_RESULTS
import com.maukingdom.musicplayer.ui.v2.search.SearchContent
import com.maukingdom.musicplayer.ui.v2.search.TILE_FALLBACK_PALETTE
import com.maukingdom.musicplayer.ui.v2.search.browseTiles
import com.maukingdom.musicplayer.ui.v2.search.buildSearchResults
import com.maukingdom.musicplayer.ui.v2.search.tileColorFromWash
import com.maukingdom.musicplayer.ui.v2.search.tileFallbackColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModelTest {

    private fun song(id: String, title: String, artist: String = "Someone", album: String = "Album $id") =
        SongEntity(
            id = id, uri = "content://$id", title = title, artist = artist, album = album,
            genre = null, year = null, durationMs = 1_000L, artworkUri = null, mimeType = null
        )

    private fun playlist(id: Long, name: String) = PlaylistEntity(id = id, name = name, createdAtMs = id)

    private fun results(
        query: String,
        playlists: List<PlaylistEntity> = emptyList(),
        songs: List<SongEntity> = emptyList(),
        folders: Set<Long> = emptySet()
    ): SearchContent.Results {
        val content = buildSearchResults(query, playlists, emptyMap(), folders, songs)
        assertTrue("expected results for '$query'", content is SearchContent.Results)
        return content as SearchContent.Results
    }

    @Test
    fun blankQueryIsBrowse() {
        val songs = listOf(song("1", "Kesariya"))
        for (q in listOf("", "   ", "!!", " - ")) {
            assertSame(q, SearchContent.Browse, buildSearchResults(q, emptyList(), emptyMap(), emptySet(), songs))
        }
    }

    @Test
    fun sectionsAreCapped() {
        val songs = (1..30).map { song("s$it", "Love song $it", artist = "Love artist $it", album = "Love album $it") }
        val playlists = (1L..6L).map { playlist(it, "Love list $it") }
        val r = results("love", playlists, songs)
        assertEquals(MAX_PLAYLIST_RESULTS, r.playlists.size)
        assertEquals(MAX_SONG_RESULTS, r.songs.size)
        assertEquals(MAX_ARTIST_RESULTS, r.artists.size)
        assertEquals(MAX_ALBUM_RESULTS, r.albums.size)
    }

    @Test
    fun matchingIgnoresCaseAccentsAndPunctuation() {
        val songs = listOf(
            song("1", "Kesariyā", artist = "Arijit Singh"),
            song("2", "Dil-Se Re!", artist = "A. R. Rahman"),
            song("3", "Other", artist = "Someone")
        )
        assertEquals(listOf("1"), results("KESARIYA", songs = songs).songs.map { it.id })
        assertEquals(listOf("2"), results("dil se re", songs = songs).songs.map { it.id })
        // Artist matches find the song and the artist group.
        val arijit = results("arijit", songs = songs)
        assertEquals(listOf("1"), arijit.songs.map { it.id })
        assertEquals(listOf("Arijit Singh"), arijit.artists.map { it.name })
    }

    @Test
    fun hindi90MatchesHindi90sLikeVoiceSearch() {
        val playlists = listOf(playlist(1, "Hindi 90s"), playlist(2, "Old Hindi 90s Hits"), playlist(3, "Rock"))
        assertEquals(listOf(1L, 2L), results("hindi 90", playlists).playlists.map { it.id })
        // An exact name comes first.
        assertEquals(1L, results("Hindi 90s", playlists).playlists.first().id)
    }

    @Test
    fun playlistNameMatchComesBeforeSongTitleMatch() {
        val r = results("drive", listOf(playlist(7, "Night Drive")), listOf(song("1", "Drive")))
        // Sections are in order Playlists, Songs: the playlist is the first result.
        assertEquals(listOf(7L), r.playlists.map { it.id })
        assertEquals(listOf("1"), r.songs.map { it.id })
    }

    @Test
    fun exactSongTitleRanksFirst() {
        val songs = listOf(song("1", "Tum Hi Ho Remix"), song("2", "Tum Hi Ho"), song("3", "Song", artist = "Tum Hi Ho band"))
        assertEquals(listOf("2", "1", "3"), results("tum hi ho", songs = songs).songs.map { it.id })
    }

    @Test
    fun artistsGroupCaseInsensitively() {
        val songs = listOf(
            song("1", "A", artist = "ARIJIT SINGH"),
            song("2", "B", artist = "arijit singh"),
            song("3", "C", artist = "Arijit Singh")
        )
        val artists = results("arijit", songs = songs).artists
        assertEquals(1, artists.size)
        assertEquals(3, artists.single().songs.size)
    }

    @Test
    fun exactArtistNameRanksFirst() {
        val songs = listOf(song("1", "A", artist = "Shreya Ghoshal Live"), song("2", "B", artist = "Shreya Ghoshal"))
        assertEquals(listOf("Shreya Ghoshal", "Shreya Ghoshal Live"), results("shreya ghoshal", songs = songs).artists.map { it.name })
    }

    @Test
    fun noResults() {
        val r = results("  zzzz  ", listOf(playlist(1, "Rock")), listOf(song("1", "Song")))
        assertTrue(r.isEmpty)
        assertEquals("zzzz", r.query)
    }

    @Test
    fun browseTilesFoldersFirstThenNonEmptyPlaylists() {
        val playlists = listOf(playlist(1, "zeta"), playlist(2, "Alpha"), playlist(3, "beta folder"), playlist(4, "Empty"))
        val counts = mapOf(1L to 3, 2L to 5, 3L to 0)
        val tiles = browseTiles(playlists, counts, folderPlaylistIds = setOf(3L))
        assertEquals(listOf(3L, 2L, 1L), tiles.map { it.playlistId })
        assertTrue(tiles.first().isFolder)
    }

    private val white = 0xFFFFFFFF.toInt()
    private val bg = 0xFF0B0B0F.toInt()

    @Test
    fun fallbackTileColoursAreReadableAndStable() {
        TILE_FALLBACK_PALETTE.forEach { assertTrue(contrastRatio(white, it) >= 4.5f) }
        for (name in listOf("Hindi 90s", "Road trip", "", "Kesariya", "A")) {
            val colour = tileFallbackColor(name)
            assertTrue(name, contrastRatio(white, colour) >= 4.5f)
            assertEquals(name, colour, tileFallbackColor(name))
        }
        assertEquals(tileFallbackColor("Hindi 90s"), tileFallbackColor("HINDI 90S"))
    }

    @Test
    fun washTileColoursAreReadable() {
        for (cover in listOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF8A3D.toInt(), 0xFF000000.toInt(), 0xFF00FFFF.toInt())) {
            val tile = tileColorFromWash(playlistWashColor(cover, bg), bg)
            assertTrue(Integer.toHexString(cover), contrastRatio(white, tile) >= 4.5f)
        }
        // A wash that is already dark enough is used as it is.
        assertEquals(0xFF1E4C8C.toInt(), tileColorFromWash(0xFF1E4C8C.toInt(), bg))
    }
}
