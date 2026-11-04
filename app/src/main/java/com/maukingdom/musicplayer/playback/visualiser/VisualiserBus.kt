package com.maukingdom.musicplayer.playback.visualiser

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local link between PlaybackService's audio tap and the Modern
 * Studio player (both run in the app's process, so no IPC). The UI says
 * when it wants levels; only then does the tap analyse audio.
 */
object VisualiserBus {

    private val requesters = AtomicInteger(0)
    private val mutableWanted = MutableStateFlow(false)

    /** True while at least one visible, resumed Studio player wants bars. */
    val wanted: StateFlow<Boolean> = mutableWanted.asStateFlow()

    /** Read on the audio thread for every buffer: a plain volatile, no flow. */
    @Volatile
    internal var analysing: Boolean = false
        private set

    private val mutableLevels = MutableStateFlow(FloatArray(VISUALISER_BANDS))

    /** Smoothed band levels 0..1, [VISUALISER_BANDS] of them, about 30 times a second while wanted. */
    val levels: StateFlow<FloatArray> = mutableLevels.asStateFlow()

    internal val timeline = VisualiserTimeline()

    /** Call when a Studio player becomes visible (and resumed); pair with [release]. */
    fun request() {
        if (requesters.incrementAndGet() == 1) {
            analysing = true
            mutableWanted.value = true
        }
    }

    fun release() {
        if (requesters.decrementAndGet() <= 0) {
            requesters.set(0)
            analysing = false
            mutableWanted.value = false
        }
    }

    internal fun publish(levels: FloatArray) {
        mutableLevels.value = levels
    }
}
