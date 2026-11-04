package com.maukingdom.musicplayer.ui.v2.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.FolderOperationState
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.inlineFolderFailure
import com.maukingdom.musicplayer.ui.v2.LocalReduceMotion
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.components.CoverImage
import com.maukingdom.musicplayer.ui.v2.components.ListRow
import com.maukingdom.musicplayer.ui.v2.components.SongRowSizes
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton
import com.maukingdom.musicplayer.ui.v2.components.playlistCoverUri
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import com.maukingdom.musicplayer.ui.v2.library.rememberLibraryPickers
import com.maukingdom.musicplayer.ui.v2.modernTween
import kotlinx.coroutines.delay

private val CoverSize = 220.dp
private val PlayButtonSize = 56.dp

/** Wash height as a fraction of the screen, and where its darker middle stop sits (~30 % of the screen). */
private const val WASH_FRACTION = 0.52f
private const val WASH_MID_STOP = 0.30f / WASH_FRACTION

/** Everything the Playlist screen shows. Built by [PlaylistRoute]. */
data class PlaylistUi(
    val id: Long,
    val name: String,
    val isFolder: Boolean,
    /** Null while the songs are still the previous playlist's (loading). */
    val songs: List<SongEntity>?,
    val syncedText: String?,
    val syncing: Boolean,
    val accessLost: Boolean,
    val isCurrentQueue: Boolean,
    val isPlaying: Boolean,
    val shuffleOn: Boolean,
    val currentSongId: String?,
    val busy: Boolean
)

/** What the Playlist screen can ask for. */
interface PlaylistActions {
    fun back()
    fun playOrPause()
    fun shufflePlay()
    fun playSong(index: Int)
    fun sync()
    fun reselectFolder()
    fun rename(name: String)
    fun delete()
    fun moveSong(index: Int, delta: Int)
    fun removeSong(songId: String)
}

/**
 * The Modern Playlist screen for [playlistId]: selects it in [musicViewModel]
 * (so playSongs keeps detecting the queue's source playlist) and shows
 * [PlaylistScreen]. Calls [onBack] when the playlist is deleted.
 */
@Composable
fun PlaylistRoute(
    playlistId: Long,
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(playlistId) { musicViewModel.selectPlaylist(playlistId) }

    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()
    val content by musicViewModel.selectedPlaylistContent.collectAsStateWithLifecycle()
    val folderLink by musicViewModel.selectedFolderLink.collectAsStateWithLifecycle()
    val folderOperation by musicViewModel.folderOperation.collectAsStateWithLifecycle()
    val libraryOperation by musicViewModel.libraryOperation.collectAsStateWithLifecycle()
    val queuedPlaylistId by musicViewModel.queuePlaylistId.collectAsStateWithLifecycle()

    // Only the fields this screen needs, so 500 ms position ticks do not recompose it.
    val playbackState = musicViewModel.playbackState.collectAsStateWithLifecycle()
    val currentSongId by remember { derivedStateOf { playbackState.value.currentSongId } }
    val isPlaying by remember { derivedStateOf { playbackState.value.isPlaying } }
    val shuffleOn by remember { derivedStateOf { playbackState.value.shuffleEnabled } }

    val playlist = playlists.firstOrNull { it.id == playlistId }

    // Close when the playlist goes away (deleted here or elsewhere), but
    // not while the list is still loading after a restore.
    var seen by remember(playlistId) { mutableStateOf(false) }
    LaunchedEffect(playlist != null) {
        if (playlist != null) seen = true else if (seen) onBack()
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }

    val pickers = rememberLibraryPickers(musicViewModel)
    val latestOnBack by rememberUpdatedState(onBack)
    val isFolder = playlistId in folderIds
    val songs = content.songs.takeIf { content.playlistId == playlistId }
    val link = folderLink?.takeIf { it.playlistId == playlistId }
    val running = folderOperation as? FolderOperationState.Running

    val ui = PlaylistUi(
        id = playlistId,
        name = playlist?.name.orEmpty(),
        isFolder = isFolder,
        songs = songs,
        syncedText = if (isFolder) syncedLabel(link?.lastSyncedAtMs, now) else null,
        syncing = running?.playlistId == playlistId,
        accessLost = inlineFolderFailure(folderOperation)?.playlistId == playlistId,
        isCurrentQueue = queuedPlaylistId == playlistId && currentSongId != null,
        isPlaying = isPlaying,
        shuffleOn = shuffleOn,
        currentSongId = currentSongId,
        busy = running != null || libraryOperation.loading
    )

    val actions = remember(musicViewModel, playlistId, pickers) {
        object : PlaylistActions {
            private fun currentSongs(): List<SongEntity> =
                musicViewModel.selectedPlaylistContent.value
                    .takeIf { it.playlistId == playlistId }?.songs.orEmpty()

            override fun back() = latestOnBack()

            override fun playOrPause() {
                val state = musicViewModel.playbackState.value
                if (musicViewModel.queuePlaylistId.value == playlistId && state.currentSongId != null) {
                    musicViewModel.togglePlayPause()
                } else {
                    val list = currentSongs()
                    if (list.isEmpty()) return
                    // Play from the top in order.
                    if (state.shuffleEnabled) musicViewModel.toggleShuffle()
                    musicViewModel.playSongs(list, 0)
                }
            }

            override fun shufflePlay() {
                val list = currentSongs()
                if (list.isEmpty()) return
                if (!musicViewModel.playbackState.value.shuffleEnabled) musicViewModel.toggleShuffle()
                musicViewModel.playSongs(list, list.indices.random())
            }

            override fun playSong(index: Int) {
                val list = currentSongs()
                if (index in list.indices) musicViewModel.playSongs(list, index)
            }

            override fun sync() = musicViewModel.syncFolderPlaylist(playlistId)

            override fun reselectFolder() = pickers.openRelinkPicker(playlistId)

            override fun rename(name: String) = musicViewModel.renamePlaylist(playlistId, name)

            override fun delete() {
                musicViewModel.deletePlaylist(playlistId)
                latestOnBack()
            }

            override fun moveSong(index: Int, delta: Int) {
                val ids = currentSongs().map { it.id }
                moveSong(ids, index, delta)?.let { musicViewModel.reorderPlaylist(playlistId, it) }
            }

            override fun removeSong(songId: String) = musicViewModel.removeSongFromPlaylist(playlistId, songId)
        }
    }

    PlaylistScreen(ui = ui, actions = actions, contentPadding = contentPadding, modifier = modifier)
}

@Composable
fun PlaylistScreen(
    ui: PlaylistUi,
    actions: PlaylistActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    val listState = rememberLazyListState()
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var removeSongId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        PlaylistWash(
            playlistId = ui.id,
            playlistName = ui.name,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(WASH_FRACTION)
                // Scrolls up with the header; read while drawing, so scrolling never recomposes.
                .graphicsLayer {
                    translationY = if (listState.firstVisibleItemIndex == 0) {
                        -listState.firstVisibleItemScrollOffset.toFloat()
                    } else {
                        -size.height
                    }
                }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)
        ) {
            item(key = "header", contentType = "header") {
                PlaylistHeader(ui = ui, topPadding = topInset + ModernDimens.touchEffective, actions = actions)
            }

            item(key = "actions", contentType = "actions") {
                ActionRow(
                    ui = ui,
                    actions = actions,
                    onRename = { renaming = true },
                    onDelete = { confirmDelete = true }
                )
            }

            val songs = ui.songs
            when {
                songs == null -> Unit
                songs.isEmpty() -> item(key = "empty", contentType = "empty") {
                    EmptyPlaylist(isFolder = ui.isFolder, syncing = ui.syncing || ui.busy, onSync = actions::sync)
                }
                else -> itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id },
                    contentType = { _, _ -> "song" }
                ) { index, song ->
                    SongRow(
                        song = song,
                        index = index,
                        lastIndex = songs.lastIndex,
                        nowPlaying = ui.isCurrentQueue && song.id == ui.currentSongId,
                        onPlay = { actions.playSong(index) },
                        onMove = { delta -> actions.moveSong(index, delta) },
                        onRemove = {
                            if (ui.isFolder) removeSongId = song.id else actions.removeSong(song.id)
                        }
                    )
                }
            }
        }

        // Fixed, so Back stays reachable while scrolling; a soft disc keeps it readable over songs.
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 4.dp)
        ) {
            V2IconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = actions::back,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        }
    }

    if (renaming) {
        RenameDialog(
            current = ui.name,
            onDismiss = { renaming = false },
            onRename = { name ->
                renaming = false
                actions.rename(name)
            }
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${ui.name}”?",
            text = if (ui.isFolder) {
                "The playlist is deleted. Its songs stay in your library, and the folder on your SD card is not changed."
            } else {
                "The playlist is deleted. Its songs stay in your library."
            },
            confirmLabel = "Delete playlist",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.delete()
            }
        )
    }

    removeSongId?.let { songId ->
        val title = ui.songs?.firstOrNull { it.id == songId }?.title
        if (title == null) {
            // The song left the playlist meanwhile; nothing to confirm.
            LaunchedEffect(songId) { removeSongId = null }
        } else {
            ConfirmDialog(
                title = "Remove “$title”?",
                text = "It is removed from this playlist and stays removed when the folder syncs again. " +
                    "The file on your SD card is not changed.",
                confirmLabel = "Remove",
                onDismiss = { removeSongId = null },
                onConfirm = {
                    removeSongId = null
                    actions.removeSong(songId)
                }
            )
        }
    }
}

/** The cover-coloured gradient behind the header, also under the status bar. */
@Composable
private fun PlaylistWash(playlistId: Long, playlistName: String, modifier: Modifier = Modifier) {
    val colors = Modern.colors
    val bgArgb = colors.bg.toArgb()
    val fallback = remember(colors.accent, colors.bg) {
        Color(playlistWashFallback(colors.accent.toArgb(), bgArgb))
    }
    val extracted = rememberPlaylistWash(playlistId, playlistName)
    val top = animateColorAsState(
        targetValue = extracted ?: fallback,
        animationSpec = modernTween(ModernMotion.COLOR_MS),
        label = "playlistWash"
    )

    Box(
        modifier = modifier.drawBehind {
            val topColour = top.value
            val mid = Color(playlistWashMid(topColour.toArgb(), bgArgb))
            drawRect(Brush.verticalGradient(0f to topColour, WASH_MID_STOP to mid, 1f to colors.bg))
        }
    )
}

@Composable
private fun PlaylistHeader(ui: PlaylistUi, topPadding: Dp, actions: PlaylistActions) {
    val colors = Modern.colors
    val coverShape = RoundedCornerShape(ModernDimens.coverMedium)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
    ) {
        CoverImage(
            uri = playlistCoverUri(ui.id),
            size = CoverSize,
            corner = ModernDimens.coverMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .shadow(elevation = 18.dp, shape = coverShape, clip = false)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = ui.name,
            style = Modern.type.screenTitle,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = ModernDimens.screenPadding)
                .semantics { heading() }
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.padding(horizontal = ModernDimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ui.isFolder) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = "SD-card folder",
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = playlistHeaderMeta(ui.isFolder, ui.songs.orEmpty().map { it.durationMs }),
                style = Modern.type.rowSubtitle,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (ui.syncedText != null) {
            Text(
                text = ui.syncedText,
                style = Modern.type.caption,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, top = 2.dp)
            )
        }

        if (ui.accessLost) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Folder access lost",
                    style = Modern.type.rowSubtitle,
                    color = colors.accent,
                    modifier = Modifier.weight(1f)
                )
                OutlinePill(label = "Re-select folder", enabled = !ui.busy, onClick = actions::reselectFolder)
            }
        }
    }
}

@Composable
private fun ActionRow(
    ui: PlaylistUi,
    actions: PlaylistActions,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = Modern.colors
    val hasSongs = !ui.songs.isNullOrEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = ModernDimens.screenPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ui.isFolder) {
            SyncButton(name = ui.name, syncing = ui.syncing, enabled = !ui.busy, onSync = actions::sync)
        }

        PlaylistMenu(
            name = ui.name,
            isFolder = ui.isFolder,
            busy = ui.busy,
            onRename = onRename,
            onReselect = actions::reselectFolder,
            onDelete = onDelete
        )

        Spacer(modifier = Modifier.weight(1f))

        V2IconButton(
            icon = Icons.Outlined.Shuffle,
            contentDescription = "Shuffle play ${ui.name}",
            onClick = actions::shufflePlay,
            enabled = hasSongs,
            tint = if (ui.shuffleOn) colors.accent else colors.textPrimary,
            modifier = Modifier.semantics { stateDescription = if (ui.shuffleOn) "Shuffle on" else "Shuffle off" }
        )

        Spacer(modifier = Modifier.width(8.dp))

        val showPause = ui.isCurrentQueue && ui.isPlaying
        Box(
            modifier = Modifier
                .size(PlayButtonSize)
                .clip(CircleShape)
                .background(if (hasSongs) colors.accent else colors.accent.copy(alpha = 0.4f))
                .clickable(enabled = hasSongs, role = Role.Button, onClick = actions::playOrPause)
                .semantics { stateDescription = if (showPause) "Playing" else "Paused" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (showPause) "Pause" else "Play ${ui.name}",
                tint = colors.chipSelectedText,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

/** Sync icon; spins while this playlist syncs (still when animations are off). */
@Composable
private fun SyncButton(name: String, syncing: Boolean, enabled: Boolean, onSync: () -> Unit) {
    val spin = syncing && !LocalReduceMotion.current
    val rotation = if (spin) {
        rememberInfiniteTransition(label = "sync").animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
            label = "syncRotation"
        )
    } else {
        null
    }

    V2IconButton(
        icon = Icons.Outlined.Sync,
        contentDescription = "Sync $name with its SD-card folder",
        onClick = onSync,
        enabled = enabled && !syncing,
        modifier = Modifier
            .graphicsLayer { rotationZ = rotation?.value ?: 0f }
            .semantics { if (syncing) stateDescription = "Syncing" }
    )
}

@Composable
private fun PlaylistMenu(
    name: String,
    isFolder: Boolean,
    busy: Boolean,
    onRename: () -> Unit,
    onReselect: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = Modern.colors
    var open by remember { mutableStateOf(false) }

    Box {
        V2IconButton(icon = Icons.Outlined.MoreVert, contentDescription = "More options for $name", onClick = { open = true })

        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surfaceHigh) {
            MenuItem("Rename") {
                open = false
                onRename()
            }
            if (isFolder) {
                MenuItem("Re-select folder", enabled = !busy) {
                    open = false
                    onReselect()
                }
            }
            MenuItem("Delete playlist") {
                open = false
                onDelete()
            }
        }
    }
}

@Composable
private fun SongRow(
    song: SongEntity,
    index: Int,
    lastIndex: Int,
    nowPlaying: Boolean,
    onPlay: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val colors = Modern.colors
    var open by remember { mutableStateOf(false) }

    ListRow(
        coverUri = songCoverUri(song.id),
        title = song.title,
        subtitle = song.artist,
        onClick = onPlay,
        titleColor = if (nowPlaying) colors.accent else colors.textPrimary,
        rowHeight = SongRowSizes.height,
        coverSize = SongRowSizes.cover,
        onClickLabel = "Play"
    ) {
        if (nowPlaying) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Now playing",
                tint = colors.accent,
                modifier = Modifier.size(18.dp)
            )
        }

        Box {
            V2IconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More options for ${song.title}",
                onClick = { open = true }
            )

            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surfaceHigh) {
                MenuItem("Move up", enabled = index > 0) {
                    open = false
                    onMove(-1)
                }
                MenuItem("Move down", enabled = index < lastIndex) {
                    open = false
                    onMove(1)
                }
                MenuItem("Remove from playlist") {
                    open = false
                    onRemove()
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Modern.colors
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = Modern.type.menuItem,
                color = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.6f)
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun EmptyPlaylist(isFolder: Boolean, syncing: Boolean, onSync: () -> Unit) {
    val colors = Modern.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No songs in this playlist yet",
            style = Modern.type.playlistTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )

        if (isFolder) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = ModernDimens.touchEffective)
                    .clip(RoundedCornerShape(ModernDimens.touchEffective / 2))
                    .background(if (syncing) colors.chipSelected.copy(alpha = 0.4f) else colors.chipSelected)
                    .clickable(enabled = !syncing, role = Role.Button, onClick = onSync)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Sync now", style = Modern.type.cardTitle, color = colors.chipSelectedText)
            }
        }
    }
}

@Composable
private fun OutlinePill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Modern.colors
    val shape = RoundedCornerShape(ModernDimens.chip)

    Box(
        modifier = Modifier
            .heightIn(min = ModernDimens.touchEffective)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = Modern.type.chip,
            color = if (enabled) colors.accent else colors.textSecondary,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, if (enabled) colors.accent else colors.divider, shape)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val colors = Modern.colors
    var text by rememberSaveable { mutableStateOf(current) }
    val trimmed = text.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceHigh,
        title = { Text("Rename playlist", style = Modern.type.sectionTitle, color = colors.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = Modern.type.rowTitle.copy(color = colors.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onRename(trimmed) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(trimmed) }, enabled = canSave) {
                Text("Rename", style = Modern.type.cardTitle, color = if (canSave) colors.accent else colors.textSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = Modern.type.cardTitle, color = colors.textPrimary)
            }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = Modern.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceHigh,
        title = { Text(title, style = Modern.type.sectionTitle, color = colors.textPrimary) },
        text = { Text(text, style = Modern.type.rowSubtitle, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = Modern.type.cardTitle, color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = Modern.type.cardTitle, color = colors.textPrimary)
            }
        }
    )
}
