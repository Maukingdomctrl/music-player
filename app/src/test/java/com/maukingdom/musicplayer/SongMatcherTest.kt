package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMatcher
import com.maukingdom.musicplayer.playback.ArtworkPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatcherTest {

    private fun song(id: String, uri: String = id) = SongEntity(
        id = id,
        uri = uri,
        title = id,
        artist = "Artist",
        album = "Album",
        genre = null,
        year = null,
        durationMs = 1_000L,
        artworkUri = null,
        mimeType = "audio/mpeg",
        dateAddedMs = 1L
    )

    /** SAF document URIs map to MediaStore URIs; MediaStore URIs map to themselves. */
    private val mediaStore = mapOf(
        "content://docs/sd/a.mp3" to "content://media/external/audio/media/1",
        "content://docs/sd/b.mp3" to "content://media/external/audio/media/2",
        "content://docs/tree/x/a.mp3" to "content://media/external/audio/media/1",
        "content://docs/tree/x/new.mp3" to "content://media/external/audio/media/9"
    )

    private class CountingLookup(
        private val table: Map<String, String>,
        /** When true, every unknown file gets its own distinct MediaStore URI. */
        private val everyFileIndexed: Boolean = false
    ) : (String) -> String? {
        val calls = mutableMapOf<String, Int>()

        override fun invoke(uri: String): String? {
            calls[uri] = (calls[uri] ?: 0) + 1
            return when {
                uri.startsWith("content://media/") -> uri
                uri in table -> table[uri]
                everyFileIndexed -> "content://media/external/audio/media/x${uri.hashCode()}"
                else -> null
            }
        }
    }

    /** The old per-file logic, for comparison. */
    private fun naiveMatch(incoming: String, known: List<SongEntity>, lookup: (String) -> String?): SongEntity? {
        val exact = known.filter { it.id == incoming || it.uri == incoming }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        val media = lookup(incoming) ?: return null
        return known.filter { lookup(it.uri) == media }.singleOrNull()
    }

    @Test
    fun exactIdOrUriMatchWins() {
        val known = listOf(song("content://docs/sd/a.mp3"), song("id-2", uri = "content://docs/sd/b.mp3"))
        val matcher = SongMatcher(known, CountingLookup(mediaStore))

        assertSame(known[0], matcher.match("content://docs/sd/a.mp3"))
        assertSame(known[1], matcher.match("content://docs/sd/b.mp3"))
    }

    @Test
    fun severalExactMatchesGiveNoMatch() {
        val known = listOf(song("dup-1", uri = "content://docs/sd/a.mp3"), song("dup-2", uri = "content://docs/sd/a.mp3"))
        assertNull(SongMatcher(known, CountingLookup(mediaStore)).match("content://docs/sd/a.mp3"))
    }

    @Test
    fun mediaStoreEquivalentMatchesAndAmbiguityGivesNone() {
        val known = listOf(song("content://docs/sd/a.mp3"), song("content://docs/sd/b.mp3"))
        val matcher = SongMatcher(known, CountingLookup(mediaStore))

        // Same file through a different tree URI.
        assertSame(known[0], matcher.match("content://docs/tree/x/a.mp3"))
        // MediaStore URI of a known SAF song.
        assertSame(known[1], matcher.match("content://media/external/audio/media/2"))
        // Unknown file.
        assertNull(matcher.match("content://docs/tree/x/new.mp3"))
        // No MediaStore equivalent at all.
        assertNull(matcher.match("content://other/zzz"))

        val ambiguous = listOf(song("content://docs/sd/a.mp3"), song("content://docs/tree/x/a.mp3"))
        assertNull(SongMatcher(ambiguous, CountingLookup(mediaStore)).match("content://media/external/audio/media/1"))
    }

    @Test
    fun eachUriIsLookedUpAtMostOncePerRun() {
        val known = (1..500).map { song("content://docs/sd/song$it.mp3") }
        // Every file has a MediaStore URI, so every known song is compared.
        val lookup = CountingLookup(mediaStore, everyFileIndexed = true)
        val matcher = SongMatcher(known, lookup)

        val incoming = (1..200).map { "content://docs/tree/new/file$it.mp3" }
        for (uri in incoming) {
            assertNull(matcher.match(uri))
        }

        assertTrue(lookup.calls.values.all { it == 1 })
        // 500 known + 200 incoming, instead of 200 × 501 with the old loop.
        assertEquals(700, lookup.calls.size)
    }

    @Test
    fun sameResultsAsTheOldLogicIncludingSongsRememberedDuringTheRun() {
        val known = listOf(song("content://docs/sd/a.mp3"), song("content://docs/sd/b.mp3"))
        val lookup = CountingLookup(mediaStore)
        val matcher = SongMatcher(known, lookup)
        val naiveKnown = known.toMutableList()

        val incoming = listOf(
            "content://docs/tree/x/a.mp3",
            "content://docs/tree/x/new.mp3",
            "content://docs/tree/x/new.mp3", // second time: matches the song added below
            "content://media/external/audio/media/2",
            "content://other/zzz"
        )

        for (uri in incoming) {
            val expected = naiveMatch(uri, naiveKnown, lookup)
            assertEquals(uri, expected?.id, matcher.match(uri)?.id)

            if (expected == null) {
                val added = song(uri)
                matcher.remember(added)
                naiveKnown += added
            }
        }
    }

    @Test
    fun noArtworkMarkerOnlyWhenTheFileOpened() {
        assertTrue(ArtworkPaths.rememberNoArtwork(fileOpened = true))
        assertFalse(ArtworkPaths.rememberNoArtwork(fileOpened = false))
    }
}
