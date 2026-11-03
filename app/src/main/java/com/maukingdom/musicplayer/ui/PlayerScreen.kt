package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun PlayerScreen(
    playback: PlaybackUiState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StereoDisplay(
            title = playback.title,
            artist = playback.artist,
            album = playback.album
        )

        Text(
            text = when {
                !playback.connected -> "CONNECTING TO PLAYER"
                playback.queueSize == 0 -> "SELECT A TRACK FROM LIBRARY"
                playback.isPlaying -> "PLAYING"
                else -> "PAUSED"
            },
            color = StereoColors.Green,
            style = MaterialTheme.typography.labelLarge
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
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StereoButton(
                text = "PREV",
                onClick = onPrevious,
                enabled = playback.queueSize > 0,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = if (playback.isPlaying) "PAUSE" else "PLAY",
                onClick = onPlayPause,
                enabled = playback.connected && playback.queueSize > 0,
                illuminated = playback.isPlaying,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = "NEXT",
                onClick = onNext,
                enabled = playback.queueSize > 0,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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

        if (playback.queueSize > 0) {
            Text(
                text = "TRACK ${playback.currentIndex + 1} / ${playback.queueSize}",
                modifier = Modifier.padding(bottom = 8.dp),
                color = StereoColors.MutedText,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
internal fun PlaybackSeekControl(
    playback: PlaybackUiState,
    onSeek: (Long) -> Unit
) {
    val duration = playback.durationMs.coerceAtLeast(0L)
    val position = playback.positionMs.coerceAtLeast(0L)

    val progress = if (duration > 0L) {
        (position.toDouble() / duration.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Column {
        Slider(
            value = progress,
            onValueChange = { fraction ->
                if (duration > 0L) {
                    onSeek((fraction.toDouble() * duration).toLong())
                }
            },
            enabled = duration > 0L,
            colors = SliderDefaults.colors(
                thumbColor = StereoColors.Amber,
                activeTrackColor = StereoColors.Amber,
                inactiveTrackColor = StereoColors.DisplayBorder
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatPlaybackTime(position),
                color = StereoColors.Amber,
                style = MaterialTheme.typography.labelLarge
            )

            Text(
                text = formatPlaybackTime(duration),
                color = StereoColors.Amber,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

internal fun repeatLabel(repeatMode: Int): String {
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "REPEAT ONE"
        Player.REPEAT_MODE_ALL -> "REPEAT ALL"
        else -> "REPEAT OFF"
    }
}
