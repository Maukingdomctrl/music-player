package com.maukingdom.musicplayer.playback

import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import java.text.Normalizer

/** What a voice query ("play X on MyCar Music") matched. */
sealed interface VoiceMatch {

    /** The query was empty after normalising. */
    data object BlankQuery : VoiceMatch

    data object NoMatch : VoiceMatch

    /** Levels 1 and 2: queue the whole playlist. */
    data class Playlist(val playlistId: Long, val exact: Boolean) : VoiceMatch

    /** Levels 3 and 4: play this song first. */
    data class Song(val songId: String, val exact: Boolean) : VoiceMatch

    /** Level 5: every song whose artist matches, A–Z by title. */
    data class Artist(val songIds: List<String>) : VoiceMatch

    /** Level 6: every song whose album matches, A–Z by title. */
    data class Album(val songIds: List<String>) : VoiceMatch
}

/** Results for Android Auto's on-screen search: playlists, then songs. */
data class SearchResults(
    val playlists: List<PlaylistEntity>,
    val songs: List<SongEntity>
) {
    val size: Int get() = playlists.size + songs.size
}

/**
 * Matches spoken or typed queries against the library. Pure Kotlin, so it
 * can be unit-tested on the JVM.
 *
 * Text is compared after [normalize]: case-insensitive, trimmed, with
 * punctuation treated as spaces and Latin accents removed, so "Dil-Se Re!"
 * matches "dil se re". Devanagari text is kept intact.
 */
object VoiceSearch {

    const val MAX_SEARCH_RESULTS = 50

    const val EMPTY_LIBRARY_MESSAGE = "Your MyCar Music library is empty."

    /**
     * What to tell the user when nothing could be played for [query]. A
     * blank query only finds nothing when the library is empty.
     */
    fun noMatchMessage(query: String): String {
        if (normalize(query).isEmpty()) return EMPTY_LIBRARY_MESSAGE

        val shown = query.trim().take(80)
        return "Couldn't find \"$shown\" in MyCar Music."
    }

    private val APOSTROPHES = setOf('\'', '’', '‘', '`')

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var previousWasLatin = false

        for (ch in decomposed) {
            val type = Character.getType(ch)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()

            when {
                // Accents on Latin letters (é -> e). Marks on other
                // scripts, such as Devanagari vowel signs, are kept.
                isMark && previousWasLatin -> Unit

                ch in APOSTROPHES -> Unit

                Character.isLetterOrDigit(ch) || isMark -> {
                    out.append(ch.lowercaseChar())
                    previousWasLatin = ch.lowercaseChar() in 'a'..'z'
                }

                else -> {
                    out.append(' ')
                    previousWasLatin = false
                }
            }
        }

        return Normalizer.normalize(out, Normalizer.Form.NFC)
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /**
     * Applies the match levels in order and returns the first that matches:
     * 1 playlist name exact, 2 playlist name contains, 3 song title exact,
     * 4 song title contains, 5 artist contains, 6 album contains.
     *
     * Ties: exact playlist matches prefer folder-linked playlists, then A–Z;
     * "contains" matches prefer the shortest name or title (the closest
     * match), then folder-linked, then A–Z.
     */
    fun match(
        query: String,
        playlists: List<PlaylistEntity>,
        folderLinkedIds: Set<Long>,
        songs: List<SongEntity>
    ): VoiceMatch {
        val q = normalize(query)
        if (q.isEmpty()) return VoiceMatch.BlankQuery

        val namedPlaylists = playlists.map { it to normalize(it.name) }

        namedPlaylists
            .filter { (_, name) -> name == q }
            .map { it.first }
            .let { BrowseTree.orderPlaylists(it, folderLinkedIds) }
            .firstOrNull()
            ?.let { return VoiceMatch.Playlist(it.id, exact = true) }

        namedPlaylists
            .filter { (_, name) -> name.contains(q) }
            .sortedWith(
                compareBy<Pair<PlaylistEntity, String>> { it.second.length }
                    .thenBy { it.first.id !in folderLinkedIds }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name }
                    .thenBy { it.first.id }
            )
            .firstOrNull()
            ?.let { return VoiceMatch.Playlist(it.first.id, exact = false) }

        val titled = songs.map { it to normalize(it.title) }

        titled
            .filter { (_, title) -> title == q }
            .map { it.first }
            .sortedWith(songOrder)
            .firstOrNull()
            ?.let { return VoiceMatch.Song(it.id, exact = true) }

        titled
            .filter { (_, title) -> title.contains(q) }
            .sortedWith(
                compareBy<Pair<SongEntity, String>> { it.second.length }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
                    .thenBy { it.first.id }
            )
            .firstOrNull()
            ?.let { return VoiceMatch.Song(it.first.id, exact = false) }

        songs
            .filter { normalize(it.artist).contains(q) }
            .sortedWith(songOrder)
            .takeIf { it.isNotEmpty() }
            ?.let { return VoiceMatch.Artist(it.map(SongEntity::id)) }

        songs
            .filter { normalize(it.album).contains(q) }
            .sortedWith(songOrder)
            .takeIf { it.isNotEmpty() }
            ?.let { return VoiceMatch.Album(it.map(SongEntity::id)) }

        return VoiceMatch.NoMatch
    }

    /**
     * On-screen search: playlists whose name contains the query (exact
     * first), then songs whose title, artist or album contains it (title
     * matches first). At most [limit] results in total; blank gives none.
     */
    fun searchResults(
        query: String,
        playlists: List<PlaylistEntity>,
        folderLinkedIds: Set<Long>,
        songs: List<SongEntity>,
        limit: Int = MAX_SEARCH_RESULTS
    ): SearchResults {
        val q = normalize(query)
        if (q.isEmpty() || limit <= 0) return SearchResults(emptyList(), emptyList())

        val matchingPlaylists = playlists
            .map { it to normalize(it.name) }
            .filter { (_, name) -> name.contains(q) }
            .sortedWith(
                compareBy<Pair<PlaylistEntity, String>> { it.second != q }
                    .thenBy { it.second.length }
                    .thenBy { it.first.id !in folderLinkedIds }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name }
                    .thenBy { it.first.id }
            )
            .map { it.first }
            .take(limit)

        val songRank = songs.mapNotNull { song ->
            val title = normalize(song.title)
            val rank = when {
                title == q -> 0
                title.contains(q) -> 1
                normalize(song.artist).contains(q) -> 2
                normalize(song.album).contains(q) -> 3
                else -> return@mapNotNull null
            }
            song to rank
        }

        val matchingSongs = songRank
            .sortedWith(
                compareBy<Pair<SongEntity, Int>> { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
                    .thenBy { it.first.id }
            )
            .map { it.first }
            .take(limit - matchingPlaylists.size)

        return SearchResults(matchingPlaylists, matchingSongs)
    }

    private val songOrder: Comparator<SongEntity> =
        compareBy<SongEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.id }
}
