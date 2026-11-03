package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun CarModeScreen(
    playback: PlaybackUiState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "CAR MODE",
            color = StereoColors.Green,
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Set up playback before driving. Do not operate the phone while driving.",
            color = StereoColors.Text,
            style = MaterialTheme.typography.bodyMedium
        )

        StereoDisplay(
            title = playback.title,
            artist = playback.artist,
            album = playback.album
        )

        playback.error?.let { error ->
            StereoMessage(
                message = "Playback error: $error",
                isError = true,
                onDismiss = null
            )
        }

        PlaybackSeekControl(
            playback = playback,
            onSeek = onSeek
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StereoButton(
                text = "PREVIOUS",
                onClick = onPrevious,
                enabled = playback.queueSize > 0,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp)
            )

            StereoButton(
                text = if (playback.isPlaying) "PAUSE" else "PLAY",
                onClick = onPlayPause,
                enabled = playback.connected && playback.queueSize > 0,
                illuminated = playback.isPlaying,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp)
            )

            StereoButton(
                text = "NEXT",
                onClick = onNext,
                enabled = playback.queueSize > 0,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StereoButton(
                text = if (playback.shuffleEnabled) {
                    "SHUFFLE ON"
                } else {
                    "SHUFFLE OFF"
                },
                onClick = onShuffle,
                enabled = playback.connected,
                illuminated = playback.shuffleEnabled,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = repeatLabel(playback.repeatMode),
                onClick = onRepeat,
                enabled = playback.connected,
                illuminated = playback.repeatMode != Player.REPEAT_MODE_OFF,
                modifier = Modifier.weight(1f)
            )
        }

        StereoVolumeControl(
            value = playback.volume,
            onValueChange = onVolumeChange
        )

        StereoButton(
            text = "EXIT CAR MODE",
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
        )
    }
}
