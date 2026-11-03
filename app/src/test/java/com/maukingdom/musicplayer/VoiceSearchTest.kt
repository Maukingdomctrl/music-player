package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.playback.VoiceMatch
import com.maukingdom.musicplayer.playback.VoiceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Unknown artist",
        album: String = "Unknown album"
    ) = SongEntity(
        id = id,
        uri = id,
        title = title,
        artist = artist,
        album = album,
        genre = null,
        year = null,
        durationMs = 200_000L,
        artworkUri = null,
        mimeType = "audio/mpeg",
        dateAddedMs = 1L
    )

    private fun playlist(id: Long, name: String) =
        PlaylistEntity(id = id, name = name, createdAtMs = 0L)

    private val songs = listOf(
        song("s1", "Tujhe Dekha To", "Kumar Sanu", "Dilwale Dulhania Le Jayenge"),
        song("s2", "Kajra Re", "Alisha Chinai", "Bunty Aur Babli"),
        song("s3", "Zingaat", "Ajay-Atul", "Sairat"),
        song("s4", "Mala Ved Laagale", "Ajay-Atul", "Time Pass"),
        song("s5", "Dil Se Re", "A. R. Rahman", "Dil Se.."),
        song("s6", "Chaiyya Chaiyya", "Sukhwinder Singh", "Dil Se.."),
        song("s7", "Don't Stop Me Now", "Queen", "Jazz")
    )

    private val playlists = listOf(
        playlist(1L, "Hindi 90s"),
        playlist(2L, "Marathi Hits"),
        playlist(3L, "Road Trip Hindi 90s Extended"),
        playlist(4L, "Sairat")
    )

    private val folderLinked = setOf(1L)

    private fun match(query: String) =
        VoiceSearch.match(query, playlists, folderLinked, songs)

    // Level 1
    @Test
    fun playlistNameExact() {
        assertEquals(VoiceMatch.Playlist(1L, exact = true), match("hindi 90s"))
    }

    // Level 2
    @Test
    fun playlistNameContainsPrefersShortestName() {
        assertEquals(VoiceMatch.Playlist(2L, exact = false), match("marathi"))
        assertEquals(VoiceMatch.Playlist(3L, exact = false), match("road trip"))
    }

    // Level 3
    @Test
    fun songTitleExact() {
        assertEquals(VoiceMatch.Song("s2", exact = true), match("Kajra Re"))
    }

    // Level 4
    @Test
    fun songTitleContains() {
        assertEquals(VoiceMatch.Song("s1", exact = false), match("tujhe dekha"))
    }

    // Level 5
    @Test
    fun artistContainsQueuesAllTheirSongsAToZ() {
        assertEquals(VoiceMatch.Artist(listOf("s4", "s3")), match("ajay atul"))
    }

    // Level 6
    @Test
    fun albumContains() {
        // Only album names contain these, so they reach level 6.
        assertEquals(VoiceMatch.Album(listOf("s2")), match("bunty aur babli"))
        assertEquals(VoiceMatch.Album(listOf("s7")), match("jazz"))
    }

    @Test
    fun priorityOrderPlaylistBeatsSongBeatsArtistBeatsAlbum() {
        // "Sairat" is a playlist name (level 1) and an album (level 6).
        assertEquals(VoiceMatch.Playlist(4L, exact = true), match("sairat"))

        // "dil se" is in a song title (level 4) and an album (level 6).
        assertEquals(VoiceMatch.Song("s5", exact = false), match("dil se"))

        // Exact title (level 3) beats a contains-title (level 4).
        val extra = songs + song("s8", "Kajra Re Remix")
        assertEquals(
            VoiceMatch.Song("s2", exact = true),
            VoiceSearch.match("kajra re", playlists, folderLinked, extra)
        )
    }

    @Test
    fun exactPlaylistTiePrefersFolderLinked() {
        val twins = listOf(playlist(10L, "Drive"), playlist(11L, "drive"))

        assertEquals(
            VoiceMatch.Playlist(11L, exact = true),
            VoiceSearch.match("Drive", twins, setOf(11L), songs)
        )
    }

    @Test
    fun hindiAndMarathiInLatinScriptIgnoreCaseSpacesAndPunctuation() {
        assertEquals(VoiceMatch.Song("s1", exact = true), match("  TUJHE   dekha, TO!! "))
        assertEquals(VoiceMatch.Song("s4", exact = true), match("mala-ved-laagale"))
        assertEquals(VoiceMatch.Song("s3", exact = true), match("Zingāt".replace("ā", "aa")))
        assertEquals(VoiceMatch.Song("s5", exact = true), match("Dil... Se, Re"))
        assertEquals(VoiceMatch.Song("s7", exact = true), match("dont stop me now"))
        assertEquals(VoiceMatch.Artist(listOf("s5")), match("a r rahman"))
    }

    @Test
    fun latinAccentsAreIgnoredButDevanagariIsKept() {
        assertEquals("zingat", VoiceSearch.normalize("Zíngât!"))
        assertEquals("दिल से रे", VoiceSearch.normalize("  दिल से, रे! "))

        val devanagari = songs + song("s9", "दिल से रे")
        assertEquals(
            VoiceMatch.Song("s9", exact = true),
            VoiceSearch.match("दिल से रे", emptyList(), emptySet(), devanagari)
        )
    }

    @Test
    fun blankQuery() {
        for (query in listOf("", "   ", "?!.,", "\t\n")) {
            assertEquals(VoiceMatch.BlankQuery, match(query))
        }
    }

    @Test
    fun noMatch() {
        assertEquals(VoiceMatch.NoMatch, match("bohemian rhapsody"))
        assertEquals(VoiceMatch.NoMatch, VoiceSearch.match("anything", emptyList(), emptySet(), emptyList()))
    }

    @Test
    fun searchResultsListPlaylistsThenSongsCappedAt50() {
        val results = VoiceSearch.searchResults("hindi", playlists, folderLinked, songs)

        assertEquals(listOf(1L, 3L), results.playlists.map { it.id })
        assertTrue(results.songs.isEmpty())

        val manySongs = (1..80).map { song("m$it", "Hindi Song $it") }
        val capped = VoiceSearch.searchResults("hindi", playlists, folderLinked, manySongs)

        assertEquals(50, capped.size)
        assertEquals(listOf(1L, 3L), capped.playlists.map { it.id })
        assertEquals(48, capped.songs.size)

        assertEquals(0, VoiceSearch.searchResults("  ", playlists, folderLinked, songs).size)
    }

    @Test
    fun searchResultsRankTitleBeforeArtistBeforeAlbum() {
        val results = VoiceSearch.searchResults("dil se", emptyList(), emptySet(), songs)

        // s5's title contains it; s6 only via its album.
        assertEquals(listOf("s5", "s6"), results.songs.map { it.id })
    }
}
