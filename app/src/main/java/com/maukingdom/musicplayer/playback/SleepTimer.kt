package com.maukingdom.musicplayer.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the sleep timer is doing. Shared from the service to the UI. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Pauses at [endsAtElapsedMs] (SystemClock.elapsedRealtime). [minutes] is what was chosen. */
    data class Countdown(val minutes: Int, val endsAtElapsedMs: Long) : SleepTimerState

    /** Pauses when the current song ends. */
    data object EndOfSong : SleepTimerState
}

/** Pure sleep-timer rules. JVM-tested. */
object SleepTimerMath {
    /** The fade before pausing. */
    const val FADE_MS = 10_000L

    /** Custom-command argument: minutes, or one of these. */
    const val ARG_OFF = 0
    const val ARG_END_OF_SONG = -1

    val choices = listOf(15, 30, 45, 60)

    /** "23:41 left" (minutes keep counting: "60:00 left"), rounding up so a running timer never shows 0:00. */
    fun formatRemaining(remainingMs: Long): String {
        val total = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
        return String.format(Locale.ROOT, "%d:%02d left", total / 60L, total % 60L)
    }

    /**
     * Volume multiplier with [remainingMs] to go: 1 before the fade, then a
     * gentle quadratic ease down to 0 over [fadeMs].
     */
    fun fadeVolume(remainingMs: Long, fadeMs: Long = FADE_MS): Float {
        if (fadeMs <= 0L) return if (remainingMs > 0L) 1f else 0f
        val t = (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
        return t * t
    }

    /** Time left in the current song, or null while its length is unknown. */
    fun songRemaining(durationMs: Long, positionMs: Long): Long? =
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) null else (durationMs - positionMs).coerceAtLeast(0L)

    /** True when a media item transition means "the song finished by itself". */
    fun songFinished(transitionReason: Int): Boolean =
        transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

    /** How long to wait before checking again: short during the fade, else up to 1 s. */
    fun nextCheckDelay(remainingMs: Long, fadeMs: Long = FADE_MS): Long = when {
        remainingMs <= fadeMs -> 100L
        else -> (remainingMs - fadeMs).coerceIn(100L, 1_000L)
    }

    fun stateFor(arg: Int, nowElapsedMs: Long): SleepTimerState = when {
        arg == ARG_END_OF_SONG -> SleepTimerState.EndOfSong
        arg > 0 -> SleepTimerState.Countdown(arg, nowElapsedMs + arg * 60_000L)
        else -> SleepTimerState.Off
    }
}

/**
 * Runs the sleep timer on [player] inside the service, so it survives the
 * UI closing. Fades the player's own volume over the last 10 s, pauses,
 * then restores the volume. Cancels when the user pauses or the queue is
 * cleared. Main thread only.
 */
internal class SleepTimerController(
    private val scope: CoroutineScope,
    private val player: Player,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val mutableState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = mutableState.asStateFlow()

    private var job: Job? = null
    /** The volume before the fade started, or null when not fading. */
    private var volumeBeforeFade: Float? = null

    val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) cancel()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mutableState.value == SleepTimerState.EndOfSong && SleepTimerMath.songFinished(reason)) {
                finish()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The last song ended by itself: playback has stopped anyway.
            if (playbackState == Player.STATE_ENDED) cancel()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (player.mediaItemCount == 0) cancel()
        }
    }

    /** [arg]: minutes, [SleepTimerMath.ARG_END_OF_SONG], or [SleepTimerMath.ARG_OFF]. */
    fun set(arg: Int) {
        stop()
        val next = SleepTimerMath.stateFor(arg, now())
        mutableState.value = next
        if (next == SleepTimerState.Off) return

        job = scope.launch {
            while (isActive) {
                val remaining = remainingMs()
                if (remaining == null) {
                    // End of song while its length is still unknown.
                    delay(1_000L)
                    continue
                }
                if (remaining <= 0L && next is SleepTimerState.Countdown) {
                    finish()
                    return@launch
                }
                applyFade(remaining)
                delay(SleepTimerMath.nextCheckDelay(remaining))
            }
        }
    }

    fun cancel() {
        if (mutableState.value == SleepTimerState.Off && job == null) return
        stop()
        mutableState.value = SleepTimerState.Off
    }

    fun release() {
        stop()
        mutableState.value = SleepTimerState.Off
    }

    private fun remainingMs(): Long? = when (val current = mutableState.value) {
        is SleepTimerState.Countdown -> current.endsAtElapsedMs - now()
        SleepTimerState.EndOfSong -> SleepTimerMath.songRemaining(player.duration, player.currentPosition)
        SleepTimerState.Off -> null
    }

    private fun applyFade(remainingMs: Long) {
        if (remainingMs > SleepTimerMath.FADE_MS) {
            restoreVolume()
            return
        }
        val base = volumeBeforeFade ?: player.volume.also { volumeBeforeFade = it }
        player.volume = base * SleepTimerMath.fadeVolume(remainingMs)
    }

    /** Pause, then restore the volume. State goes Off first, so our pause is not taken as the user's. */
    private fun finish() {
        val restore = volumeBeforeFade
        job?.cancel()
        job = null
        mutableState.value = SleepTimerState.Off
        player.pause()
        volumeBeforeFade = null
        player.volume = restore ?: player.volume.takeIf { it > 0f } ?: 1f
    }

    private fun stop() {
        job?.cancel()
        job = null
        restoreVolume()
    }

    private fun restoreVolume() {
        volumeBeforeFade?.let { player.volume = it }
        volumeBeforeFade = null
    }
}
