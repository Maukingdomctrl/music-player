package com.maukingdom.musicplayer.ui.v2.queue

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maukingdom.musicplayer.ui.v2.LocalReduceMotion
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.components.CoverImage
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SongMenuButton
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SongMenuItem

/** What the Up next sheet can ask for; indices are the player's item indices. */
interface UpNextActions {
    fun play(index: Int)
    fun move(from: Int, to: Int)
    fun remove(index: Int)
    fun clearUpcoming()
}

/**
 * "Up next": the song playing now, then the upcoming songs in play order.
 * Drag the handle to reorder (not while shuffled), swipe left or use ⋮ to
 * remove, tap to jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(
    queue: QueueUi,
    actions: UpNextActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceHigh,
        contentColor = colors.textPrimary,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = ModernDimens.sheetTop, topEnd = ModernDimens.sheetTop),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textSecondary.copy(alpha = 0.4f)) },
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ModernDimens.screenPadding, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(
                        text = "Up next",
                        style = Modern.type.sectionTitle.copy(fontSize = 17.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp),
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (queue.upcoming.isEmpty()) "Nothing after this song" else upcomingLabel(queue.upcoming.size),
                        style = Modern.type.rowSubtitle,
                        color = colors.textSecondary
                    )
                }
                TextButton(
                    onClick = actions::clearUpcoming,
                    enabled = queue.upcoming.isNotEmpty(),
                    modifier = Modifier.heightIn(min = ModernDimens.touchEffective)
                ) {
                    Text(
                        "Clear upcoming",
                        style = Modern.type.cardTitle,
                        color = if (queue.upcoming.isNotEmpty()) colors.accent else colors.textPrimary.copy(alpha = 0.5f)
                    )
                }
            }

            UpNextList(queue = queue, actions = actions)
        }
    }
}

private fun upcomingLabel(count: Int) = if (count == 1) "1 song" else "$count songs"

@Composable
private fun UpNextList(queue: QueueUi, actions: UpNextActions) {
    val colors = Modern.colors
    val listState = rememberLazyListState()
    val reduceMotion = LocalReduceMotion.current

    // The shown order; reset whenever the player reports a new queue.
    val order = remember(queue.upcoming) { queue.upcoming.toMutableStateList() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStartPos by remember { mutableStateOf(-1) }
    // Read only in graphicsLayer, so dragging never recomposes the rows.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val latestQueue by rememberUpdatedState(queue)

    fun onDrag(delta: Float) {
        val key = draggingKey ?: return
        dragOffset.floatValue += delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val centre = dragged.offset + dragOffset.floatValue + dragged.size / 2f
        val target = visible.firstOrNull { info ->
            info.key != key && info.contentType == ROW && centre >= info.offset && centre < info.offset + info.size
        } ?: return
        val from = order.indexOfFirst { it.key == key }
        val to = order.indexOfFirst { it.key == target.key }
        if (from < 0 || to < 0) return
        order.add(to, order.removeAt(from))
        // Keep the row under the finger while the list re-lays out around it.
        dragOffset.floatValue += dragged.offset - target.offset
    }

    fun endDrag() {
        val key = draggingKey ?: return
        val end = order.indexOfFirst { it.key == key }
        val upcoming = latestQueue.upcoming
        if (dragStartPos in upcoming.indices && end in upcoming.indices && end != dragStartPos) {
            actions.move(upcoming[dragStartPos].index, upcoming[end].index)
        }
        draggingKey = null
        dragStartPos = -1
        dragOffset.floatValue = 0f
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        queue.current?.let { current ->
            item(key = "label-now", contentType = LABEL) { SectionLabel("Now playing") }
            item(key = "now", contentType = NOW) {
                QueueRow(entry = current, nowPlaying = true, onClick = {}, trailing = {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "Now playing", tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(ModernDimens.screenPadding - 4.dp))
                })
            }
        }

        if (order.isNotEmpty()) {
            item(key = "label-next", contentType = LABEL) {
                SectionLabel(if (queue.canReorder) "Next up" else "Next up · shuffled (turn off shuffle to reorder)")
            }
        }

        items(order, key = { it.key }, contentType = { ROW }) { entry ->
            val dragging = entry.key == draggingKey
            val position = order.indexOf(entry)
            UpcomingRow(
                entry = entry,
                canReorder = queue.canReorder,
                canMoveUp = queue.canReorder && position > 0,
                canMoveDown = queue.canReorder && position < order.lastIndex,
                actions = actions,
                onMoveBy = { delta ->
                    val upcoming = latestQueue.upcoming
                    val target = position + delta
                    if (position in upcoming.indices && target in upcoming.indices) {
                        actions.move(upcoming[position].index, upcoming[target].index)
                    }
                },
                handle = Modifier.pointerInput(entry.key, queue.canReorder) {
                    if (!queue.canReorder) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            draggingKey = entry.key
                            dragStartPos = order.indexOfFirst { it.key == entry.key }
                            dragOffset.floatValue = 0f
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            onDrag(amount)
                        },
                        onDragEnd = ::endDrag,
                        onDragCancel = ::endDrag
                    )
                },
                modifier = Modifier
                    .then(
                        if (dragging || reduceMotion) {
                            Modifier
                        } else {
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 600f))
                        }
                    )
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationY = dragOffset.floatValue
                            shadowElevation = 8.dp.toPx()
                        }
                    }
            )
        }

        if (order.isEmpty()) {
            item(key = "empty", contentType = LABEL) {
                Text(
                    text = "Use Play next or Add to queue on any song to line it up here.",
                    style = Modern.type.rowSubtitle,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = ModernDimens.screenPadding, vertical = 16.dp)
                )
            }
        }
    }
}

private const val LABEL = "label"
private const val NOW = "now"
private const val ROW = "row"

@Composable
private fun SectionLabel(text: String) {
    Column {
        HorizontalDivider(thickness = ModernDimens.line, color = Modern.colors.divider)
        Text(
            text = text.uppercase(),
            style = Modern.type.sheetEyebrow,
            color = Modern.colors.textSecondary,
            modifier = Modifier
                .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, top = 16.dp, bottom = 4.dp)
                .semantics { heading() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LazyItemScope.UpcomingRow(
    entry: QueueEntry,
    canReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: UpNextActions,
    onMoveBy: (Int) -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    val latestEntry by rememberUpdatedState(entry)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                actions.remove(latestEntry.index)
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.chip)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = colors.textPrimary)
            }
        }
    ) {
        QueueRow(
            entry = entry,
            nowPlaying = false,
            onClick = { actions.play(entry.index) },
            modifier = Modifier
                .background(colors.surfaceHigh)
                .semantics {
                    customActions = buildList {
                        if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMoveBy(-1); true })
                        if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMoveBy(1); true })
                        add(CustomAccessibilityAction("Remove from queue") { actions.remove(entry.index); true })
                    }
                },
            trailing = {
                SongMenuButton(songTitle = entry.title.ifBlank { "this song" }) { close ->
                    SongMenuItem("Remove from queue") {
                        close()
                        actions.remove(entry.index)
                    }
                }
                if (canReorder) {
                    // Decorative for TalkBack: the row's Move up / Move down actions do the same.
                    Box(
                        modifier = handle.size(ModernDimens.touchEffective),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.DragHandle,
                            contentDescription = null,
                            tint = colors.textPrimary.copy(alpha = 0.5f),
                            modifier = Modifier.size(ModernDimens.icon)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    nowPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    val colors = Modern.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.songRowHeight)
            .then(
                if (nowPlaying) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = "Play now", onClick = onClick)
                }
            )
            .padding(start = ModernDimens.screenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(uri = songCoverUri(entry.songId), size = ModernDimens.songRowCover, corner = ModernDimens.coverSmall)
        Spacer(Modifier.width(ModernDimens.itemGap))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title.ifBlank { "Unknown title" },
                style = Modern.type.rowTitle,
                color = if (nowPlaying) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.artist.ifBlank { "Unknown artist" },
                style = Modern.type.rowSubtitle,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing()
    }
}
