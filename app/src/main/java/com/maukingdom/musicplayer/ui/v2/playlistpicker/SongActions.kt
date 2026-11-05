package com.maukingdom.musicplayer.ui.v2.playlistpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton

/**
 * A song's ⋮ button and its menu. [items] fills the menu; each item gets
 * a `close` to call before acting.
 */
@Composable
fun SongMenuButton(
    songTitle: String,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        V2IconButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "More options for $songTitle",
            onClick = { open = true }
        )
        SongMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}

/** The song menu itself, for callers that open it another way (Home's long-press). */
@Composable
fun SongMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Modern.colors.surfaceHigh) {
        items(onDismiss)
    }
}

@Composable
fun SongMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Modern.colors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = Modern.type.menuItem,
                color = if (enabled) colors.textPrimary else colors.textPrimary.copy(alpha = 0.5f)
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}

/** Play next / Add to queue. Provided by PlaylistPickerHost; a no-op elsewhere. */
@Stable
class SongQueue(
    private val onPlayNext: (List<String>) -> Unit,
    private val onAddToQueue: (List<String>) -> Unit
) {
    fun playNext(songIds: List<String>) {
        if (songIds.isNotEmpty()) onPlayNext(songIds)
    }

    fun addToQueue(songIds: List<String>) {
        if (songIds.isNotEmpty()) onAddToQueue(songIds)
    }
}

val LocalSongQueue = staticCompositionLocalOf { SongQueue({}, {}) }

/** "Play next" and "Add to queue" for one song, closing the menu first. */
@Composable
fun QueueMenuItems(songId: String, close: () -> Unit) {
    val queue = LocalSongQueue.current
    SongMenuItem("Play next") {
        close()
        queue.playNext(listOf(songId))
    }
    SongMenuItem("Add to queue") {
        close()
        queue.addToQueue(listOf(songId))
    }
}

/**
 * Favourites for menus and multi-select. [songIds] is read only where a
 * menu is open, so the set changing never recomposes song lists.
 */
@Stable
class FavouriteActions(
    private val songIds: State<Set<String>>,
    private val onAdd: (List<String>) -> Unit,
    private val onRemove: (List<String>) -> Unit
) {
    fun isFavourite(songId: String): Boolean = songId in songIds.value

    fun add(songIds: List<String>) {
        if (songIds.isNotEmpty()) onAdd(songIds)
    }

    fun remove(songIds: List<String>) {
        if (songIds.isNotEmpty()) onRemove(songIds)
    }
}

val LocalFavourites = staticCompositionLocalOf {
    FavouriteActions(androidx.compose.runtime.mutableStateOf(emptySet()), {}, {})
}

/** "Add to Favourites", or "Remove from Favourites" when it already is one. */
@Composable
fun FavouriteMenuItem(songId: String, close: () -> Unit) {
    val favourites = LocalFavourites.current
    val isFavourite = favourites.isFavourite(songId)
    SongMenuItem(if (isFavourite) "Remove from Favourites" else "Add to Favourites") {
        close()
        if (isFavourite) favourites.remove(listOf(songId)) else favourites.add(listOf(songId))
    }
}

/** "Add to playlist…" for one song, closing the menu first. */
@Composable
fun AddToPlaylistMenuItem(songId: String, close: () -> Unit) {
    val picker = LocalPlaylistPicker.current
    SongMenuItem("Add to playlist…") {
        close()
        picker.add(listOf(songId))
    }
}

/**
 * Multi-select state: the selected song ids in tap order, or null when not
 * selecting. Saved, so selection survives rotation.
 */
@Stable
class SongSelection internal constructor(initial: List<String>?) {
    var selected: List<String>? by mutableStateOf(initial)
        private set

    val active: Boolean get() = selected != null
    val count: Int get() = selected?.size ?: 0

    fun start(songId: String) {
        selected = listOf(songId)
    }

    /** Leaves selection mode when the last song is unselected. */
    fun toggle(songId: String) {
        val current = selected ?: return
        selected = toggleSelection(current, songId).takeIf { it.isNotEmpty() }
    }

    fun selectAll(songIds: List<String>) {
        selected = songIds
    }

    /** Drops songs that are no longer listed. */
    fun prune(listed: Collection<String>) {
        val current = selected ?: return
        val kept = pruneSelection(current, listed)
        if (kept.size != current.size) selected = kept.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        selected = null
    }

    internal companion object {
        val Saver = Saver<SongSelection, ArrayList<String>>(
            save = { state -> state.selected?.let { ArrayList(it) } },
            restore = { SongSelection(it.toList()) }
        )
    }
}

@Composable
fun rememberSongSelection(): SongSelection =
    rememberSaveable(saver = SongSelection.Saver) { SongSelection(null) }

/**
 * The top bar while selecting: Close, "N selected", Select all, then the
 * caller's [actions]. 56 dp with a hairline under it.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = Modern.colors

    Box(modifier = modifier.background(colors.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            V2IconButton(icon = Icons.Outlined.Close, contentDescription = "Exit selection", onClick = onClose)

            Text(
                text = selectionTitle(count),
                style = Modern.type.playlistTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            V2IconButton(
                icon = Icons.Outlined.SelectAll,
                contentDescription = "Select all",
                onClick = onSelectAll,
                enabled = !allSelected
            )

            actions()
        }

        HorizontalDivider(
            thickness = ModernDimens.line,
            color = colors.divider,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** A row's TalkBack state while selecting ([selected] is null when not selecting). */
fun selectionSemantics(selected: Boolean?): Modifier =
    if (selected == null) {
        Modifier
    } else {
        Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" }
    }

/** The row's tap label: [normal] when not selecting, else Select / Unselect. */
fun selectionClickLabel(selected: Boolean?, normal: String): String = when (selected) {
    null -> normal
    true -> "Unselect"
    false -> "Select"
}

/** The check shown at a row's end while selecting. Decorative: the row states it. */
@Composable
fun SelectionCheck(selected: Boolean) {
    val colors = Modern.colors
    Box(modifier = Modifier.size(ModernDimens.touchEffective), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.textPrimary.copy(alpha = 0.5f),
            modifier = Modifier.size(ModernDimens.icon)
        )
    }
}
