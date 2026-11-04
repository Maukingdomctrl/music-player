package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.LocalReduceMotion
import com.maukingdom.musicplayer.ui.v2.MINI_SUBTITLE_ALPHA
import com.maukingdom.musicplayer.ui.v2.MiniSwipe
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.modernTween
import com.maukingdom.musicplayer.ui.v2.resolveMiniSwipe
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * What the mini player shows. The play position is not part of it: it
 * changes every 500 ms and is read through MiniPlayerBar's progress provider
 * at draw time instead, so position ticks never recompose the bar.
 */
data class MiniPlayerState(
    val songId: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean
)

/** Side margin of the bar, so it floats inside the screen edges. The caller applies it. */
val MiniPlayerSideMargin = 8.dp

/** A drag must travel this far (or be flung, see below) to count. */
private val SwipeDistance = 56.dp
private val SwipeVelocity = 700.dp // per second

/** How far the bar follows an upward drag before it opens. */
private val MaxLift = 12.dp

/**
 * The 56 dp mini player above the bottom navigation: 40 dp cover, title and
 * artist, play/pause, and a 2 dp progress line on the bottom edge
 * ([progressProvider], 0..1, read only while drawing). Its colour is [tint]
 * (the cover's colour, see rememberCoverTint), else surfaceHigh; changes
 * fade over 300 ms. Width and side margins come from the caller's [modifier].
 *
 * Tap or swipe up: [onOpen]. Swipe left / right: [onNext] / [onPrevious],
 * with the text sliding the same way and a light haptic tick. Play/pause
 * only toggles.
 */
@Composable
fun MiniPlayerBar(
    state: MiniPlayerState,
    progressProvider: () -> Float,
    onTogglePlay: () -> Unit,
    onOpen: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {

    val colors = Modern.colors
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val background = animateColorAsState(
        targetValue = tint ?: colors.surfaceHigh,
        animationSpec = modernTween(ModernMotion.COLOR_MS),
        label = "miniBackground"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) ModernMotion.PRESS_SCALE else 1f,
        animationSpec = modernTween(ModernMotion.PRESS_MS),
        label = "miniPress"
    )

    // Live drag offsets: read only in graphicsLayer, so a drag never recomposes.
    val dragX = remember { mutableFloatStateOf(0f) }
    val dragY = remember { mutableFloatStateOf(0f) }
    // +1: new text comes in from the right (next), -1: from the left (previous).
    var direction by remember { mutableIntStateOf(1) }

    val latestNext by rememberUpdatedState(onNext)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestReduce by rememberUpdatedState(reduceMotion)

    val distancePx = with(density) { SwipeDistance.toPx() }
    val velocityPx = with(density) { SwipeVelocity.toPx() }
    val maxLiftPx = with(density) { MaxLift.toPx() }
    val lineHeightPx = with(density) { 2.dp.toPx() }

    fun settle() {
        if (latestReduce) {
            dragX.floatValue = 0f
            dragY.floatValue = 0f
            return
        }
        scope.launch {
            animate(dragX.floatValue, 0f, animationSpec = spring(stiffness = 700f)) { v, _ ->
                dragX.floatValue = v
            }
        }
        scope.launch {
            animate(dragY.floatValue, 0f, animationSpec = spring(stiffness = 700f)) { v, _ ->
                dragY.floatValue = v
            }
        }
    }

    val playLabel = if (state.isPlaying) "Pause" else "Play"
    val shape = RoundedCornerShape(ModernDimens.miniPlayer)
    Row(
        modifier = modifier
            .height(ModernDimens.miniPlayerHeight)
            // Before graphicsLayer, so drag positions are not moved by the lift.
            .pointerInput(distancePx, velocityPx, maxLiftPx) {
                val tracker = VelocityTracker()
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        total = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        total += amount
                        if (abs(total.x) >= abs(total.y)) {
                            dragX.floatValue = total.x * 0.5f
                            dragY.floatValue = 0f
                        } else {
                            dragX.floatValue = 0f
                            dragY.floatValue = (total.y * 0.35f).coerceIn(-maxLiftPx, 0f)
                        }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        when (resolveMiniSwipe(total.x, total.y, v.x, v.y, distancePx, velocityPx)) {
                            MiniSwipe.NEXT -> {
                                direction = 1
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestNext()
                            }
                            MiniSwipe.PREVIOUS -> {
                                direction = -1
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestPrevious()
                            }
                            MiniSwipe.OPEN -> latestOpen()
                            MiniSwipe.NONE -> Unit
                        }
                        settle()
                    },
                    onDragCancel = { settle() }
                )
            }
            .semantics {
                contentDescription = "Now playing: ${state.title.ifBlank { "Unknown title" }} " +
                    "by ${state.artist.ifBlank { "Unknown artist" }}"
                customActions = listOf(
                    CustomAccessibilityAction(playLabel) { onTogglePlay(); true },
                    CustomAccessibilityAction("Next") { onNext(); true },
                    CustomAccessibilityAction("Previous") { onPrevious(); true },
                    CustomAccessibilityAction("Open player") { onOpen(); true }
                )
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationY = dragY.floatValue
                this.shape = shape
                clip = true
            }
            .drawBehind {
                drawRect(background.value)
                // Progress line: white over white at 25 %, on the bottom edge.
                val top = size.height - lineHeightPx
                drawRect(
                    color = Color.White.copy(alpha = 0.25f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, lineHeightPx)
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(0f, top),
                    size = Size(size.width * progressProvider().coerceIn(0f, 1f), lineHeightPx)
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                onClickLabel = "Open player",
                onClick = onOpen
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            uri = songCoverUri(state.songId),
            size = ModernDimens.miniPlayerCover,
            corner = ModernDimens.coverSmall
        )

        Spacer(modifier = Modifier.width(10.dp))

        AnimatedContent(
            targetState = state,
            contentKey = { it.songId },
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val d = direction
                    (slideInHorizontally(tween(ModernMotion.TEXT_SWAP_MS)) { w -> d * w / 5 } +
                        fadeIn(tween(ModernMotion.TEXT_SWAP_MS))) togetherWith
                        (slideOutHorizontally(tween(ModernMotion.TEXT_SWAP_MS / 2)) { w -> -d * w / 5 } +
                            fadeOut(tween(ModernMotion.TEXT_SWAP_MS / 2)))
                }
            },
            label = "miniText",
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { }
                .graphicsLayer {
                    translationX = dragX.floatValue
                    alpha = 1f - (abs(dragX.floatValue) / (distancePx * 2f)).coerceIn(0f, 0.6f)
                }
        ) { shown ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = shown.title.ifBlank { "Unknown title" },
                    style = Modern.type.miniTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = shown.artist.ifBlank { "Unknown artist" },
                    style = Modern.type.miniSubtitle,
                    color = Color.White.copy(alpha = MINI_SUBTITLE_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        V2IconButton(
            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = playLabel,
            onClick = onTogglePlay,
            tint = Color.White
        )
    }
}
