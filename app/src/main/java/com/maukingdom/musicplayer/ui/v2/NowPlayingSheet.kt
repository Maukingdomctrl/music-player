package com.maukingdom.musicplayer.ui.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.ColumnScope
import com.maukingdom.musicplayer.ui.v2.queue.playingFromCaption
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.maukingdom.musicplayer.ui.v2.studio.drawStudioBackground
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton

/**
 * Where the Now Playing sheet is. [position] is 0 when fully open and 1
 * when fully below the screen; [dragFraction] is a live header drag added
 * on top. Only the open/closed flag is saved, so the sheet comes back open
 * after Settings or a configuration change.
 */
@Stable
class NowPlayingSheetState(initiallyOpen: Boolean) {
    val position = Animatable(if (initiallyOpen) 0f else 1f)
    internal val dragFraction = mutableFloatStateOf(0f)

    var isOpen by mutableStateOf(initiallyOpen)
        private set

    /** Fraction off screen right now, 0..1. Read it in draw/layout, not composition. */
    val offset: Float get() = (position.value + dragFraction.floatValue).coerceIn(0f, 1f)

    /** Composed while open or while it is still sliding away. */
    val isVisible: Boolean by derivedStateOf { isOpen || offset < 1f }

    suspend fun open(reduceMotion: Boolean) = settle(open = true, reduceMotion)

    suspend fun close(reduceMotion: Boolean) = settle(open = false, reduceMotion)

    internal suspend fun settle(open: Boolean, reduceMotion: Boolean) {
        isOpen = open
        // Fold any live drag into the animated position first, so nothing jumps.
        position.snapTo(offset)
        dragFraction.floatValue = 0f
        position.animateTo(if (open) 0f else 1f, sheetSpec(reduceMotion))
    }

    companion object {
        val Saver = Saver<NowPlayingSheetState, Boolean>(
            save = { it.isOpen },
            restore = { NowPlayingSheetState(it) }
        )
    }
}

@Composable
fun rememberNowPlayingSheetState(): NowPlayingSheetState =
    rememberSaveable(saver = NowPlayingSheetState.Saver) { NowPlayingSheetState(false) }

/** Release speed that closes the sheet regardless of distance. */
private val CloseFlingVelocity = 900.dp // per second

/**
 * Full-screen Now Playing over the tabs and navigation, drawn edge to edge
 * on the Studio one-hue background ([tintProvider], read while drawing). Slides with [state]; drag the header down
 * (or fling it) to close. [content] is the skin's player; it gets all the
 * space below the header.
 */
@Composable
fun NowPlayingSheet(
    state: NowPlayingSheetState,
    playlistName: String?,
    canClearQueue: Boolean,
    onClose: () -> Unit,
    onSettle: (open: Boolean) -> Unit,
    onChangeSkin: () -> Unit,
    onClearQueue: () -> Unit,
    tintProvider: () -> Color,
    queueEdited: Boolean = false,
    songMenuItems: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val background = Modern.colors.bg
    val heightPx = remember { mutableIntStateOf(1) }
    val flingPx = with(LocalDensity.current) { CloseFlingVelocity.toPx() }

    val dragState = rememberDraggableState { delta ->
        val h = heightPx.intValue.coerceAtLeast(1)
        // Header drags only move the sheet down from open, never above it.
        state.dragFraction.floatValue =
            (state.dragFraction.floatValue + delta / h).coerceIn(-state.position.value, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { heightPx.intValue = it.height }
            .graphicsLayer { translationY = state.offset * size.height }
            // Keep touches from reaching the tabs underneath.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawStudioBackground(tintProvider(), background) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                SheetHeader(
                    playlistName = playlistName,
                    canClearQueue = canClearQueue,
                    onClose = onClose,
                    onChangeSkin = onChangeSkin,
                    onClearQueue = onClearQueue,
                    queueEdited = queueEdited,
                    songMenuItems = songMenuItems,
                    modifier = Modifier.draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val close = shouldCloseSheet(state.offset, velocity, flingPx)
                            onSettle(!close)
                        }
                    )
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    playlistName: String?,
    canClearQueue: Boolean,
    onClose: () -> Unit,
    onChangeSkin: () -> Unit,
    onClearQueue: () -> Unit,
    queueEdited: Boolean,
    songMenuItems: (@Composable ColumnScope.(close: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    var menuOpen by remember { mutableStateOf(false) }
    val (eyebrow, source) = playingFromCaption(playlistName, queueEdited)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        V2IconButton(
            icon = Icons.Outlined.KeyboardArrowDown,
            contentDescription = "Close player",
            onClick = onClose
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // "Playlist" only when it really is one; "Queue" once the user changed it.
                text = eyebrow,
                style = Modern.type.sheetEyebrow,
                color = colors.textSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Text(
                text = source,
                style = Modern.type.sheetTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Box {
            V2IconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More options",
                onClick = { menuOpen = true }
            )

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = colors.surfaceHigh
            ) {
                songMenuItems?.invoke(this) { menuOpen = false }
                DropdownMenuItem(
                    text = { Text("Change skin", style = Modern.type.menuItem, color = colors.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Palette, contentDescription = null, tint = colors.textSecondary)
                    },
                    onClick = {
                        menuOpen = false
                        onChangeSkin()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear queue", style = Modern.type.menuItem, color = colors.textPrimary) },
                    leadingIcon = {
                        Icon(Icons.Outlined.ClearAll, contentDescription = null, tint = colors.textSecondary)
                    },
                    enabled = canClearQueue,
                    onClick = {
                        menuOpen = false
                        onClearQueue()
                    }
                )
            }
        }
    }
}

/** One item of the Now Playing ⋮ menu, for [NowPlayingSheet]'s songMenuItems slot. */
@Composable
fun NowPlayingMenuItem(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = Modern.colors
    DropdownMenuItem(
        text = { Text(label, style = Modern.type.menuItem, color = if (enabled) colors.textPrimary else colors.textPrimary.copy(alpha = 0.5f)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = colors.textSecondary) },
        enabled = enabled,
        onClick = onClick
    )
}
