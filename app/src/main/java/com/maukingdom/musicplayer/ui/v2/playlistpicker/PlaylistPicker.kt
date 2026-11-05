package com.maukingdom.musicplayer.ui.v2.playlistpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.data.PlaylistEdit
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.cover.LivingCover
import com.maukingdom.musicplayer.ui.v2.cover.LocalCoverCandidates
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val IconWell = Color(0xFF2A2A32)
private val SearchHeight = 44.dp

/** Opens the Add / Move to playlist sheet. Provided by [PlaylistPickerHost]. */
@Stable
class PlaylistPicker(private val onOpen: (PickerRequest) -> Unit) {
    fun open(request: PickerRequest) = onOpen(request)

    fun add(songIds: List<String>) {
        if (songIds.isNotEmpty()) open(PickerRequest(PickerMode.ADD, songIds))
    }

    fun move(fromPlaylistId: Long, songIds: List<String>) {
        if (songIds.isNotEmpty()) open(PickerRequest(PickerMode.MOVE, songIds, fromPlaylistId))
    }
}

/** No-op outside Modern (and in previews). */
val LocalPlaylistPicker = staticCompositionLocalOf { PlaylistPicker {} }

/** Where the sheet's flow is, saved across rotation. */
private enum class PickerStage { SHEET, NEW_PLAYLIST, CONFIRM_MOVE }

private data class PickerSession(
    val request: PickerRequest,
    val stage: PickerStage = PickerStage.SHEET,
    /** CONFIRM_MOVE: the chosen target; -1 when it is a new playlist named [newName]. */
    val targetId: Long = -1L,
    val newName: String = ""
)

private val SessionSaver = Saver<PickerSession?, ArrayList<Any>>(
    save = { session ->
        session?.let {
            arrayListOf(
                it.request.mode.name,
                ArrayList(it.request.songIds),
                it.request.sourcePlaylistId ?: -1L,
                it.stage.name,
                it.targetId,
                it.newName
            )
        } ?: arrayListOf()
    },
    restore = { saved ->
        if (saved.size < 6) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            PickerSession(
                request = PickerRequest(
                    mode = PickerMode.valueOf(saved[0] as String),
                    songIds = (saved[1] as List<String>).toList(),
                    sourcePlaylistId = (saved[2] as Long).takeIf { it >= 0L }
                ),
                stage = PickerStage.valueOf(saved[3] as String),
                targetId = saved[4] as Long,
                newName = saved[5] as String
            )
        }
    }
)

/**
 * Provides [LocalPlaylistPicker] and [LocalSongQueue] to [content] and owns the sheet, its
 * dialogs and the result snackbar (drawn over [content], [snackbarBottomPadding]
 * above the bottom edge). Also offers a "Select files" import's songs.
 */
@Composable
fun PlaylistPickerHost(
    musicViewModel: MusicViewModel,
    snackbarBottomPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var session by rememberSaveable(stateSaver = SessionSaver) { mutableStateOf<PickerSession?>(null) }
    val picker = remember { PlaylistPicker { request -> session = PickerSession(request) } }
    val songQueue = remember(musicViewModel) { SongQueue(musicViewModel::playNext, musicViewModel::addToQueue) }
    val favouriteIds = musicViewModel.favouriteSongIds.collectAsStateWithLifecycle()
    val favourites = remember(musicViewModel, favouriteIds) {
        FavouriteActions(favouriteIds, musicViewModel::addToFavourites, musicViewModel::removeFromFavourites)
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(musicViewModel) {
        musicViewModel.playlistEditResults.collect { result ->
            launch { showEditSnackbar(snackbar, result, musicViewModel::undoPlaylistEdit) }
        }
    }

    LaunchedEffect(musicViewModel) {
        musicViewModel.queueEditResults.collect { result ->
            launch {
                val undo = result.undo
                val choice = snackbar.showSnackbar(
                    message = result.message,
                    actionLabel = if (undo != null) "Undo" else null,
                    duration = SnackbarDuration.Short
                )
                if (choice == SnackbarResult.ActionPerformed && undo != null) musicViewModel.undoQueueEdit(undo)
            }
        }
    }

    // Consuming the offer restarts this effect, so the snackbar runs in the host's scope.
    val scope = rememberCoroutineScope()
    val offer by musicViewModel.importOffer.collectAsStateWithLifecycle()
    LaunchedEffect(offer) {
        val current = offer ?: return@LaunchedEffect
        musicViewModel.consumeImportOffer()
        scope.launch {
            val choice = snackbar.showSnackbar(
                message = current.message,
                actionLabel = "Add to playlist",
                duration = SnackbarDuration.Long
            )
            if (choice == SnackbarResult.ActionPerformed) picker.add(current.songIds)
        }
    }

    CompositionLocalProvider(
        LocalPlaylistPicker provides picker,
        LocalSongQueue provides songQueue,
        LocalFavourites provides favourites
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            content()

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = snackbarBottomPadding)
                    .padding(horizontal = 8.dp)
            ) { data ->
                ModernSnackbar(
                    message = data.visuals.message,
                    actionLabel = data.visuals.actionLabel,
                    onAction = data::performAction
                )
            }
        }
    }

    session?.let { current ->
        PickerFlow(
            session = current,
            musicViewModel = musicViewModel,
            onSessionChange = { session = it }
        )
    }
}

private suspend fun showEditSnackbar(
    host: SnackbarHostState,
    result: PlaylistEditResult,
    onUndo: (PlaylistEdit) -> Unit
) {
    val edit = result.edit
    val choice = host.showSnackbar(
        message = result.message,
        actionLabel = if (edit != null) "Undo" else null,
        duration = SnackbarDuration.Long
    )
    if (choice == SnackbarResult.ActionPerformed && edit != null) onUndo(edit)
}

@Composable
private fun ModernSnackbar(message: String, actionLabel: String?, onAction: () -> Unit) {
    val colors = Modern.colors

    Snackbar(
        containerColor = colors.surfaceHigh,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(ModernDimens.cardCover),
        action = actionLabel?.let { label ->
            {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = ModernDimens.touchEffective)) {
                    Text(text = label, style = Modern.type.cardTitle, color = colors.accent)
                }
            }
        }
    ) {
        Text(text = message, style = Modern.type.rowSubtitle, color = colors.textPrimary, maxLines = 2)
    }
}

/** The sheet, then (when needed) the name dialog or the folder-move confirmation. */
@Composable
private fun PickerFlow(
    session: PickerSession,
    musicViewModel: MusicViewModel,
    onSessionChange: (PickerSession?) -> Unit
) {
    val request = session.request
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()

    fun nameOf(id: Long?) = playlists.firstOrNull { it.id == id }?.name.orEmpty()
    val sourceIsFolder = request.mode == PickerMode.MOVE && request.sourcePlaylistId in folderIds

    fun commit(targetId: Long, newName: String) {
        val songIds = request.songIds
        val source = request.sourcePlaylistId
        when {
            request.mode == PickerMode.ADD && targetId < 0L -> musicViewModel.addSongsToNewPlaylist(newName, songIds)
            request.mode == PickerMode.ADD -> musicViewModel.addSongsToPlaylist(targetId, songIds)
            source == null -> Unit
            targetId < 0L -> musicViewModel.moveSongsToNewPlaylist(newName, source, songIds)
            else -> musicViewModel.moveSongsToPlaylist(source, targetId, songIds)
        }
        onSessionChange(null)
    }

    /** Move out of a folder playlist asks first; everything else happens at once. */
    fun choose(targetId: Long, newName: String) {
        if (sourceIsFolder) {
            onSessionChange(session.copy(stage = PickerStage.CONFIRM_MOVE, targetId = targetId, newName = newName))
        } else {
            commit(targetId, newName)
        }
    }

    when (session.stage) {
        PickerStage.SHEET -> PlaylistPickerSheet(
            request = request,
            musicViewModel = musicViewModel,
            onNewPlaylist = { onSessionChange(session.copy(stage = PickerStage.NEW_PLAYLIST)) },
            onPick = { id -> choose(id, "") },
            onDismiss = { onSessionChange(null) }
        )

        PickerStage.NEW_PLAYLIST -> NewPlaylistDialog(
            onDismiss = { onSessionChange(session.copy(stage = PickerStage.SHEET)) },
            onCreate = { name -> choose(-1L, name) }
        )

        PickerStage.CONFIRM_MOVE -> {
            val target = if (session.targetId < 0L) session.newName else nameOf(session.targetId)
            val count = request.songIds.size
            ConfirmMoveDialog(
                title = if (count == 1) "Move to “$target”?" else "Move ${pickerSubtitle(count)} to “$target”?",
                text = moveFromFolderNote(nameOf(request.sourcePlaylistId), count),
                onDismiss = { onSessionChange(null) },
                onConfirm = { commit(session.targetId, session.newName) }
            )
        }
    }
}

/**
 * The Add / Move to playlist sheet for [request]. "+ New playlist" first,
 * then every playlist A–Z (searchable above [PICKER_SEARCH_THRESHOLD]).
 * Picking a row hides the sheet, then calls [onPick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    request: PickerRequest,
    musicViewModel: MusicViewModel,
    onNewPlaylist: () -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val counts by musicViewModel.playlistSongCounts.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()
    val candidates by musicViewModel.playlistCoverCandidates.collectAsStateWithLifecycle()

    // Only a single song gets ✓ marks, so only then is its membership observed.
    val singleSongId = request.songIds.singleOrNull()
    val containingFlow = remember(singleSongId) {
        singleSongId?.let(musicViewModel::observePlaylistIdsContaining) ?: flowOf(emptySet())
    }
    val containing by containingFlow.collectAsStateWithLifecycle(initialValue = emptySet())

    var query by rememberSaveable { mutableStateOf("") }
    val listedCount = remember(playlists, request) {
        playlists.count { request.mode == PickerMode.ADD || it.id != request.sourcePlaylistId }
    }
    val searchable = showPickerSearch(listedCount)
    val rows = remember(request, playlists, counts, folderIds, containing, query, searchable) {
        pickerRows(request, playlists, counts, folderIds, containing, if (searchable) query else "")
    }

    val latestPick by rememberUpdatedState(onPick)
    val latestDismiss by rememberUpdatedState(onDismiss)

    /** Animates the sheet away, then runs [then]. */
    fun hideThen(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = { latestDismiss() },
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.surfaceHigh,
        contentColor = colors.textPrimary,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = ModernDimens.sheetTop, topEnd = ModernDimens.sheetTop),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textSecondary.copy(alpha = 0.4f)) },
        // safeDrawing includes the keyboard, so the search field stays above it.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) }
    ) {
        CompositionLocalProvider(LocalCoverCandidates provides candidates) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                SheetTitle(title = pickerTitle(request.mode), subtitle = pickerSubtitle(request.songIds.size))

                if (searchable) {
                    PickerSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp)
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "new", contentType = "new") {
                        NewPlaylistRow(onClick = { hideThen(onNewPlaylist) })
                    }

                    items(rows, key = { it.id }, contentType = { "playlist" }) { row ->
                        PickerPlaylistRow(
                            row = row,
                            description = pickerRowDescription(row, request.mode),
                            onClick = { hideThen { latestPick(row.id) } }
                        )
                    }

                    if (rows.isEmpty() && query.isNotBlank()) {
                        item(key = "none", contentType = "none") {
                            Text(
                                text = "No playlists match “${query.trim()}”",
                                style = Modern.type.rowSubtitle,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = ModernDimens.screenPadding, vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    val colors = Modern.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, bottom = 8.dp)
            .semantics(mergeDescendants = true) { heading() }
    ) {
        Text(
            text = title,
            style = Modern.type.sectionTitle.copy(fontSize = 17.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp),
            color = colors.textPrimary
        )
        Text(text = subtitle, style = Modern.type.rowSubtitle, color = colors.textSecondary)
    }
}

@Composable
private fun PickerSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Modern.colors
    val style = Modern.type.rowTitle.copy(color = colors.textPrimary)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.touchEffective)
            .semantics { contentDescription = "Search playlists" },
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SearchHeight)
                    .clip(RoundedCornerShape(ModernDimens.cardCover))
                    .background(colors.chip)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(text = "Find a playlist", style = style, color = colors.textPrimary.copy(alpha = 0.5f), maxLines = 1)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(ModernDimens.touchEffective)
                            .clickable(role = Role.Button, onClickLabel = null) { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    )
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    val colors = Modern.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.songRowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ModernDimens.songRowCover)
                .clip(CircleShape)
                .background(IconWell),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(ModernDimens.iconSmall))
        }
        Spacer(modifier = Modifier.width(ModernDimens.itemGap))
        Text(text = "New playlist", style = Modern.type.playlistTitle, color = colors.textPrimary)
    }
}

/** One playlist row. Rows already holding the single song show ✓ and are disabled. */
@Composable
private fun PickerPlaylistRow(row: PickerRow, description: String, onClick: () -> Unit) {
    val colors = Modern.colors
    val enabled = !row.alreadyAdded
    val tertiary = colors.textPrimary.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.songRowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            // One announcement per row: "Add to Hindi New" / "Already in Hindi New".
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            }
            .padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LivingCover(
            playlistId = row.id,
            playlistName = row.name,
            size = ModernDimens.songRowCover,
            corner = ModernDimens.coverSmall,
            animate = false
        )

        Spacer(modifier = Modifier.width(ModernDimens.itemGap))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.name,
                style = Modern.type.playlistTitle,
                color = if (enabled) colors.textPrimary else tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (enabled) row.meta else "Already added",
                style = Modern.type.rowSubtitle,
                color = if (enabled) colors.textSecondary else tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (row.alreadyAdded) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(ModernDimens.iconSmall))
        }
    }
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val colors = Modern.colors
    var text by rememberSaveable { mutableStateOf("") }
    val trimmed = text.trim()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceHigh,
        title = { Text("New playlist", style = Modern.type.sectionTitle, color = colors.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Playlist name", style = Modern.type.rowTitle, color = colors.textPrimary.copy(alpha = 0.5f)) },
                textStyle = Modern.type.rowTitle.copy(color = colors.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty()) onCreate(trimmed) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text("Create", style = Modern.type.cardTitle, color = if (trimmed.isNotEmpty()) colors.accent else colors.textSecondary)
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
private fun ConfirmMoveDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = Modern.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceHigh,
        title = { Text(title, style = Modern.type.sectionTitle, color = colors.textPrimary) },
        text = { Text(text, style = Modern.type.rowSubtitle, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Move", style = Modern.type.cardTitle, color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = Modern.type.cardTitle, color = colors.textPrimary)
            }
        }
    )
}
