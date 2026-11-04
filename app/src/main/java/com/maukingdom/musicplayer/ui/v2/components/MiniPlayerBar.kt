package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the mini player shows. [progress] is 0..1. */
data class MiniPlayerState(
    val songId: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val progress: Float
)

/**
 * The 56 dp mini player above the bottom navigation, with a 40 dp cover
 * and an 8 dp radius.
 *
 * Placeholder: the API is fixed here and the bar is implemented in UI v2
 * Step 2. Until then it draws nothing.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MiniPlayerBar(
    state: MiniPlayerState?,
    onTogglePlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Implemented in Step 2.
}
