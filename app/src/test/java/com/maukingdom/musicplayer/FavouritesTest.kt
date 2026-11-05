package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.FavouritesRules
import com.maukingdom.musicplayer.data.PlaylistSongEntity
import com.maukingdom.musicplayer.data.planPrepend
import com.maukingdom.musicplayer.ui.v2.library.LibrarySort
import com.maukingdom.musicplayer.ui.v2.library.playlistItems
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.ui.v2.studio.favouriteHeartLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {

    private val library = setOf("a", "b", "c", "d", "e")

    /** A tiny in-memory Favourites playlist using the same rules as the repository. */
    private class FakeFavourites(private val library: Set<String>) {
        val entries = mutableListOf<PlaylistSongEntity>()

        fun order(): List<String> = entries.sortedBy { it.position }.map { it.songId }

        fun add(songIds: List<String>): Int {
            val plan = planPrepend(
                playlistId = 1L,
                existingIds = entries.mapTo(HashSet()) { it.songId },
                firstPosition = entries.minOfOrNull { it.position } ?: 0L,
                songIds = songIds,
                knownSongIds = library
            )
            entries += plan.entries
            return plan.skipped
        }

        fun toggle(songId: String): Boolean {
            val isFavourite = entries.any { it.songId == songId }
            return if (FavouritesRules.shouldAdd(isFavourite)) {
                add(listOf(songId))
                true
            } else {
                entries.removeAll { it.songId == songId }
                false
            }
        }
    }

    // Toggle

    @Test
    fun heartToggleAddsThenRemoves() {
        val favourites = FakeFavourites(library)

        assertTrue(favourites.toggle("a"))
        assertEquals(listOf("a"), favourites.order())
        assertFalse(favourites.toggle("a"))
        assertEquals(emptyList<String>(), favourites.order())
        assertTrue(FavouritesRules.shouldAdd(isFavourite = false))
        assertFalse(FavouritesRules.shouldAdd(isFavourite = true))
    }

    @Test
    fun heartLabelsForTalkBack() {
        assertEquals("Add to Favourites", favouriteHeartLabel(isFavourite = false))
        assertEquals("Remove from Favourites", favouriteHeartLabel(isFavourite = true))
    }

    // Recreate if deleted

    @Test
    fun savedIdIsUsedOnlyWhileThePlaylistExists() {
        assertEquals(5L, FavouritesRules.usableId(savedId = 5L, existingPlaylistIds = setOf(1L, 5L)))
        // The user deleted Favourites: the next heart creates it again.
        assertNull(FavouritesRules.usableId(savedId = 5L, existingPlaylistIds = setOf(1L, 2L)))
        // Never created yet.
        assertNull(FavouritesRules.usableId(savedId = null, existingPlaylistIds = setOf(1L)))
        assertEquals("Favourites", FavouritesRules.NAME)
    }

    // Ordering

    @Test
    fun newestFavouritesAreAtTheTop() {
        val favourites = FakeFavourites(library)

        favourites.add(listOf("a"))
        favourites.add(listOf("b"))
        favourites.add(listOf("c", "d"))

        // A multi-select block keeps its order, above everything older.
        assertEquals(listOf("c", "d", "b", "a"), favourites.order())
    }

    @Test
    fun addingSkipsSongsAlreadyThereAndKeepsTheirPlace() {
        val favourites = FakeFavourites(library)
        favourites.add(listOf("a", "b"))

        val skipped = favourites.add(listOf("b", "e", "gone"))

        assertEquals(1, skipped)
        assertEquals(listOf("e", "a", "b"), favourites.order())
    }

    @Test
    fun prependIntoAnEmptyPlaylistStartsBelowZero() {
        val plan = planPrepend(1L, emptySet(), firstPosition = 0L, songIds = listOf("a", "b"), knownSongIds = library)

        assertEquals(listOf(-2L, -1L), plan.entries.map { it.position })
    }

    // Badge

    @Test
    fun onlyTheFavouritesPlaylistGetsTheHeartBadge() {
        val playlists = listOf(
            PlaylistEntity(id = 1L, name = "Favourites", createdAtMs = 0L),
            PlaylistEntity(id = 2L, name = "Hindi 90s", createdAtMs = 0L)
        )
        val items = playlistItems(playlists, emptyMap(), setOf(2L), foldersOnly = false, sort = LibrarySort.entries.first(), favouritesId = 1L)

        assertTrue(items.first { it.id == 1L }.isFavourites)
        assertFalse(items.first { it.id == 2L }.isFavourites)
        assertTrue(items.first { it.id == 2L }.isFolder)
    }
}
