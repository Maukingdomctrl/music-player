package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.playback.ArtworkPaths
import com.maukingdom.musicplayer.playback.ArtworkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPathsTest {

    private val songIds = listOf(
        "content://media/external/audio/media/101",
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic" +
            "/document/1234-ABCD%3AMusic%2FHindi%2090s%2F01%20Song.mp3",
        "content://example/odd|pipe:colon/slash%7Cpercent?query=1#frag",
        "हिंदी गाना 🎵",
        "a"
    )

    @Test
    fun songIdsRoundTripThroughBase64Url() {
        for (songId in songIds) {
            val encoded = ArtworkPaths.encodeSongId(songId)

            assertTrue(
                "Encoded form must be URL-safe: $encoded",
                encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            )
            assertEquals(songId, ArtworkPaths.decodeSongId(encoded))
            assertEquals(
                ArtworkTarget.Song(songId),
                ArtworkPaths.parse(listOf("song", encoded))
            )
        }
    }

    @Test
    fun songUriUsesTheArtworkAuthority() {
        val uri = ArtworkPaths.songUri(songIds[0])

        assertEquals(
            "content://com.maukingdom.musicplayer.artwork/song/" +
                ArtworkPaths.encodeSongId(songIds[0]),
            uri
        )
    }

    @Test
    fun playlistIdsRoundTrip() {
        for (id in listOf(0L, 7L, Long.MAX_VALUE)) {
            assertEquals(
                "content://com.maukingdom.musicplayer.artwork/playlist/$id",
                ArtworkPaths.playlistUri(id)
            )
            assertEquals(
                ArtworkTarget.Playlist(id),
                ArtworkPaths.parse(listOf("playlist", id.toString()))
            )
        }
    }

    @Test
    fun malformedOrUnsafePathsAreRejected() {
        val valid = ArtworkPaths.encodeSongId(songIds[0])

        val rejected = listOf(
            emptyList(),
            listOf("song"),
            listOf("song", valid, "extra"),
            listOf("..", "song", valid),
            listOf("cover", valid),
            listOf("song", ""),
            listOf("song", ".."),
            listOf("song", "../../etc/passwd"),
            listOf("song", "/data/data/x"),
            listOf("song", "a/b"),
            listOf("song", "$valid="),
            listOf("song", "ab+c"),
            listOf("song", "a"),
            listOf("song", "IA"),
            listOf("song", "x".repeat(5000)),
            listOf("playlist", ""),
            listOf("playlist", "-1"),
            listOf("playlist", "+1"),
            listOf("playlist", "1.5"),
            listOf("playlist", "abc"),
            listOf("playlist", "99999999999999999999")
        )

        for (segments in rejected) {
            assertNull("Expected $segments to be rejected", ArtworkPaths.parse(segments))
        }
    }

    @Test
    fun nonCanonicalEncodingsAreRejected() {
        // "QQ" is canonical for "A"; "QR" decodes to the same byte with
        // non-zero trailing bits and must not be accepted as an alias.
        assertEquals("A", ArtworkPaths.decodeSongId("QQ"))
        assertNull(ArtworkPaths.decodeSongId("QR"))
        assertNull(ArtworkPaths.decodeSongId("QQ=="))
    }

    @Test
    fun blankDecodedIdsAreRejected() {
        assertNull(ArtworkPaths.decodeSongId(ArtworkPaths.encodeSongId(" ")))
    }

    @Test
    fun cacheNamesAreStableHexAndDistinct() {
        val first = ArtworkPaths.sha256Hex(songIds[0])

        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(first, ArtworkPaths.sha256Hex(songIds[0]))
        assertNotEquals(first, ArtworkPaths.sha256Hex(songIds[1]))

        // Joined parts cannot collide by shifting text between them.
        assertNotEquals(
            ArtworkPaths.sha256Hex("tile", "ab", "c"),
            ArtworkPaths.sha256Hex("tile", "a", "bc")
        )
    }
}
