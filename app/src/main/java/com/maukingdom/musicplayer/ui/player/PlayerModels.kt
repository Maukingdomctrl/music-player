package com.maukingdom.musicplayer.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import com.maukingdom.musicplayer.ui.PlaybackUiState
import java.util.Locale

/**
 * Everything a skin's player needs to draw itself. [trackNumber] is 1-based
 * and 0 when the queue is empty. [volume] is 0..1 of the device's music
 * volume. [playlistName] is null when the queue did not come from a playlist.
 */
data class PlayerUi(
    val title: String,
    val artist: String,
    val album: String,
    val playlistName: String?,
    val trackNumber: Int,
    val trackCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val volume: Float,
    val artwork: ImageBitmap?,
    val connected: Boolean,
    val error: String?,
    /** Bass and treble in whole dB, -6..+6. */
    val bassDb: Int = 0,
    val trebleDb: Int = 0,
    /** False only when the phone refused the equaliser. */
    val toneAvailable: Boolean = true
) {
    val hasQueue: Boolean get() = trackCount > 0
}

/** What a skin's player can ask for. */
interface PlayerActions {
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun setVolume(fraction: Float)
    fun setBassDb(db: Int)
    fun setTrebleDb(db: Int)
    fun clearQueue()
    fun openSkinPicker()
}

fun PlaybackUiState.toPlayerUi(
    artwork: ImageBitmap?,
    playlistName: String?,
    bassDb: Int = 0,
    trebleDb: Int = 0,
    toneAvailable: Boolean = true
): PlayerUi = PlayerUi(
    title = title,
    artist = artist,
    album = album,
    playlistName = playlistName,
    trackNumber = if (queueSize > 0) currentIndex + 1 else 0,
    trackCount = queueSize,
    positionMs = positionMs,
    durationMs = durationMs,
    isPlaying = isPlaying,
    shuffle = shuffleEnabled,
    repeatMode = repeatMode,
    volume = volume,
    artwork = artwork,
    connected = connected,
    error = error,
    bassDb = bassDb,
    trebleDb = trebleDb,
    toneAvailable = toneAvailable
)

/** Total time, or "--:--" while the duration is unknown (not prepared yet). */
internal fun formatTotal(durationMs: Long): String =
    if (durationMs > 0L) formatClock(durationMs) else "--:--"

/** Played fraction 0..1; 0 while the duration is unknown. */
internal fun playedFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** "mm:ss"; minutes keep counting past 59 (for example "125:03"). */
internal fun formatClock(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
