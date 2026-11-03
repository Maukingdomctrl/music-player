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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity

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
    modifier: Modifier = Modifier
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }

    val selectedPlaylist = playlists.firstOrNull {
        it.id == selectedPlaylistId
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedPlaylist == null) {
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
                    message = "No playlists yet. Create one to get started.",
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

                StereoButton(
                    text = "PLAY ALL",
                    onClick = { onPlay(0) },
                    enabled = selectedSongs.isNotEmpty()
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
