package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.PlaylistEdit
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.RemovedSong
import com.maukingdom.musicplayer.data.planAppend
import com.maukingdom.musicplayer.data.planRemoval
import com.maukingdom.musicplayer.data.restoreOrder
import com.maukingdom.musicplayer.ui.v2.playlistpicker.PickerMode
import com.maukingdom.musicplayer.ui.v2.playlistpicker.PickerRequest
import com.maukingdom.musicplayer.ui.v2.playlistpicker.PlaylistEditKind
import com.maukingdom.musicplayer.ui.v2.playlistpicker.importOffer
import com.maukingdom.musicplayer.ui.v2.playlistpicker.moveFromFolderNote
import com.maukingdom.musicplayer.ui.v2.playlistpicker.pickerRowDescription
import com.maukingdom.musicplayer.ui.v2.playlistpicker.pickerRows
import com.maukingdom.musicplayer.ui.v2.playlistpicker.playlistEditMessage
import com.maukingdom.musicplayer.ui.v2.playlistpicker.pruneSelection
import com.maukingdom.musicplayer.ui.v2.playlistpicker.selectionInListOrder
import com.maukingdom.musicplayer.ui.v2.playlistpicker.showPickerSearch
import com.maukingdom.musicplayer.ui.v2.playlistpicker.toggleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistEditsTest {

    private val library = setOf("a", "b", "c", "d", "e", "f")

    // planAppend: dedupe, skip counts, order

    @Test
    fun appendKeepsGivenOrderAfterTheLastPosition() {
        val plan = planAppend(7L, existingIds = setOf("x"), lastPosition = 4L, songIds = listOf("c", "a", "b"), knownSongIds = library)

        assertEquals(listOf("c", "a", "b"), plan.entries.map { it.songId })
        assertEquals(listOf(5L, 6L, 7L), plan.entries.map { it.position })
        assertTrue(plan.entries.all { it.playlistId == 7L })
        assertEquals(0, plan.skipped)
    }

    @Test
    fun appendSkipsSongsAlreadyThereAndCountsThem() {
        val plan = planAppend(1L, existingIds = setOf("b", "d"), lastPosition = 1L, songIds = listOf("a", "b", "c", "d", "e"), knownSongIds = library)

        assertEquals(listOf("a", "c", "e"), plan.entries.map { it.songId })
        // No gaps where the skipped songs were.
        assertEquals(listOf(2L, 3L, 4L), plan.entries.map { it.position })
        assertEquals(2, plan.skipped)
    }

    @Test
    fun appendCountsDuplicateRequestsOnce() {
        val plan = planAppend(1L, existingIds = setOf("b"), lastPosition = -1L, songIds = listOf("a", "a", "b", "b"), knownSongIds = library)

        assertEquals(listOf("a"), plan.entries.map { it.songId })
        assertEquals(0L, plan.entries.single().position)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun appendDropsSongsNoLongerInTheLibraryWithoutCounting() {
        val plan = planAppend(1L, existingIds = emptySet(), lastPosition = -1L, songIds = listOf("gone", "a"), knownSongIds = library)

        assertEquals(listOf("a"), plan.entries.map { it.songId })
        assertEquals(0, plan.skipped)
    }

    // planRemoval / restoreOrder

    @Test
    fun removalRecordsIndicesInPlaylistOrder() {
        val removed = planRemoval(listOf("a", "b", "c", "d"), listOf("d", "b", "zz"))

        assertEquals(listOf(RemovedSong("b", 1), RemovedSong("d", 3)), removed)
    }

    @Test
    fun undoRestoresTheExactOriginalOrder() {
        val original = listOf("a", "b", "c", "d", "e")
        val removed = planRemoval(original, listOf("a", "c", "e"))
        val after = original.filterNot { id -> removed.any { it.songId == id } }

        assertEquals(listOf("b", "d"), after)
        assertEquals(original, restoreOrder(after, removed))
    }

    @Test
    fun undoAfterTheListChangedPutsSongsAsCloseAsPossible() {
        val removed = listOf(RemovedSong("x", 1), RemovedSong("y", 9))

        assertEquals(listOf("a", "x", "b", "y"), restoreOrder(listOf("a", "b"), removed))
    }

    @Test
    fun undoNeverDuplicatesASongThatIsAlreadyBack() {
        val removed = listOf(RemovedSong("b", 1))

        assertEquals(listOf("a", "b", "c"), restoreOrder(listOf("a", "b", "c"), removed))
    }

    // Move = add + remove

    @Test
    fun moveCountsAreTheAddAndTheRemoval() {
        val source = listOf("a", "b", "c", "d")
        val target = listOf("c", "z")
        val moving = listOf("b", "c", "d")

        val removed = planRemoval(source, moving)
        val ordered = moving.filter { id -> removed.any { it.songId == id } }
        val add = planAppend(2L, target.toSet(), lastPosition = 1L, songIds = ordered, knownSongIds = library + "z")

        // All three leave the source; "c" was already in the target.
        assertEquals(3, removed.size)
        assertEquals(listOf("b", "d"), add.entries.map { it.songId })
        assertEquals(1, add.skipped)

        val edit = PlaylistEdit(
            addedTo = 2L,
            addedSongIds = add.entries.map { it.songId },
            skipped = add.skipped,
            removedFrom = 1L,
            removed = removed
        )
        assertEquals("Moved 3 songs to Hindi New (1 was already there)", playlistEditMessage(PlaylistEditKind.MOVE, edit, "Hindi New"))

        // Undo: the target loses only what was added; the source gets its songs back in place.
        val targetAfterUndo = (target + edit.addedSongIds) - edit.addedSongIds.toSet()
        assertEquals(target, targetAfterUndo)
        assertEquals(source, restoreOrder(listOf("a"), edit.removed))
    }

    // Snackbar text

    @Test
    fun addMessagesMatchTheSpec() {
        fun msg(added: Int, skipped: Int) = playlistEditMessage(
            PlaylistEditKind.ADD,
            PlaylistEdit(addedTo = 1L, addedSongIds = List(added) { "s$it" }, skipped = skipped),
            "Hindi New"
        )

        assertEquals("Added to Hindi New", msg(1, 0))
        assertEquals("Added 5 songs to Hindi New", msg(5, 0))
        assertEquals("Added 5 songs to Hindi New (2 were already there)", msg(5, 2))
        assertEquals("Already in Hindi New", msg(0, 1))
        assertEquals("All 3 songs were already in Hindi New", msg(0, 3))
    }

    @Test
    fun singleMoveMessage() {
        val edit = PlaylistEdit(addedTo = 2L, addedSongIds = listOf("a"), removedFrom = 1L, removed = listOf(RemovedSong("a", 0)))

        assertEquals("Moved to Hindi New", playlistEditMessage(PlaylistEditKind.MOVE, edit, "Hindi New"))
    }

    @Test
    fun nothingChangedHasNothingToUndo() {
        assertFalse(PlaylistEdit(addedTo = 1L, skipped = 1).changedAnything)
        assertTrue(PlaylistEdit(createdPlaylistId = 4L).changedAnything)
    }

    @Test
    fun folderMoveNoteNamesTheFolderPlaylist() {
        assertEquals("It won't come back when Hindi 90s syncs.", moveFromFolderNote("Hindi 90s", 1))
    }

    // Picker rows

    private val playlists = listOf(
        PlaylistEntity(id = 3L, name = "rock", createdAtMs = 0L),
        PlaylistEntity(id = 1L, name = "Hindi New", createdAtMs = 0L),
        PlaylistEntity(id = 2L, name = "Hindi 90s", createdAtMs = 0L),
        PlaylistEntity(id = 4L, name = "Ambient", createdAtMs = 0L)
    )

    @Test
    fun rowsAreSortedAToZIgnoringCase() {
        val rows = pickerRows(PickerRequest(PickerMode.ADD, listOf("a", "b")), playlists, emptyMap(), emptySet(), emptySet(), "")

        assertEquals(listOf("Ambient", "Hindi 90s", "Hindi New", "rock"), rows.map { it.name })
    }

    @Test
    fun singleSongMarksPlaylistsThatAlreadyHoldIt() {
        val rows = pickerRows(
            PickerRequest(PickerMode.ADD, listOf("a")),
            playlists,
            counts = mapOf(1L to 12),
            folderIds = setOf(2L),
            containing = setOf(1L),
            query = ""
        )
        val hindiNew = rows.first { it.id == 1L }
        val hindi90s = rows.first { it.id == 2L }

        assertTrue(hindiNew.alreadyAdded)
        assertFalse(hindi90s.alreadyAdded)
        assertEquals("Already in Hindi New", pickerRowDescription(hindiNew, PickerMode.ADD))
        assertEquals("Add to Hindi 90s", pickerRowDescription(hindi90s, PickerMode.ADD))
        assertEquals("Playlist · 12 songs", hindiNew.meta)
        assertEquals("SD folder · 0 songs", hindi90s.meta)
    }

    @Test
    fun severalSongsAreNeverMarkedAlreadyAdded() {
        val rows = pickerRows(PickerRequest(PickerMode.ADD, listOf("a", "b")), playlists, emptyMap(), emptySet(), setOf(1L), "")

        assertTrue(rows.none { it.alreadyAdded })
    }

    @Test
    fun moveHidesTheSourcePlaylist() {
        val request = PickerRequest(PickerMode.MOVE, listOf("a"), sourcePlaylistId = 2L)
        val rows = pickerRows(request, playlists, emptyMap(), emptySet(), emptySet(), "")

        assertTrue(rows.none { it.id == 2L })
        assertEquals("Move to Hindi New", pickerRowDescription(rows.first { it.id == 1L }, PickerMode.MOVE))
    }

    @Test
    fun queryFiltersByName() {
        val rows = pickerRows(PickerRequest(PickerMode.ADD, listOf("a")), playlists, emptyMap(), emptySet(), emptySet(), " hindi ")

        assertEquals(listOf(2L, 1L), rows.map { it.id })
    }

    @Test
    fun searchShowsAboveEightPlaylists() {
        assertFalse(showPickerSearch(8))
        assertTrue(showPickerSearch(9))
    }

    // Import handoff

    @Test
    fun importOfferHandsOverExactlyTheImportedSongs() {
        val offer = importOffer(listOf("s1", "s2", "s1", "s3"))!!

        assertEquals(listOf("s1", "s2", "s3"), offer.songIds)
        assertEquals("3 songs added to library", offer.message)
        assertEquals("1 song added to library", importOffer(listOf("s1"))!!.message)
        assertNull(importOffer(emptyList()))
    }

    // Selection

    @Test
    fun selectionTogglesAndKeepsListOrderForActions() {
        var selected = toggleSelection(emptyList(), "c")
        selected = toggleSelection(selected, "a")
        selected = toggleSelection(selected, "b")
        selected = toggleSelection(selected, "b")

        assertEquals(listOf("c", "a"), selected)
        assertEquals(listOf("a", "c"), selectionInListOrder(listOf("a", "b", "c"), selected))
        assertEquals(listOf("a"), pruneSelection(selected, listOf("a", "b")))
    }
}
