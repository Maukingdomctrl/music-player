package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity

@Composable
fun LibraryScreen(
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    loading: Boolean,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onPlay: (Int) -> Unit,
    onEdit: (SongEntity) -> Unit,
    onRemove: (String) -> Unit,
    onAddToPlaylist: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var playlistSong by remember { mutableStateOf<SongEntity?>(null) }
    var removalSong by remember { mutableStateOf<SongEntity?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StereoButton(
                text = "SCAN DEVICE",
                onClick = onScan,
                enabled = !loading,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = "SELECT FILES",
                onClick = onImport,
                enabled = !loading,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "LIBRARY  •  ${songs.size} TRACKS",
            color = StereoColors.Green,
            style = MaterialTheme.typography.labelLarge
        )

        if (songs.isEmpty()) {
            StereoEmptyState(
                message = if (loading) {
                    "Reading local music…"
                } else {
                    "No songs yet. Scan the device or select audio files."
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    LibrarySongCard(
                        song = song,
                        onPlay = { onPlay(index) },
                        onEdit = { onEdit(song) },
                        onAdd = { playlistSong = song },
                        onRemove = { removalSong = song }
                    )
                }
            }
        }
    }

    playlistSong?.let { song ->
        AlertDialog(
            onDismissRequest = { playlistSong = null },
            title = { Text("Add to playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("Create a playlist first.")
                } else {
                    LazyColumn {
                        itemsIndexed(
                            items = playlists,
                            key = { _, playlist -> playlist.id }
                        ) { _, playlist ->
                            TextButton(
                                onClick = {
                                    onAddToPlaylist(playlist.id, song.id)
                                    playlistSong = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = playlist.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistSong = null }) {
                    Text("CLOSE")
                }
            }
        )
    }

    removalSong?.let { song ->
        AlertDialog(
            onDismissRequest = { removalSong = null },
            title = { Text("Remove from library?") },
            text = {
                Text(
                    "\"${song.title}\" will be removed from the app library " +
                        "and its playlists. The original file will not be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(song.id)
                        removalSong = null
                    }
                ) {
                    Text("REMOVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { removalSong = null }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
private fun LibrarySongCard(
    song: SongEntity,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = StereoColors.Panel,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = song.title,
                color = StereoColors.Amber,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "${song.artist}  •  ${song.album}",
                color = StereoColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatPlaybackTime(song.durationMs),
                color = StereoColors.Green,
                style = MaterialTheme.typography.labelLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onPlay) { Text("PLAY") }
                TextButton(onClick = onEdit) { Text("EDIT") }
                TextButton(onClick = onAdd) { Text("ADD") }
                TextButton(onClick = onRemove) { Text("REMOVE") }
            }
        }
    }
}
