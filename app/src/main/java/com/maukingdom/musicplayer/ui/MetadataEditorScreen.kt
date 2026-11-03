package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMetadata

@Composable
fun MetadataEditorScreen(
    song: SongEntity,
    onSave: (SongMetadata) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var album by rememberSaveable(song.id) { mutableStateOf(song.album) }
    var genre by rememberSaveable(song.id) {
        mutableStateOf(song.genre.orEmpty())
    }
    var year by rememberSaveable(song.id) {
        mutableStateOf(song.year?.toString().orEmpty())
    }

    val trimmedYear = year.trim()
    val parsedYear = trimmedYear.toIntOrNull()

    val titleValid = title.isNotBlank()
    val yearValid = trimmedYear.isEmpty() ||
        (parsedYear != null && parsedYear in 1..9999)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "EDIT TRACK INFORMATION",
            color = StereoColors.Green,
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Corrections are stored in the app. The original audio file is not changed.",
            color = StereoColors.Text,
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Song title") },
            isError = !titleValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!titleValid) {
            Text(
                text = "A title is required.",
                color = StereoColors.Error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("Singer / artist") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = album,
            onValueChange = { album = it },
            label = { Text("Album / movie") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = genre,
            onValueChange = { genre = it },
            label = { Text("Genre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = year,
            onValueChange = { year = it },
            label = { Text("Year") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !yearValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!yearValid) {
            Text(
                text = "Enter a year from 1 to 9999, or leave it blank.",
                color = StereoColors.Error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = "Duration: ${formatPlaybackTime(song.durationMs)}",
            color = StereoColors.MutedText,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Artwork is read from the source when available.",
            color = StereoColors.MutedText,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StereoButton(
                text = "CANCEL",
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = "SAVE",
                enabled = titleValid && yearValid,
                illuminated = true,
                onClick = {
                    onSave(
                        SongMetadata(
                            title = title.trim(),
                            artist = artist.trim(),
                            album = album.trim(),
                            genre = genre.trim().ifEmpty { null },
                            year = parsedYear
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
