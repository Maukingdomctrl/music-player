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
    val error: String?
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
    fun clearQueue()
    fun openSkinPicker()
}

fun PlaybackUiState.toPlayerUi(
    artwork: ImageBitmap?,
    playlistName: String?
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
    error = error
)

/** "mm:ss"; minutes keep counting past 59 (for example "125:03"). */
internal fun formatClock(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
