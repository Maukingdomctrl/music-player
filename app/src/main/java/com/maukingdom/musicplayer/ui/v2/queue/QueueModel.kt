package com.maukingdom.musicplayer.ui.v2.queue

import androidx.compose.runtime.Immutable
import java.util.Locale

/** Pure rules for the Up next queue and Continue listening. JVM-tested. */

/**
 * Where the current queue came from, as this app set it: [playlistId]
 * (null = the whole library), whether the user has [edited] it since, and
 * the [fingerprint] of the queue's song ids at that moment. Saved, so a
 * restored queue keeps its caption; a queue changed elsewhere (Android
 * Auto) no longer matches the fingerprint and counts as the library.
 */
data class QueueOrigin(
    val playlistId: Long?,
    val edited: Boolean,
    val fingerprint: Int
)

/** What the caption and Home show for the player's queue right now. */
data class ResolvedOrigin(val playlistId: Long?, val edited: Boolean) {
    companion object {
        val Library = ResolvedOrigin(playlistId = null, edited = false)
    }
}

/** Order-sensitive fingerprint of a queue's song ids. */
fun queueFingerprint(songIds: List<String>): Int = 31 * songIds.hashCode() + songIds.size

/** [origin] when it still describes the queue with [songIds]; else the library. */
fun resolveOrigin(origin: QueueOrigin?, songIds: List<String>): ResolvedOrigin {
    if (origin == null || songIds.isEmpty() || origin.fingerprint != queueFingerprint(songIds)) {
        return ResolvedOrigin.Library
    }
    return if (origin.edited) {
        ResolvedOrigin(playlistId = null, edited = true)
    } else {
        ResolvedOrigin(playlistId = origin.playlistId, edited = false)
    }
}

/** The Now Playing caption: eyebrow and title. */
fun playingFromCaption(playlistName: String?, edited: Boolean): Pair<String, String> = when {
    edited -> "PLAYING FROM" to "QUEUE"
    playlistName != null -> "PLAYING FROM PLAYLIST" to playlistName
    else -> "PLAYING FROM" to "YOUR LIBRARY"
}

// Queue edits, mirrored on the id list so the expected result can be fingerprinted.

/** Where "Play next" inserts: after the current song (0 for an empty queue). */
fun playNextIndex(currentIndex: Int, queueSize: Int): Int =
    if (queueSize == 0) 0 else (currentIndex + 1).coerceIn(0, queueSize)

fun insertAt(ids: List<String>, index: Int, inserted: List<String>): List<String> =
    ids.toMutableList().apply { addAll(index.coerceIn(0, size), inserted) }

fun moveItem(ids: List<String>, from: Int, to: Int): List<String> {
    if (from !in ids.indices || to !in ids.indices || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

fun removeAt(ids: List<String>, index: Int): List<String> =
    if (index in ids.indices) ids.toMutableList().apply { removeAt(index) } else ids

/** Only the song at [currentIndex] is left ("Clear upcoming"). */
fun keepOnly(ids: List<String>, currentIndex: Int): List<String> =
    ids.getOrNull(currentIndex)?.let(::listOf) ?: ids

/** Undo for Play next / Add to queue: what went in where, and the caption before. */
data class QueueUndo(
    val index: Int,
    val songIds: List<String>,
    val previousOrigin: QueueOrigin?
)

/** True while the inserted songs are still exactly where they went in. */
fun canUndoInsert(currentIds: List<String>, undo: QueueUndo): Boolean {
    val end = undo.index + undo.songIds.size
    return undo.songIds.isNotEmpty() && undo.index >= 0 && end <= currentIds.size &&
        currentIds.subList(undo.index, end) == undo.songIds
}

fun queueAddMessage(playNext: Boolean, count: Int): String = when {
    playNext && count == 1 -> "Playing next"
    playNext -> "$count songs playing next"
    count == 1 -> "Added to queue"
    else -> "Added $count songs to queue"
}

/**
 * Item indices after [currentIndex] in play order (shuffle-aware, no
 * repeat wrap). [nextOf] is the timeline's next index, or null at the end.
 */
fun upcomingIndices(currentIndex: Int, queueSize: Int, nextOf: (Int) -> Int?): List<Int> {
    if (currentIndex !in 0 until queueSize) return emptyList()
    val result = ArrayList<Int>()
    val seen = HashSet<Int>().apply { add(currentIndex) }
    var index = nextOf(currentIndex)
    while (index != null && index in 0 until queueSize && seen.add(index)) {
        result += index
        index = nextOf(index)
    }
    return result
}

/** Stable-enough list keys: the song id plus which copy of it this is. */
fun queueKeys(songIds: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return songIds.map { id ->
        val n = seen.getOrDefault(id, 0)
        seen[id] = n + 1
        "$id#$n"
    }
}

/** "From Hindi 90s · 2:14 left"; the time part only once the duration is known. */
fun continueSubtitle(playlistName: String?, edited: Boolean, positionMs: Long, durationMs: Long): String {
    val from = when {
        edited -> "From your queue"
        playlistName != null -> "From $playlistName"
        else -> "From your library"
    }
    if (durationMs <= 0L) return from
    val left = (durationMs - positionMs).coerceAtLeast(0L)
    return "$from · ${formatShortClock(left)} left"
}

/** "2:14" (no leading zero on minutes), "1:02:03" past an hour. */
fun formatShortClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000L
    val hours = total / 3_600L
    val minutes = (total % 3_600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** One queue row. [index] is the player's item index. */
@Immutable
data class QueueEntry(
    val index: Int,
    val key: String,
    val songId: String,
    val title: String,
    val artist: String
)

/** The queue for the Up next sheet: [upcoming] are entries in play order. */
@Immutable
data class QueueUi(
    val current: QueueEntry? = null,
    val upcoming: List<QueueEntry> = emptyList(),
    val shuffle: Boolean = false
) {
    /** Reordering is in item order, which equals play order only without shuffle. */
    val canReorder: Boolean get() = !shuffle
}
