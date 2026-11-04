package com.maukingdom.musicplayer.ui.v2

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** True when the system "remove animations" setting is on. Provided by [ModernTheme]. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Durations used across Modern. */
object ModernMotion {
    const val COLOR_MS = 300
    const val CROSSFADE_MS = 150
    const val PRESS_MS = 100
    const val TEXT_SWAP_MS = 220
    const val PLAYLIST_ENTER_MS = 200
    const val PLAYLIST_EXIT_MS = 150
    const val STUDIO_TINT_MS = 400

    /** Sheet: no bounce, settles in about 350 ms. */
    const val SHEET_STIFFNESS = Spring.StiffnessMediumLow
    const val PRESS_SCALE = 0.97f
}

/** Reads the animator scale now and again on every resume (the user may change it). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var off by remember { mutableStateOf(readAnimationsOff(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { off = readAnimationsOff(context) }
    return off
}

private fun readAnimationsOff(context: Context): Boolean = animationsOff(
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
)

/** A tween, or an instant change when animations are off. */
@Composable
fun <T> modernTween(durationMs: Int): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMs)

/** The sheet spring, or an instant change when animations are off. */
fun <T> sheetSpec(reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = ModernMotion.SHEET_STIFFNESS)
