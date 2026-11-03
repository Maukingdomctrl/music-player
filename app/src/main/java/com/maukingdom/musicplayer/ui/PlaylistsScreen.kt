package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.playback.ArtworkPaths
import java.util.Locale

/** Folder failures the user can fix by selecting the folder again. */
private val RESELECTABLE_REASONS = setOf(
    FolderFailureReason.PERMISSION_MISSING,
    FolderFailureReason.FOLDER_UNAVAILABLE
)

/**
 * The failed sync that should be shown inline on its playlist, or null.
 * Import failures have no playlist yet and stay in the banner.
 */
internal fun inlineFolderFailure(
    folderOperation: FolderOperationState
): FolderOperationState.Failed? {
    return (folderOperation as? FolderOperationState.Failed)?.takeIf {
        it.playlistId != null && it.reason in RESELECTABLE_REASONS
    }
}

private val OUTLINE = 1.25.dp
private val PANEL_CORNER = 24.dp
private val NeonOutline = listOf(
    StereoColors.Green,
    StereoColors.NeonPurple,
    StereoColors.NeonPink
)

/**
 * The LISTS page: SD-card folder playlists and the user's own playlists,
 * and the opened playlist with its songs.
 *
 * [currentSongId] highlights the song that is playing. [onShufflePlay], when
 * given, adds a SHUFFLE button that plays the opened playlist shuffled.
 */
@Composable
fun PlaylistsScreen(
    playlists: List<PlaylistEntity>,
    selectedPlaylistId: Long?,
    selectedSongs: List<SongEntity>,
    onSelectPlaylist: (Long?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemoveSong: (Long, String) -> Unit,
    onReorder: (Long, List<String>) -> Unit,
    onPlay: (Int) -> Unit,
    folderLinkedIds: Set<Long>,
    folderOperation: FolderOperationState,
    loading: Boolean,
    onImportFolder: () -> Unit,
    onSyncFolder: (Long) -> Unit,
    onReselectFolder: (Long) -> Unit,
    onDismissFolderProblem: () -> Unit,
    modifier: Modifier = Modifier,
    currentSongId: String? = null,
    onShufflePlay: (() -> Unit)? = null
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }

    val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }

    val syncingPlaylistId = (folderOperation as? FolderOperationState.Running)
        ?.takeIf { it.kind == FolderOperationKind.SYNC }
        ?.playlistId

    val folderFailure = inlineFolderFailure(folderOperation)

    if (selectedPlaylist == null) {
        PlaylistListView(
            playlists = playlists,
            folderLinkedIds = folderLinkedIds,
            syncingPlaylistId = syncingPlaylistId,
            folderFailure = folderFailure,
            loading = loading,
            onOpen = onSelectPlaylist,
            onCreate = { creating = true },
            onRename = { renaming = it },
            onDelete = { deleting = it },
            onImportFolder = onImportFolder,
            onSyncFolder = onSyncFolder,
            onReselectFolder = onReselectFolder,
            onDismissFolderProblem = onDismissFolderProblem,
            modifier = modifier
        )
    } else {
        OpenedPlaylistView(
            playlist = selectedPlaylist,
            songs = selectedSongs,
            folderLinked = selectedPlaylist.id in folderLinkedIds,
            syncing = syncingPlaylistId == selectedPlaylist.id,
            folderFailure = folderFailure?.takeIf { it.playlistId == selectedPlaylist.id },
            loading = loading,
            currentSongId = currentSongId,
            onBack = { onSelectPlaylist(null) },
            onPlay = onPlay,
            onShufflePlay = onShufflePlay,
            onSync = { onSyncFolder(selectedPlaylist.id) },
            onReselect = { onReselectFolder(selectedPlaylist.id) },
            onDismissFolderProblem = onDismissFolderProblem,
            onReorder = { ids -> onReorder(selectedPlaylist.id, ids) },
            onRemoveSong = { songId -> onRemoveSong(selectedPlaylist.id, songId) },
            modifier = modifier
        )
    }

    if (creating) {
        PlaylistNameDialog(
            title = "Create playlist",
            initialName = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                creating = false
            }
        )
    }

    renaming?.let { playlist ->
        PlaylistNameDialog(
            title = "Rename playlist",
            initialName = playlist.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRenamePlaylist(playlist.id, name)
                renaming = null
            }
        )
    }

    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "\"${playlist.name}\" will be deleted. " +
                        "Its audio files will not be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlist.id)
                        deleting = null
                    }
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

// List view

@Composable
private fun PlaylistListView(
    playlists: List<PlaylistEntity>,
    folderLinkedIds: Set<Long>,
    syncingPlaylistId: Long?,
    folderFailure: FolderOperationState.Failed?,
    loading: Boolean,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onRename: (PlaylistEntity) -> Unit,
    onDelete: (PlaylistEntity) -> Unit,
    onImportFolder: () -> Unit,
    onSyncFolder: (Long) -> Unit,
    onReselectFolder: (Long) -> Unit,
    onDismissFolderProblem: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current
    val neon = LocalPlayerSkin.current == PlayerSkin.NEON_WALKMAN

    val (folderPlaylists, manualPlaylists) = playlists.partition { it.id in folderLinkedIds }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playlists",
                color = tokens.text,
                fontFamily = BrandFont,
                fontSize = 18.sp,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )

            RoundAddButton(onClick = onCreate)
        }

        if (playlists.isEmpty()) {
            EmptyPlaylists(
                loading = loading,
                onImportFolder = onImportFolder,
                onCreate = onCreate,
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item(key = "import") {
                ImportFolderPill(
                    enabled = !loading,
                    onClick = onImportFolder,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

            if (folderPlaylists.isNotEmpty()) {
                item(key = "label-folders") {
                    SectionLabel(text = "SD CARD FOLDERS", color = tokens.accent)
                }

                playlistSection(
                    keyPrefix = "folder",
                    playlists = folderPlaylists,
                    neon = neon
                ) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        subtitle = "SD card folder",
                        folderLinked = true,
                        syncing = syncingPlaylistId == playlist.id,
                        syncEnabled = !loading,
                        onOpen = { onOpen(playlist.id) },
                        onSync = { onSyncFolder(playlist.id) },
                        onRename = { onRename(playlist) },
                        onDelete = { onDelete(playlist) }
                    )

                    if (folderFailure?.playlistId == playlist.id) {
                        FolderProblem(
                            message = folderFailure.message,
                            onReselect = { onReselectFolder(playlist.id) },
                            onDismiss = onDismissFolderProblem,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        )
                    }
                }
            }

            if (manualPlaylists.isNotEmpty()) {
                item(key = "label-manual") {
                    SectionLabel(
                        text = "MY PLAYLISTS",
                        color = if (neon) StereoColors.NeonPink else tokens.accent,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                playlistSection(
                    keyPrefix = "manual",
                    playlists = manualPlaylists,
                    neon = neon
                ) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        subtitle = "Playlist",
                        folderLinked = false,
                        syncing = false,
                        syncEnabled = false,
                        onOpen = { onOpen(playlist.id) },
                        onSync = {},
                        onRename = { onRename(playlist) },
                        onDelete = { onDelete(playlist) }
                    )
                }
            }
        }
    }
}

/** Adds [playlists] as segments of one rounded panel, with hairline dividers. */
private fun LazyListScope.playlistSection(
    keyPrefix: String,
    playlists: List<PlaylistEntity>,
    neon: Boolean,
    row: @Composable (PlaylistEntity) -> Unit
) {
    itemsIndexed(
        items = playlists,
        key = { _, playlist -> "$keyPrefix-${playlist.id}" }
    ) { index, playlist ->
        PanelSegment(index = index, count = playlists.size, neon = neon) {
            row(playlist)
        }
    }
}

@Composable
private fun EmptyPlaylists(
    loading: Boolean,
    onImportFolder: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = tokens.muted,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No playlists yet",
            color = tokens.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Import a folder from your SD card or create one.",
            color = tokens.muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        ImportFolderPill(enabled = !loading, onClick = onImportFolder)

        Spacer(modifier = Modifier.height(4.dp))

        OutlinePill(
            text = "NEW PLAYLIST",
            icon = Icons.Filled.Add,
            color = tokens.text,
            onClick = onCreate
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    subtitle: String,
    folderLinked: Boolean,
    syncing: Boolean,
    syncEnabled: Boolean,
    onOpen: () -> Unit,
    onSync: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = LocalSkinTokens.current
    val skin = LocalPlayerSkin.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(
                onClickLabel = "Open",
                onLongClickLabel = "Rename or delete",
                onLongClick = { menuOpen = true },
                onClick = onOpen
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            uri = ArtworkPaths.playlistUri(playlist.id),
            size = 48.dp,
            corner = 12.dp,
            revision = "${playlist.name}|${skin.id}"
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = tokens.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = tokens.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (folderLinked) {
            SyncPill(
                playlistName = playlist.name,
                syncing = syncing,
                enabled = syncEnabled,
                onClick = onSync
            )
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options for ${playlist.name}",
                    tint = tokens.muted
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = tokens.panelRaised
            ) {
                MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) {
                    menuOpen = false
                    onRename()
                }
                MenuItem("Delete", Icons.Outlined.Delete) {
                    menuOpen = false
                    onDelete()
                }
            }
        }
    }
}

// Opened playlist

@Composable
private fun OpenedPlaylistView(
    playlist: PlaylistEntity,
    songs: List<SongEntity>,
    folderLinked: Boolean,
    syncing: Boolean,
    folderFailure: FolderOperationState.Failed?,
    loading: Boolean,
    currentSongId: String?,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShufflePlay: (() -> Unit)?,
    onSync: () -> Unit,
    onReselect: () -> Unit,
    onDismissFolderProblem: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemoveSong: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current
    val skin = LocalPlayerSkin.current
    val neon = skin == PlayerSkin.NEON_WALKMAN

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to playlists",
                        tint = tokens.text
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(
                        uri = ArtworkPaths.playlistUri(playlist.id),
                        size = 120.dp,
                        corner = 20.dp,
                        revision = "${playlist.name}|${skin.id}"
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = playlist.name,
                            color = tokens.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() }
                        )

                        if (folderLinked) {
                            SdFolderChip()
                        }

                        Text(
                            text = if (songs.size == 1) "1 song" else "${songs.size} songs",
                            color = tokens.muted,
                            fontSize = 13.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledPill(
                        text = "PLAY ALL",
                        icon = Icons.Filled.PlayArrow,
                        enabled = songs.isNotEmpty(),
                        onClick = { onPlay(0) },
                        modifier = Modifier.weight(1.2f)
                    )

                    if (onShufflePlay != null) {
                        OutlinePill(
                            text = "SHUFFLE",
                            icon = Icons.Filled.Shuffle,
                            color = tokens.text,
                            enabled = songs.isNotEmpty(),
                            onClick = onShufflePlay,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (folderLinked) {
                        SyncPill(
                            playlistName = playlist.name,
                            syncing = syncing,
                            enabled = !loading,
                            onClick = onSync,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (folderFailure != null) {
                    FolderProblem(
                        message = folderFailure.message,
                        onReselect = onReselect,
                        onDismiss = onDismissFolderProblem
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        if (songs.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "This playlist is empty. Add songs from Library.",
                    color = tokens.muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 24.dp)
                )
            }
        } else {
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id }
            ) { index, song ->
                PanelSegment(index = index, count = songs.size, neon = neon) {
                    SongRow(
                        index = index,
                        song = song,
                        playing = song.id == currentSongId,
                        canMoveUp = index > 0,
                        canMoveDown = index < songs.lastIndex,
                        onPlay = { onPlay(index) },
                        onMoveUp = { onReorder(swapped(songs, index, index - 1)) },
                        onMoveDown = { onReorder(swapped(songs, index, index + 1)) },
                        onRemove = { onRemoveSong(song.id) }
                    )
                }
            }
        }
    }
}

private fun swapped(songs: List<SongEntity>, from: Int, to: Int): List<String> {
    val ids = songs.map { it.id }.toMutableList()
    val moved = ids[from]
    ids[from] = ids[to]
    ids[to] = moved
    return ids
}

@Composable
private fun SongRow(
    index: Int,
    song: SongEntity,
    playing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val tokens = LocalSkinTokens.current
    var menuOpen by remember { mutableStateOf(false) }
    val titleColor = if (playing) tokens.accent else tokens.text

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (playing) tokens.accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClickLabel = "Play from here", role = Role.Button, onClick = onPlay)
            .semantics {
                if (playing) stateDescription = "Now playing"
            }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            uri = ArtworkPaths.songUri(song.id),
            size = 40.dp,
            corner = 10.dp,
            revision = song.title
        )

        Text(
            text = String.format(Locale.ROOT, "%02d", index + 1),
            color = tokens.muted,
            fontFamily = ShareTechMono,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 30.dp)
                .padding(end = 10.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = tokens.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (playing) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
            )
        }

        Text(
            text = formatPlaybackTime(song.durationMs),
            color = tokens.muted,
            fontFamily = ShareTechMono,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
        )

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options for ${song.title}",
                    tint = tokens.muted
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = tokens.panelRaised
            ) {
                MenuItem("Move up", Icons.Outlined.ArrowUpward, enabled = canMoveUp) {
                    menuOpen = false
                    onMoveUp()
                }
                MenuItem("Move down", Icons.Outlined.ArrowDownward, enabled = canMoveDown) {
                    menuOpen = false
                    onMoveDown()
                }
                MenuItem("Remove from playlist", Icons.Outlined.RemoveCircleOutline) {
                    menuOpen = false
                    onRemove()
                }
            }
        }
    }
}

// Building blocks

/**
 * One row's slice of a rounded panel. The first slice has the top corners,
 * the last the bottom ones, so consecutive items read as a single panel;
 * slices after the first start with a hairline divider.
 */
@Composable
private fun PanelSegment(
    index: Int,
    count: Int,
    neon: Boolean,
    content: @Composable () -> Unit
) {
    val tokens = LocalSkinTokens.current
    val first = index == 0
    val last = index == count - 1

    val shape = RoundedCornerShape(
        topStart = if (first) PANEL_CORNER else 0.dp,
        topEnd = if (first) PANEL_CORNER else 0.dp,
        bottomStart = if (last) PANEL_CORNER else 0.dp,
        bottomEnd = if (last) PANEL_CORNER else 0.dp
    )

    val outline: Brush = if (neon) {
        Brush.horizontalGradient(NeonOutline)
    } else {
        Brush.linearGradient(listOf(tokens.muted.copy(alpha = 0.3f), tokens.muted.copy(alpha = 0.3f)))
    }
    val divider = tokens.muted.copy(alpha = 0.18f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.copy(alpha = 0.85f))
            .drawBehind {
                val stroke = OUTLINE.toPx()
                val half = stroke / 2f
                val radius = PANEL_CORNER.toPx()
                val path = segmentOutline(
                    width = size.width,
                    height = size.height,
                    inset = half,
                    radius = radius,
                    first = first,
                    last = last
                )
                drawPath(path = path, brush = outline, style = Stroke(width = stroke))

                if (!first) {
                    drawLine(
                        color = divider,
                        start = Offset(72.dp.toPx(), 0f),
                        end = Offset(size.width - 16.dp.toPx(), 0f),
                        strokeWidth = 1f
                    )
                }
            }
    ) {
        content()
    }
}

/** Left and right edges, plus the rounded top and/or bottom where needed. */
private fun segmentOutline(
    width: Float,
    height: Float,
    inset: Float,
    radius: Float,
    first: Boolean,
    last: Boolean
): Path {
    val left = inset
    val right = width - inset
    val top = inset
    val bottom = height - inset

    return Path().apply {
        if (first && last) {
            addRoundRect(RoundRect(Rect(left, top, right, bottom), CornerRadius(radius - inset)))
            return@apply
        }

        if (first) {
            moveTo(left, height)
            lineTo(left, top + radius)
            arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
            lineTo(right - radius, top)
            arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), 270f, 90f, false)
            lineTo(right, height)
        } else if (last) {
            moveTo(left, 0f)
            lineTo(left, bottom - radius)
            arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 180f, -90f, false)
            lineTo(right - radius, bottom)
            arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 90f, -90f, false)
            lineTo(right, 0f)
        } else {
            moveTo(left, 0f)
            lineTo(left, height)
            moveTo(right, 0f)
            lineTo(right, height)
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = modifier
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun RoundAddButton(onClick: () -> Unit) {
    val tokens = LocalSkinTokens.current

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "New playlist" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(OUTLINE, tokens.accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ImportFolderPill(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinePill(
        text = "IMPORT FOLDER FROM SD CARD",
        icon = Icons.Outlined.SdCard,
        color = LocalSkinTokens.current.accent,
        enabled = enabled,
        onClick = onClick,
        description = "Import a folder from the SD card",
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SyncPill(
    playlistName: String,
    syncing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinePill(
        text = if (syncing) "SYNCING…" else "SYNC",
        icon = Icons.Filled.Sync,
        color = LocalSkinTokens.current.secondary,
        enabled = enabled && !syncing,
        busy = syncing,
        onClick = onClick,
        description = "Sync $playlistName with its SD-card folder",
        modifier = modifier
    )
}

/**
 * A slim outlined pill (34 dp) inside a 48 dp touch target. Shows a small
 * spinner instead of the icon while [busy].
 */
@Composable
private fun OutlinePill(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    description: String? = null
) {
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled || busy) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .border(OUTLINE, color, shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = color,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The main action: filled with the skin's accent. */
@Composable
private fun FilledPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(tokens.accent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.accentOn,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            color = tokens.accentOn,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SdFolderChip() {
    val color = LocalSkinTokens.current.secondary

    Row(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.SdCard,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "SD FOLDER",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun MenuItem(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tokens = LocalSkinTokens.current

    DropdownMenuItem(
        text = { Text(text, color = tokens.text) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tokens.muted) },
        enabled = enabled,
        onClick = onClick
    )
}

/** Folder access was lost or the card is missing; offer to pick it again. */
@Composable
private fun FolderProblem(
    message: String,
    onReselect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(StereoColors.Error.copy(alpha = 0.10f))
            .border(OUTLINE, StereoColors.Error.copy(alpha = 0.7f), shape)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                color = tokens.text,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            )

            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = tokens.muted
                )
            }
        }

        OutlinePill(
            text = "RE-SELECT FOLDER",
            icon = Icons.Outlined.SdCard,
            color = StereoColors.Error,
            onClick = onReselect,
            modifier = Modifier.padding(end = 10.dp)
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) {
                Text("SAVE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
