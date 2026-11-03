package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity

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
    modifier: Modifier = Modifier
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }

    val selectedPlaylist = playlists.firstOrNull {
        it.id == selectedPlaylistId
    }

    val syncingPlaylistId = (folderOperation as? FolderOperationState.Running)
        ?.takeIf { it.kind == FolderOperationKind.SYNC }
        ?.playlistId

    val folderFailure = inlineFolderFailure(folderOperation)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedPlaylist == null) {
            StereoButton(
                text = "IMPORT FOLDER FROM SD CARD",
                onClick = onImportFolder,
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Import a folder from the SD card" }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "PLAYLISTS",
                    color = StereoColors.Green,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )

                StereoButton(
                    text = "NEW LIST",
                    onClick = { creating = true }
                )
            }

            if (playlists.isEmpty()) {
                StereoEmptyState(
                    message = "No playlists yet. Import a folder from your SD card or create one.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = playlists,
                        key = { _, playlist -> playlist.id }
                    ) { _, playlist ->
                        val folderLinked = playlist.id in folderLinkedIds

                        Surface(
                            color = StereoColors.Panel,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = playlist.name,
                                    color = StereoColors.Amber,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (folderLinked) {
                                    SdFolderLabel()
                                }

                                if (folderFailure?.playlistId == playlist.id) {
                                    FolderProblem(
                                        message = folderFailure.message,
                                        onReselect = { onReselectFolder(playlist.id) },
                                        onDismiss = onDismissFolderProblem
                                    )
                                }

                                Row {
                                    TextButton(
                                        onClick = {
                                            onSelectPlaylist(playlist.id)
                                        }
                                    ) {
                                        Text("OPEN")
                                    }

                                    TextButton(
                                        onClick = { renaming = playlist }
                                    ) {
                                        Text("RENAME")
                                    }

                                    TextButton(
                                        onClick = { deleting = playlist }
                                    ) {
                                        Text("DELETE")
                                    }

                                    if (folderLinked) {
                                        SyncTextButton(
                                            playlistName = playlist.name,
                                            syncing = syncingPlaylistId == playlist.id,
                                            enabled = !loading,
                                            onClick = { onSyncFolder(playlist.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StereoButton(
                    text = "BACK",
                    onClick = { onSelectPlaylist(null) }
                )

                Text(
                    text = selectedPlaylist.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp),
                    color = StereoColors.Amber,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (selectedPlaylist.id in folderLinkedIds) {
                    val syncing = syncingPlaylistId == selectedPlaylist.id

                    StereoButton(
                        text = if (syncing) "SYNCING…" else "SYNC",
                        onClick = { onSyncFolder(selectedPlaylist.id) },
                        enabled = !loading,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Sync ${selectedPlaylist.name} with its SD-card folder"
                        }
                    )
                }

                StereoButton(
                    text = "PLAY ALL",
                    onClick = { onPlay(0) },
                    enabled = selectedSongs.isNotEmpty()
                )
            }

            if (folderFailure?.playlistId == selectedPlaylist.id) {
                FolderProblem(
                    message = folderFailure.message,
                    onReselect = { onReselectFolder(selectedPlaylist.id) },
                    onDismiss = onDismissFolderProblem
                )
            }

            if (selectedSongs.isEmpty()) {
                StereoEmptyState(
                    message = "This playlist is empty. Add songs from Library.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = selectedSongs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        Surface(
                            color = StereoColors.Panel,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${song.title}",
                                    color = StereoColors.Amber,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text = song.artist,
                                    color = StereoColors.Text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Row {
                                    TextButton(onClick = { onPlay(index) }) {
                                        Text("PLAY")
                                    }

                                    TextButton(
                                        enabled = index > 0,
                                        onClick = {
                                            val ids = selectedSongs
                                                .map { it.id }
                                                .toMutableList()
                                            val previous = ids[index - 1]
                                            ids[index - 1] = ids[index]
                                            ids[index] = previous
                                            onReorder(selectedPlaylist.id, ids)
                                        }
                                    ) {
                                        Text("UP")
                                    }

                                    TextButton(
                                        enabled = index < selectedSongs.lastIndex,
                                        onClick = {
                                            val ids = selectedSongs
                                                .map { it.id }
                                                .toMutableList()
                                            val next = ids[index + 1]
                                            ids[index + 1] = ids[index]
                                            ids[index] = next
                                            onReorder(selectedPlaylist.id, ids)
                                        }
                                    ) {
                                        Text("DOWN")
                                    }

                                    TextButton(
                                        onClick = {
                                            onRemoveSong(
                                                selectedPlaylist.id,
                                                song.id
                                            )
                                        }
                                    ) {
                                        Text("REMOVE")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

@Composable
private fun SdFolderLabel() {
    Text(
        text = "SD FOLDER",
        color = StereoColors.Green,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun SyncTextButton(
    playlistName: String,
    syncing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !syncing,
        modifier = Modifier.semantics {
            contentDescription = "Sync $playlistName with its SD-card folder"
        }
    ) {
        Text(if (syncing) "SYNCING…" else "SYNC")
    }
}

/** Folder access was lost or the card is missing; offer to pick it again. */
@Composable
private fun FolderProblem(
    message: String,
    onReselect: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StereoMessage(
            message = message,
            isError = true,
            onDismiss = onDismiss
        )

        StereoButton(
            text = "RE-SELECT FOLDER",
            onClick = onReselect,
            modifier = Modifier.fillMaxWidth()
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
