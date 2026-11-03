package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HarmanSimulatorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val simulator = remember { HarmanSimulatorState() }
    val state = simulator.state

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "TATA PUNCH EV HARMAN",
            color = StereoColors.Amber,
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "DIAGNOSTIC SIMULATOR",
            color = StereoColors.Green,
            style = MaterialTheme.typography.titleMedium
        )

        StereoMessage(
            message = "SIMULATION ONLY. This screen does not connect to a " +
                "vehicle or test actual Bluetooth or Harman compatibility.",
            isError = true,
            onDismiss = null
        )

        Text(
            text = "SIMULATED BLUETOOTH: ${state.connectionLabel}",
            color = if (state.connected) {
                StereoColors.Green
            } else {
                StereoColors.Amber
            },
            style = MaterialTheme.typography.labelLarge
        )

        StereoDisplay(
            title = state.title,
            artist = state.artist,
            album = state.album
        )

        Text(
            text = "SIMULATED PLAYBACK: ${if (state.playing) "PLAYING" else "PAUSED"}",
            color = StereoColors.Green,
            style = MaterialTheme.typography.labelLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StereoButton(
                text = "CONNECT",
                onClick = simulator::connect,
                enabled = !state.connected,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = "DISCONNECT",
                onClick = simulator::disconnect,
                enabled = state.connected,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StereoButton(
                text = "PREVIOUS",
                onClick = simulator::previous,
                enabled = state.connected,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = if (state.playing) "PAUSE" else "PLAY",
                onClick = simulator::togglePlayPause,
                enabled = state.connected,
                modifier = Modifier.weight(1f)
            )

            StereoButton(
                text = "NEXT",
                onClick = simulator::next,
                enabled = state.connected,
                modifier = Modifier.weight(1f)
            )
        }

        StereoButton(
            text = "SIMULATE INTERRUPTION",
            onClick = simulator::interrupt,
            enabled = state.connected,
            modifier = Modifier.fillMaxWidth()
        )

        StereoButton(
            text = "RECONNECT",
            onClick = simulator::reconnect,
            enabled = !state.connected,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "SIMULATED EVENT LOG",
            color = StereoColors.Green,
            style = MaterialTheme.typography.labelLarge
        )

        if (state.events.isEmpty()) {
            Text(
                text = "No simulated events yet.",
                color = StereoColors.MutedText,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            state.events.forEach { event ->
                Text(
                    text = event,
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = StereoColors.Text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        StereoButton(
            text = "CLEAR SIMULATED LOG",
            onClick = simulator::clearEvents,
            modifier = Modifier.fillMaxWidth()
        )

        StereoButton(
            text = "BACK TO PLAYER",
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )
    }
}
