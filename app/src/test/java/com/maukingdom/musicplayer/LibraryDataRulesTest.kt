package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.GrantPolicy
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMatcher
import com.maukingdom.musicplayer.data.mergeImportedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDataRulesTest {

    private fun song(
        id: String = "content://media/external/audio/media/1",
        title: String = "Kajra Re",
        artworkUri: String? = null,
        edited: Boolean = false,
        dateAdded: Long = 100L
    ) = SongEntity(
        id = id,
        uri = id,
        title = title,
        artist = "Alisha Chinai",
        album = "Bunty Aur Babli",
        genre = null,
        year = 2005,
        durationMs = 240_000L,
        artworkUri = artworkUri,
        mimeType = "audio/mpeg",
        metadataEdited = edited,
        dateAddedMs = dateAdded
    )

    // #14

    @Test
    fun emptyArtworkNeverReplacesAKnownOne() {
        val existing = song(artworkUri = "content://media/external/audio/albumart/7")

        for (incoming in listOf(null, "", "   ")) {
            val merged = mergeImportedSong(existing, song(artworkUri = incoming, dateAdded = 999L))
            assertEquals("for '$incoming'", "content://media/external/audio/albumart/7", merged.artworkUri)
        }
    }

    @Test
    fun aNewArtworkLinkStillReplacesTheOldOne() {
        val merged = mergeImportedSong(
            song(artworkUri = "content://media/external/audio/albumart/7"),
            song(artworkUri = "content://media/external/audio/albumart/8")
        )
        assertEquals("content://media/external/audio/albumart/8", merged.artworkUri)
    }

    @Test
    fun editedTextAndDateAddedAreKept() {
        val existing = song(title = "Kajra Re (fixed)", edited = true, dateAdded = 100L,
            artworkUri = "content://media/external/audio/albumart/7")
        val merged = mergeImportedSong(existing, song(title = "kajra re", dateAdded = 999L))

        assertEquals("Kajra Re (fixed)", merged.title)
        assertTrue(merged.metadataEdited)
        assertEquals(100L, merged.dateAddedMs)
        assertEquals("content://media/external/audio/albumart/7", merged.artworkUri)

        val plain = mergeImportedSong(song(dateAdded = 100L), song(title = "New Tag", dateAdded = 999L))
        assertEquals("New Tag", plain.title)
        assertFalse(plain.metadataEdited)
        assertEquals(100L, plain.dateAddedMs)
    }

    // #8: removed songs are found with the same matching rules as the library

    @Test
    fun aRemovedSongIsRecognisedByIdOrMediaStoreEquivalent() {
        val removedSaf = "content://docs/tree/sd/a.mp3"
        val removed = SongMatcher(listOf(song(id = removedSaf))) { uri ->
            when (uri) {
                removedSaf -> "content://media/external/audio/media/1"
                else -> if (uri.startsWith("content://media/")) uri else null
            }
        }

        assertEquals(removedSaf, removed.match(removedSaf)?.id)
        // The phone scan sees the same file as a MediaStore URI.
        assertEquals(removedSaf, removed.match("content://media/external/audio/media/1")?.id)
        assertNull(removed.match("content://media/external/audio/media/2"))
    }

    // #9

    @Test
    fun grantWarningAbove400() {
        assertFalse(GrantPolicy.shouldWarn(0))
        assertFalse(GrantPolicy.shouldWarn(400))
        assertTrue(GrantPolicy.shouldWarn(401))
        assertTrue(GrantPolicy.warning(450).contains("450"))
        assertTrue(GrantPolicy.warning(450).contains("Import SD-card folder"))
    }

    @Test
    fun onlySingleFileGrantsOfRemovedSongsAreReleased() {
        assertTrue(GrantPolicy.mayRelease(true, isTreeGrant = false, usedByFolderPlaylist = false, stillUsedBySong = false))
        // Folder grants are never released.
        assertFalse(GrantPolicy.mayRelease(true, isTreeGrant = true, usedByFolderPlaylist = false, stillUsedBySong = false))
        assertFalse(GrantPolicy.mayRelease(true, isTreeGrant = false, usedByFolderPlaylist = true, stillUsedBySong = false))
        // Another song still plays that file.
        assertFalse(GrantPolicy.mayRelease(true, isTreeGrant = false, usedByFolderPlaylist = false, stillUsedBySong = true))
        assertFalse(GrantPolicy.mayRelease(false, isTreeGrant = false, usedByFolderPlaylist = false, stillUsedBySong = false))
    }
}
