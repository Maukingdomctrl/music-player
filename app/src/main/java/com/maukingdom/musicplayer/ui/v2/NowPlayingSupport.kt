package com.maukingdom.musicplayer.ui.v2

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.player.PlayerActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The [PlayerActions] for the Modern Now Playing sheet; same calls as Classic. */
@Composable
fun rememberModernPlayerActions(
    musicViewModel: MusicViewModel,
    onOpenSkinPicker: () -> Unit
): PlayerActions {
    val latestOpenSkinPicker by rememberUpdatedState(onOpenSkinPicker)
    return remember(musicViewModel) { modernPlayerActions(musicViewModel) { latestOpenSkinPicker() } }
}

private fun modernPlayerActions(
    musicViewModel: MusicViewModel,
    onOpenSkinPicker: () -> Unit
): PlayerActions = object : PlayerActions {
    override fun playPause() = musicViewModel.togglePlayPause()
    override fun next() = musicViewModel.next()
    override fun previous() = musicViewModel.previous()
    override fun seekTo(positionMs: Long) = musicViewModel.seekTo(positionMs)
    override fun toggleShuffle() = musicViewModel.toggleShuffle()
    override fun cycleRepeat() = musicViewModel.cycleRepeat()
    override fun setVolume(fraction: Float) = musicViewModel.setVolume(fraction)
    override fun setBassDb(db: Int) = musicViewModel.setBassDb(db)
    override fun setTrebleDb(db: Int) = musicViewModel.setTrebleDb(db)
    override fun clearQueue() = musicViewModel.clearQueue()
    override fun openSkinPicker() = onOpenSkinPicker()
}

/**
 * The mini player tint for [artwork] (see [miniPlayerTint]), or null with
 * no cover. Worked out once per cover on a background thread from an 8×8
 * downscale; nothing runs per frame.
 */
@Composable
fun rememberCoverTint(artwork: ImageBitmap?): Color? {
    val tint = produceState<Color?>(initialValue = null, artwork) {
        value = if (artwork == null) {
            null
        } else {
            withContext(Dispatchers.Default) { coverTint(artwork) }
        }
    }
    return tint.value
}

private fun coverTint(artwork: ImageBitmap): Color? = try {
    var source = artwork.asAndroidBitmap()
    // Hardware bitmaps cannot be scaled on the CPU; copy first.
    val copied = source.config == Bitmap.Config.HARDWARE
    if (copied) source = source.copy(Bitmap.Config.ARGB_8888, false)
    val small = Bitmap.createScaledBitmap(source, 8, 8, true)
    val pixels = IntArray(64)
    small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
    if (small !== source) small.recycle()
    if (copied) source.recycle()
    averageColor(pixels)?.let { Color(miniPlayerTint(it)) }
} catch (error: RuntimeException) {
    null
}
