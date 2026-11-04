package com.maukingdom.musicplayer.ui.v2.cover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maukingdom.musicplayer.ui.v2.LocalReduceMotion

/**
 * One animation clock for every living cover on a screen. It ticks (via
 * withFrameNanos) only while at least one cover is waiting to move and the
 * screen may animate; covers read it while drawing, so a frame redraws
 * them without recomposing anything.
 *
 * It also hands out the screen's [MAX_ANIMATED_COVERS] animation slots, in
 * the order covers asked; the rest show their static frame.
 */
@Stable
class CoverClock internal constructor() {
    private val frameNanos = mutableLongStateOf(0L)
    private var firstNanos = -1L

    internal val waiting = mutableStateListOf<Long>()
    private var nextToken = 0L

    internal fun newToken(): Long = nextToken++

    internal fun tick(nanos: Long) {
        if (firstNanos < 0L) firstNanos = nanos
        frameNanos.longValue = nanos
    }

    /** Seconds since the clock first ran. A snapshot read: call it only while drawing. */
    fun seconds(): Float =
        if (firstNanos < 0L) 0f else (frameNanos.longValue - firstNanos) / 1_000_000_000f
}

/** What every cover on the screen needs to decide whether it may move. */
@Immutable
data class CoverEnvironment(val appResumed: Boolean, val animationsOff: Boolean)

val LocalCoverClock = staticCompositionLocalOf<CoverClock?> { null }
val LocalCoverEnvironment = compositionLocalOf { CoverEnvironment(appResumed = false, animationsOff = true) }

/** True below something that covers the screen (Now Playing, an open playlist). */
val LocalCoversCovered = compositionLocalOf { false }

/** Songs to try per playlist (see MusicViewModel.playlistCoverCandidates). */
val LocalCoverCandidates = compositionLocalOf<Map<Long, List<String>>> { emptyMap() }

/**
 * Provides the screen's [CoverClock] and [candidates] to [content]. The
 * clock stops while [covered] (Now Playing open), the app is not resumed,
 * or "Remove animations" is on.
 */
@Composable
fun ProvideLivingCovers(
    candidates: Map<Long, List<String>>,
    covered: Boolean,
    content: @Composable () -> Unit
) {
    val clock = remember { CoverClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val animationsOff = LocalReduceMotion.current
    val anyWaiting by remember { derivedStateOf { clock.waiting.isNotEmpty() } }

    val run = resumed && !animationsOff && !covered && anyWaiting
    LaunchedEffect(run) {
        if (run) {
            while (true) withFrameNanos(clock::tick)
        }
    }

    val environment = remember(resumed, animationsOff) { CoverEnvironment(resumed, animationsOff) }
    CompositionLocalProvider(
        LocalCoverClock provides clock,
        LocalCoverEnvironment provides environment,
        LocalCoversCovered provides covered,
        LocalCoverCandidates provides candidates,
        content = content
    )
}

/** Marks [content] as covered (for example a tab under an open playlist): its covers hold still. */
@Composable
fun CoversCovered(covered: Boolean, content: @Composable () -> Unit) {
    val parent = LocalCoversCovered.current
    CompositionLocalProvider(LocalCoversCovered provides (parent || covered), content = content)
}
