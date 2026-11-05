package com.maukingdom.musicplayer.ui.v2.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.FolderOperationState
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.inlineFolderFailure
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.modernTween
import com.maukingdom.musicplayer.ui.v2.playlistpicker.AddToPlaylistMenuItem
import com.maukingdom.musicplayer.ui.v2.playlistpicker.FavouriteMenuItem
import com.maukingdom.musicplayer.ui.v2.playlistpicker.LocalFavourites
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SongMenu
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SongMenuItem
import com.maukingdom.musicplayer.ui.v2.playlistpicker.LocalSongQueue
import com.maukingdom.musicplayer.ui.v2.playlistpicker.QueueMenuItems
import com.maukingdom.musicplayer.ui.v2.playlistpicker.LocalPlaylistPicker
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SelectionCheck
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SelectionTopBar
import com.maukingdom.musicplayer.ui.v2.playlistpicker.SongMenuButton
import com.maukingdom.musicplayer.ui.v2.playlistpicker.rememberSongSelection
import com.maukingdom.musicplayer.ui.v2.playlistpicker.selectionClickLabel
import com.maukingdom.musicplayer.ui.v2.playlistpicker.selectionInListOrder
import com.maukingdom.musicplayer.ui.v2.playlistpicker.selectionSemantics
import com.maukingdom.musicplayer.ui.v2.components.Chip
import com.maukingdom.musicplayer.ui.v2.components.ListRow
import com.maukingdom.musicplayer.ui.v2.components.SongRowSizes
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton
import com.maukingdom.musicplayer.ui.v2.cover.LivingCover
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Above this many songs, sorting and grouping run off the main thread. */
private const val BACKGROUND_SORT_THRESHOLD = 2_000

/** What the list shows for the selected chip. */
@Immutable
sealed interface LibraryRows {
    data class Playlists(val items: List<PlaylistItem>) : LibraryRows
    data class Songs(val songs: List<SongEntity>) : LibraryRows
    data class Groups(val groups: List<SongGroup>, val albums: Boolean) : LibraryRows

    val isEmpty: Boolean
        get() = when (this) {
            is Playlists -> items.isEmpty()
            is Songs -> songs.isEmpty()
            is Groups -> groups.isEmpty()
        }
}

/** An info or error line under the chips; dismissible. */
@Immutable
data class LibraryBanner(val text: String, val isError: Boolean)

/**
 * Your Library tab: collects library data from [musicViewModel] and shows
 * [LibraryScreen]. Playlist and folder rows open the Playlist screen
 * ([onOpenPlaylist]); songs, artists and albums play directly.
 */
@Composable
fun LibraryRoute(
    musicViewModel: MusicViewModel,
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val counts by musicViewModel.playlistSongCounts.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()
    val songs by musicViewModel.songs.collectAsStateWithLifecycle()
    val libraryOperation by musicViewModel.libraryOperation.collectAsStateWithLifecycle()
    val folderOperation by musicViewModel.folderOperation.collectAsStateWithLifecycle()
    val queuedPlaylistId by musicViewModel.queuePlaylistId.collectAsStateWithLifecycle()

    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.PLAYLISTS.name) }
    var sortsEncoded by rememberSaveable { mutableStateOf("") }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    val filter = LibraryFilter.entries.firstOrNull { it.name == filterName } ?: LibraryFilter.PLAYLISTS
    val sort = remember(filter, sortsEncoded) { validSort(filter, decodeSorts(sortsEncoded)[filter]) }

    val pickers = rememberLibraryPickers(musicViewModel)
    val favouritesId by musicViewModel.favouritesPlaylistId.collectAsStateWithLifecycle()
    val rows = rememberLibraryRows(filter, sort, playlists, counts, folderIds, songs, favouritesId)

    // A sync that lost its folder is shown on its row, so the banner skips it there.
    val inlineFailure = inlineFolderFailure(folderOperation)
    val failedPlaylistId = inlineFailure?.playlistId?.takeIf { id -> playlists.any { it.id == id } }
    val failureOnScreen = failedPlaylistId != null &&
        (filter == LibraryFilter.PLAYLISTS || filter == LibraryFilter.FOLDERS)

    val progress = when (val op = folderOperation) {
        is FolderOperationState.Running -> progressText(
            folderKind = op.kind,
            playlistName = op.playlistId?.let { id -> playlists.firstOrNull { it.id == id }?.name }
        )
        else -> if (libraryOperation.loading) progressText(null, null) else null
    }

    val banner = libraryOperation.error
        ?.takeUnless { failureOnScreen && it == inlineFailure?.message }
        ?.let { LibraryBanner(it, isError = true) }
        ?: libraryOperation.message?.let { LibraryBanner(it, isError = false) }

    LibraryScreen(
        filter = filter,
        sort = sort,
        rows = rows,
        queuedPlaylistId = queuedPlaylistId,
        accessLostPlaylistId = failedPlaylistId,
        progress = progress,
        banner = banner,
        contentPadding = contentPadding,
        onSelectFilter = { filterName = it.name },
        onCycleSort = {
            val sorts = decodeSorts(sortsEncoded) + (filter to nextSort(filter, sort))
            sortsEncoded = encodeSorts(sorts)
        },
        onOpenSearch = onOpenSearch,
        onAddMusic = { showAddSheet = true },
        onImportFolder = pickers.openFolderPicker,
        onOpenPlaylist = onOpenPlaylist,
        onPlaySongs = musicViewModel::playSongs,
        onSyncFolder = musicViewModel::syncFolderPlaylist,
        onReselectFolder = pickers.openRelinkPicker,
        onDismissBanner = musicViewModel::clearOperationMessages,
        modifier = modifier
    )

    if (showAddSheet) {
        AddMusicSheet(
            onDismiss = { showAddSheet = false },
            onImportFolder = pickers.openFolderPicker,
            onScanPhone = pickers.requestPhoneScan,
            onSelectFiles = pickers.openFilePicker
        )
    }
}

/**
 * The rows for [filter], re-derived only when an input changes. Large
 * libraries are sorted on Dispatchers.Default; null until that finishes.
 */
@Composable
private fun rememberLibraryRows(
    filter: LibraryFilter,
    sort: LibrarySort,
    playlists: List<PlaylistEntity>,
    counts: Map<Long, Int>,
    folderIds: Set<Long>,
    songs: List<SongEntity>,
    favouritesId: Long?
): LibraryRows? {
    fun build(): LibraryRows = when (filter) {
        LibraryFilter.PLAYLISTS -> LibraryRows.Playlists(playlistItems(playlists, counts, folderIds, false, sort, favouritesId))
        LibraryFilter.FOLDERS -> LibraryRows.Playlists(playlistItems(playlists, counts, folderIds, true, sort, favouritesId))
        LibraryFilter.SONGS -> LibraryRows.Songs(sortSongs(songs, sort))
        LibraryFilter.ARTISTS -> LibraryRows.Groups(groupByArtist(songs, sort), albums = false)
        LibraryFilter.ALBUMS -> LibraryRows.Groups(groupByAlbum(songs, sort), albums = true)
    }

    val songBased = filter == LibraryFilter.SONGS || filter == LibraryFilter.ARTISTS || filter == LibraryFilter.ALBUMS
    return if (songBased && songs.size > BACKGROUND_SORT_THRESHOLD) {
        produceState<LibraryRows?>(initialValue = null, filter, sort, songs) {
            value = withContext(Dispatchers.Default) { build() }
        }.value
    } else {
        remember(filter, sort, playlists, counts, folderIds, songs, favouritesId) { build() }
    }
}

@Composable
fun LibraryScreen(
    filter: LibraryFilter,
    sort: LibrarySort,
    rows: LibraryRows?,
    queuedPlaylistId: Long?,
    accessLostPlaylistId: Long?,
    progress: String?,
    banner: LibraryBanner?,
    contentPadding: PaddingValues,
    onSelectFilter: (LibraryFilter) -> Unit,
    onCycleSort: () -> Unit,
    onOpenSearch: () -> Unit,
    onAddMusic: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySongs: (List<SongEntity>, Int) -> Unit,
    onSyncFolder: (Long) -> Unit,
    onReselectFolder: (Long) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val picker = LocalPlaylistPicker.current
    val songQueue = LocalSongQueue.current
    val favourites = LocalFavourites.current
    // Multi-select, for the Songs list only.
    val selection = rememberSongSelection()
    val listedIds = remember(rows) { (rows as? LibraryRows.Songs)?.songs?.map { it.id } }
    LaunchedEffect(filter, listedIds) {
        if (filter != LibraryFilter.SONGS) selection.clear() else listedIds?.let(selection::prune)
    }
    BackHandler(enabled = selection.active) { selection.clear() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryHeader(onOpenSearch = onOpenSearch, onAddMusic = onAddMusic)

            FilterChips(selected = filter, onSelect = onSelectFilter)

            SortButton(sort = sort, onClick = onCycleSort)

            if (progress != null) {
                ProgressBanner(text = progress)
            }

            if (banner != null) {
                MessageBanner(banner = banner, onDismiss = onDismissBanner)
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // Scrolls behind the mini player and navigation, ending clear of them.
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)
            ) {
                when {
                    rows == null -> Unit
                    rows.isEmpty -> item(key = "empty", contentType = "empty") {
                        EmptyLibrary(filter = filter, onAddMusic = onAddMusic, onImportFolder = onImportFolder)
                    }
                    rows is LibraryRows.Playlists -> playlistRows(
                        items = rows.items,
                        queuedPlaylistId = queuedPlaylistId,
                        accessLostPlaylistId = accessLostPlaylistId,
                        busy = progress != null,
                        onOpenPlaylist = onOpenPlaylist,
                        onSyncFolder = onSyncFolder,
                        onReselectFolder = onReselectFolder
                    )
                    rows is LibraryRows.Songs -> itemsIndexed(
                        items = rows.songs,
                        key = { _, song -> "s:${song.id}" },
                        contentType = { _, _ -> "song" }
                    ) { index, song ->
                        val selected = selection.selected?.contains(song.id)
                        ListRow(
                            coverUri = songCoverUri(song.id),
                            title = song.title,
                            subtitle = song.artist,
                            onClick = {
                                if (selection.active) selection.toggle(song.id) else onPlaySongs(rows.songs, index)
                            },
                            modifier = selectionSemantics(selected),
                            rowHeight = SongRowSizes.height,
                            coverSize = SongRowSizes.cover,
                            onClickLabel = selectionClickLabel(selected, "Play"),
                            onLongClick = {
                                if (selection.active) selection.toggle(song.id) else selection.start(song.id)
                            },
                            onLongClickLabel = if (selected == null) "Select" else null
                        ) {
                            if (selected != null) {
                                SelectionCheck(selected = selected)
                            } else {
                                SongMenuButton(songTitle = song.title) { close ->
                                    QueueMenuItems(songId = song.id, close = close)
                                    FavouriteMenuItem(songId = song.id, close = close)
                                    AddToPlaylistMenuItem(songId = song.id, close = close)
                                }
                            }
                        }
                    }
                    rows is LibraryRows.Groups -> items(
                        items = rows.groups,
                        key = { group -> (if (rows.albums) "al:" else "ar:") + group.key },
                        contentType = { "group" }
                    ) { group ->
                        ListRow(
                            coverUri = songCoverUri(group.coverSongId),
                            title = group.name,
                            subtitle = if (rows.albums) {
                                albumMetaText(group.artist ?: UNKNOWN_ARTIST, group.songs.size)
                            } else {
                                songCountLabel(group.songs.size)
                            },
                            onClick = { onPlaySongs(group.songs, 0) },
                            titleStyle = Modern.type.playlistTitle,
                            // Artists are round, albums square: the shape tells them apart.
                            coverCorner = if (rows.albums) ModernDimens.coverSmall else ModernDimens.listRowCover / 2,
                            onClickLabel = "Play"
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selection.active,
            enter = fadeIn(modernTween(ModernMotion.CROSSFADE_MS)),
            exit = fadeOut(modernTween(ModernMotion.CROSSFADE_MS)),
            label = "selectionBar"
        ) {
            SelectionTopBar(
                count = selection.count,
                allSelected = selection.count == listedIds.orEmpty().size,
                onClose = selection::clear,
                onSelectAll = { selection.selectAll(listedIds.orEmpty()) }
            ) {
                fun selected() = selectionInListOrder(listedIds.orEmpty(), selection.selected.orEmpty())
                V2IconButton(
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    onClick = {
                        picker.add(selected())
                        selection.clear()
                    }
                )
                V2IconButton(
                    icon = Icons.Outlined.FavoriteBorder,
                    contentDescription = "Add to Favourites",
                    onClick = {
                        favourites.add(selected())
                        selection.clear()
                    }
                )
                // Queue actions in an overflow, so "N selected" keeps room at 384 dp and 1.3x text.
                Box {
                    var more by remember { mutableStateOf(false) }
                    V2IconButton(icon = Icons.Outlined.MoreVert, contentDescription = "More actions", onClick = { more = true })
                    SongMenu(expanded = more, onDismiss = { more = false }) { close ->
                        SongMenuItem("Play next") {
                            close()
                            songQueue.playNext(selected())
                            selection.clear()
                        }
                        SongMenuItem("Add to queue") {
                            close()
                            songQueue.addToQueue(selected())
                            selection.clear()
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.playlistRows(
    items: List<PlaylistItem>,
    queuedPlaylistId: Long?,
    accessLostPlaylistId: Long?,
    busy: Boolean,
    onOpenPlaylist: (Long) -> Unit,
    onSyncFolder: (Long) -> Unit,
    onReselectFolder: (Long) -> Unit
) {
    items(
        items = items,
        key = { "p:${it.id}" },
        contentType = { if (it.isFolder) "folder" else "playlist" }
    ) { item ->
        PlaylistRow(
            item = item,
            queued = item.id == queuedPlaylistId,
            accessLost = item.isFolder && item.id == accessLostPlaylistId,
            busy = busy,
            onOpen = { onOpenPlaylist(item.id) },
            onSync = { onSyncFolder(item.id) },
            onReselect = { onReselectFolder(item.id) }
        )
    }
}

@Composable
private fun LibraryHeader(onOpenSearch: () -> Unit, onAddMusic: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = ModernDimens.screenPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Your Library",
            style = Modern.type.screenTitle,
            color = Modern.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )

        V2IconButton(icon = Icons.Outlined.Search, contentDescription = "Search your library", onClick = onOpenSearch)
        V2IconButton(icon = Icons.Outlined.Add, contentDescription = "Add music", onClick = onAddMusic)
    }
}

@Composable
private fun FilterChips(selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ModernDimens.screenPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryFilter.entries.forEach { filter ->
            Chip(text = filter.label, selected = filter == selected, onClick = { onSelect(filter) })
        }
    }
}

@Composable
private fun SortButton(sort: LibrarySort, onClick: () -> Unit) {
    val colors = Modern.colors
    val type = Modern.type
    // 13 sp / 600 in Inter.
    val style = remember(type) { type.rowSubtitle.copy(fontWeight = FontWeight(600)) }

    Row(
        modifier = Modifier
            .padding(start = 8.dp)
            .heightIn(min = ModernDimens.touchEffective)
            .clip(RoundedCornerShape(ModernDimens.chip))
            .clickable(role = Role.Button, onClickLabel = "Change sort order", onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Sort: ${sort.label}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Sort,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = sort.label, style = style, color = colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun PlaylistRow(
    item: PlaylistItem,
    queued: Boolean,
    accessLost: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onSync: () -> Unit,
    onReselect: () -> Unit
) {
    val colors = Modern.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.listRowHeight)
            .clickable(
                onClickLabel = if (accessLost) "Re-select folder" else "Open",
                onClick = if (accessLost) onReselect else onOpen
            )
            .padding(
                start = ModernDimens.screenPadding,
                end = if (item.isFolder) 4.dp else ModernDimens.screenPadding,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LivingCover(
            playlistId = item.id,
            playlistName = item.name,
            size = ModernDimens.listRowCover,
            corner = ModernDimens.coverSmall,
            animate = false
        )

        Spacer(modifier = Modifier.width(ModernDimens.itemGap))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.name,
                style = Modern.type.playlistTitle,
                color = if (queued) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accessLost) {
                    Text(
                        text = FOLDER_ACCESS_LOST,
                        style = Modern.type.rowSubtitle,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    if (item.isFavourites || item.isFolder) {
                        Icon(
                            imageVector = if (item.isFavourites) Icons.Filled.Favorite else Icons.Filled.Folder,
                            contentDescription = if (item.isFavourites) "Favourites" else "SD-card folder",
                            tint = colors.accent,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = playlistMetaText(item.isFolder, item.songCount),
                        style = Modern.type.rowSubtitle,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (item.isFolder) {
            FolderMenu(busy = busy, onSync = onSync, onReselect = onReselect)
        }
    }
}

@Composable
private fun FolderMenu(busy: Boolean, onSync: () -> Unit, onReselect: () -> Unit) {
    val colors = Modern.colors
    var open by remember { mutableStateOf(false) }

    Box {
        V2IconButton(icon = Icons.Outlined.MoreVert, contentDescription = "Folder options", onClick = { open = true })

        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surfaceHigh) {
            DropdownMenuItem(
                text = { Text("Sync now", style = Modern.type.menuItem, color = colors.textPrimary) },
                enabled = !busy,
                onClick = {
                    open = false
                    onSync()
                }
            )
            DropdownMenuItem(
                text = { Text("Re-select folder", style = Modern.type.menuItem, color = colors.textPrimary) },
                enabled = !busy,
                onClick = {
                    open = false
                    onReselect()
                }
            )
        }
    }
}

@Composable
private fun ProgressBanner(text: String) {
    val colors = Modern.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = ModernDimens.screenPadding)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = colors.accent,
            trackColor = colors.divider,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = Modern.type.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MessageBanner(banner: LibraryBanner, onDismiss: () -> Unit) {
    val colors = Modern.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ModernDimens.screenPadding, vertical = 4.dp)
            .clip(RoundedCornerShape(ModernDimens.miniPlayer))
            .background(colors.surfaceHigh)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (banner.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = if (banner.isError) "Error" else null,
            tint = if (banner.isError) colors.accent else colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = banner.text,
            style = Modern.type.rowSubtitle,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        V2IconButton(icon = Icons.Outlined.Close, contentDescription = "Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun EmptyLibrary(filter: LibraryFilter, onAddMusic: () -> Unit, onImportFolder: () -> Unit) {
    val colors = Modern.colors
    val (title, body, action) = when (filter) {
        LibraryFilter.PLAYLISTS -> Triple(
            "No playlists yet",
            "Import an SD-card folder and it becomes a playlist you can sync.",
            "Add music"
        )
        LibraryFilter.FOLDERS -> Triple(
            "No SD-card folders yet",
            "Pick a folder on your SD card. Its songs become a playlist you can sync.",
            "Import SD-card folder"
        )
        LibraryFilter.SONGS -> Triple("No songs yet", "Add music to see every song here.", "Add music")
        LibraryFilter.ARTISTS -> Triple("No artists yet", "Artists appear once you add music.", "Add music")
        LibraryFilter.ALBUMS -> Triple("No albums yet", "Albums appear once you add music.", "Add music")
    }
    val onAction = if (filter == LibraryFilter.FOLDERS) onImportFolder else onAddMusic

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = Modern.type.sectionTitle, color = colors.textPrimary, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = body, style = Modern.type.rowSubtitle, color = colors.textSecondary, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .heightIn(min = ModernDimens.touchEffective)
                .clip(RoundedCornerShape(ModernDimens.touchEffective / 2))
                .background(colors.chipSelected)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = action, style = Modern.type.cardTitle, color = colors.chipSelectedText)
        }
    }
}
