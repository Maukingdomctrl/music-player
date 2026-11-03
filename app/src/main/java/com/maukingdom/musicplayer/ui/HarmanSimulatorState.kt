package com.maukingdom.musicplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class HarmanSimulatorUiState(
    val connected: Boolean,
    val connectionLabel: String,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val events: List<String>
)

/**
 * Isolated simulator state.
 *
 * This class does not access Bluetooth, MediaSession, ExoPlayer, a vehicle,
 * or the user's real library. Its track labels are simulated display data,
 * not audio files or placeholder audio.
 */
class HarmanSimulatorState {

    private data class SimulatedTrack(
        val title: String,
        val artist: String,
        val album: String
    )

    private val tracks = listOf(
        SimulatedTrack(
            title = "Simulated Track 1",
            artist = "Simulated Singer 1",
            album = "Simulated Movie / Album 1"
        ),
        SimulatedTrack(
            title = "Simulated Track 2",
            artist = "Simulated Singer 2",
            album = "Simulated Movie / Album 2"
        ),
        SimulatedTrack(
            title = "Simulated Track 3",
            artist = "Simulated Singer 3",
            album = "Simulated Movie / Album 3"
        )
    )

    private var trackIndex = 0
    private var eventNumber = 0

    var state by mutableStateOf(
        HarmanSimulatorUiState(
            connected = false,
            connectionLabel = "DISCONNECTED",
            playing = false,
            title = tracks[0].title,
            artist = tracks[0].artist,
            album = tracks[0].album,
            events = emptyList()
        )
    )
        private set

    fun connect() {
        if (state.connected) return

        state = state.copy(
            connected = true,
            connectionLabel = "CONNECTED"
        )

        record("Bluetooth connection established.")
        recordMetadata()
    }

    fun disconnect() {
        if (!state.connected) return

        state = state.copy(
            connected = false,
            connectionLabel = "DISCONNECTED",
            playing = false
        )

        record("Bluetooth disconnected; simulated playback paused.")
    }

    fun togglePlayPause() {
        if (!state.connected) return

        val nowPlaying = !state.playing
        state = state.copy(playing = nowPlaying)

        record(if (nowPlaying) "PLAY event." else "PAUSE event.")
    }

    fun next() {
        if (!state.connected) return

        trackIndex = (trackIndex + 1) % tracks.size
        updateTrack()

        record("NEXT event.")
        recordMetadata()
    }

    fun previous() {
        if (!state.connected) return

        trackIndex = (trackIndex - 1 + tracks.size) % tracks.size
        updateTrack()

        record("PREVIOUS event.")
        recordMetadata()
    }

    fun interrupt() {
        if (!state.connected) return

        state = state.copy(
            connected = false,
            connectionLabel = "INTERRUPTED",
            playing = false
        )

        record("Connection interrupted; simulated playback paused.")
    }

    fun reconnect() {
        if (state.connected) return

        state = state.copy(
            connected = true,
            connectionLabel = "RECONNECTED",
            playing = false
        )

        record("Bluetooth reconnected; simulated playback remains paused.")
        recordMetadata()
    }

    fun clearEvents() {
        state = state.copy(events = emptyList())
    }

    private fun updateTrack() {
        val track = tracks[trackIndex]

        state = state.copy(
            title = track.title,
            artist = track.artist,
            album = track.album
        )
    }

    private fun recordMetadata() {
        record(
            "Metadata: ${state.title} | ${state.artist} | ${state.album}"
        )
    }

    private fun record(message: String) {
        eventNumber += 1

        val event = "SIMULATED #$eventNumber — $message"

        state = state.copy(
            events = (listOf(event) + state.events).take(30)
        )
    }
}
