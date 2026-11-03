package com.maukingdom.musicplayer

import android.provider.DocumentsContract
import com.maukingdom.musicplayer.data.FolderEntry
import com.maukingdom.musicplayer.data.folderNameFallback
import com.maukingdom.musicplayer.data.folderSortKey
import com.maukingdom.musicplayer.data.isAudioCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderScannerLogicTest {

    @Test
    fun audioMimeTypesAreCandidates() {
        assertTrue(isAudioCandidate("audio/mpeg", "song.mp3"))
        assertTrue(isAudioCandidate("audio/flac", "song.flac"))
    }

    @Test
    fun nonAudioMimeTypesAndDirectoriesAreNotCandidates() {
        assertFalse(isAudioCandidate("image/jpeg", "cover.jpg"))
        assertFalse(isAudioCandidate(DocumentsContract.Document.MIME_TYPE_DIR, "Hindi 90s"))
        assertFalse(isAudioCandidate(DocumentsContract.Document.MIME_TYPE_DIR, "album.mp3"))
    }

    @Test
    fun missingOrGenericMimeTypeFallsBackToExtension() {
        assertTrue(isAudioCandidate(null, "song.MP3"))
        assertTrue(isAudioCandidate("application/octet-stream", "a.flac"))
        assertTrue(isAudioCandidate("application/ogg", "track.ogg"))
        assertFalse(isAudioCandidate("application/octet-stream", "notes.txt"))
        assertFalse(isAudioCandidate(null, null))
        assertFalse(isAudioCandidate(null, "no-extension"))
    }

    @Test
    fun playlistTextAndImageFilesAreNeverAudio() {
        val names = listOf(
            "Hits.m3u", "Hits.M3U8", "radio.pls", "album.cue", "readme.txt",
            "song.lrc", "info.nfo", "cover.jpg", "Folder.JPEG", "art.png"
        )
        val mimes = listOf(
            null,
            "",
            "application/octet-stream",
            // Some providers label playlists as audio.
            "audio/x-mpegurl",
            "audio/mpegurl",
            "audio/x-scpls",
            "audio/mpeg"
        )

        for (name in names) {
            for (mime in mimes) {
                assertFalse("$name as $mime", isAudioCandidate(mime, name))
            }
        }
    }

    @Test
    fun realSongsStillCountWithTheSameMimeTypes() {
        assertTrue(isAudioCandidate(null, "Kajra Re.mp3"))
        assertTrue(isAudioCandidate("application/octet-stream", "Zingaat.m4a"))
        assertTrue(isAudioCandidate("audio/mpeg", "Dil Se Re.mp3"))
        assertTrue(isAudioCandidate("audio/mpeg", null))
        // Names with dots and odd characters.
        assertTrue(isAudioCandidate(null, "01. Tujhe Dekha To (Remix) [128k].mp3"))
        assertTrue(isAudioCandidate("audio/flac", "lyrics.txt.flac"))
    }

    @Test
    fun folderNameFallbackUsesLastPathSegment() {
        assertEquals("Hindi 90s", folderNameFallback("1234-ABCD:Music/MyCarMusic/Hindi 90s"))
        assertEquals("Old Classics", folderNameFallback("primary:Old Classics"))
        assertEquals("Folder", folderNameFallback("1234-ABCD:"))
        assertEquals("Folder", folderNameFallback(""))
    }

    @Test
    fun entriesSortCaseInsensitivelyByName() {
        val entries = listOf(
            FolderEntry("doc-1", "b.mp3", "audio/mpeg"),
            FolderEntry("doc-2", "A.mp3", "audio/mpeg"),
            FolderEntry("doc-3", "a2.mp3", "audio/mpeg")
        )

        assertEquals(
            listOf("A.mp3", "a2.mp3", "b.mp3"),
            entries.sortedWith(folderSortKey).map { it.displayName }
        )
    }

    @Test
    fun equalNamesSortByDocumentId() {
        val entries = listOf(
            FolderEntry("doc-b", "Song.mp3", "audio/mpeg"),
            FolderEntry("doc-a", "song.mp3", "audio/mpeg"),
            FolderEntry("doc-c", "SONG.mp3", "audio/mpeg")
        )

        assertEquals(
            listOf("doc-a", "doc-b", "doc-c"),
            entries.sortedWith(folderSortKey).map { it.documentId }
        )
    }
}
