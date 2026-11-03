package com.maukingdom.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.maukingdom.musicplayer.MainActivity
import com.maukingdom.musicplayer.R
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.PlaybackSnapshot
import com.maukingdom.musicplayer.data.SongEntity
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns playback independently of the activity lifecycle.
 *
 * ExoPlayer and MediaSession are accessed on the main thread. Playback
 * snapshots are written sequentially by a separate IO coroutine.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHECKPOINT_INTERVAL_MS = 5_000L

        /**
         * Send this Media3 custom session command to explicitly clear both
         * the player queue and the saved queue.
         *
         * The command is made available only to controllers running under
         * this app's UID.
         */
        const val ACTION_CLEAR_QUEUE =
            "com.maukingdom.musicplayer.action.CLEAR_QUEUE"

        private val mutableRecoveryNotice = MutableStateFlow<String?>(null)

        /**
         * An in-process message for the UI when saved queue entries could not
         * be restored. This is not a persistent diagnostic history.
         */
        val recoveryNotice: StateFlow<String?> =
            mutableRecoveryNotice.asStateFlow()

        fun clearRecoveryNotice() {
            mutableRecoveryNotice.value = null
        }
    }

    private val mainScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /*
     * This scope is intentionally separate from mainScope. Closing the
     * channel in onDestroy lets queued writes finish while the process lives.
     */
    private val writerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /*
     * Writes are processed in order. Unlike a conflated channel, this also
     * permits an explicit-clear request to receive a completion result.
     */
    private val snapshotChannel =
        Channel<SnapshotWrite>(Channel.UNLIMITED)

    private lateinit var player: ExoPlayer
    private lateinit var preferences: PlaybackPreferences
    private lateinit var repository: MusicRepository

    private var mediaSession: MediaSession? = null
    private var restorationJob: Job? = null

    private var restorationInProgress = true
    private var changedDuringRestoration = false
    private var preserveSavedSnapshot = false
    private var destroyed = false

    private val clearQueueCommand = SessionCommand(
        ACTION_CLEAR_QUEUE,
        Bundle.EMPTY
    )

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (restorationInProgress) {
                changedDuringRestoration = true
                return
            }

            /*
             * If all saved files were unavailable, do not overwrite the
             * saved queue with an empty queue merely because the service
             * started. A later actual nonempty queue replaces it.
             */
            if (preserveSavedSnapshot && player.mediaItemCount == 0) {
                return
            }

            if (player.mediaItemCount > 0) {
                preserveSavedSnapshot = false
            }

            enqueueSnapshot()
        }
    }

    private val sessionCallback = object : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val builder =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)

            /*
             * Continue accepting external media controllers with the default
             * commands. The destructive custom command is available only to
             * controllers under this application's UID.
             */
            if (controller.uid == Process.myUid()) {
                val commands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(clearQueueCommand)
                        .build()

                builder.setAvailableSessionCommands(commands)
            }

            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_CLEAR_QUEUE) {
                return completedResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }

            /*
             * Check again at execution time rather than relying only on the
             * commands advertised when the controller connected.
             */
            if (controller.uid != Process.myUid()) {
                return completedResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            }

            if (destroyed) {
                return completedResult(SessionResult.RESULT_ERROR_SESSION_DISCONNECTED)
            }

            return clearQueueExplicitly()
        }
    }

    override fun onCreate() {
        super.onCreate()

        preferences = PlaybackPreferences(applicationContext)
        repository = MusicRepository(
            MusicDatabase.getInstance(applicationContext)
        )

        writerScope.launch {
            for (request in snapshotChannel) {
                try {
                    preferences.savePlaybackSnapshot(request.snapshot)
                    request.result?.set(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Could not save playback snapshot.", error)
                    request.result?.set(
                        SessionResult(SessionResult.RESULT_ERROR_IO)
                    )
                }
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification)
            }

        setMediaNotificationProvider(notificationProvider)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .build()

        restorePlayback()

        mainScope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)

                if (!restorationInProgress &&
                    !(preserveSavedSnapshot && player.mediaItemCount == 0)
                ) {
                    enqueueSnapshot()
                }
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        return mediaSession
    }

    /**
     * Handles the explicit clear as one service-side operation.
     *
     * Canceling restoration prevents a pending IO result from subsequently
     * putting the old queue back into the player. The returned future is
     * completed after the empty snapshot has been written, or with an error
     * if that write fails.
     */
    private fun clearQueueExplicitly(): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()

        try {
            restorationJob?.cancel()
            restorationJob = null

            restorationInProgress = false
            changedDuringRestoration = true
            preserveSavedSnapshot = false

            player.pause()
            player.clearMediaItems()

            mutableRecoveryNotice.value = null

            val emptySnapshot = PlaybackSnapshot(
                queueSongIds = emptyList(),
                selectedIndex = 0,
                positionMs = 0L,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                shuffleOrder = emptyList()
            )

            val sent = snapshotChannel.trySend(
                SnapshotWrite(
                    snapshot = emptySnapshot,
                    result = result
                )
            )

            if (sent.isFailure) {
                result.set(
                    SessionResult(SessionResult.RESULT_ERROR_SESSION_DISCONNECTED)
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not clear playback queue.", error)
            result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
        }

        return result
    }

    private fun completedResult(code: Int): ListenableFuture<SessionResult> {
        val result = SettableFuture.create<SessionResult>()
        result.set(SessionResult(code))
        return result
    }

    private fun restorePlayback() {
        restorationJob = mainScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    loadRestorableQueue()
                }

                if (destroyed) return@launch

                /*
                 * Do not replace a queue or playback command that arrived
                 * while Room and file access were being checked.
                 */
                if (changedDuringRestoration || player.mediaItemCount > 0) {
                    restorationInProgress = false
                    enqueueSnapshot()
                    return@launch
                }

                val saved = result.snapshot

                if (result.missingCount > 0) {
                    mutableRecoveryNotice.value =
                        "${result.missingCount} saved queue item(s) could not " +
                                "be restored. Check file access and permissions."
                } else {
                    mutableRecoveryNotice.value = null
                }

                if (result.songs.isEmpty()) {
                    preserveSavedSnapshot = saved.queueSongIds.isNotEmpty()

                    player.shuffleModeEnabled = saved.shuffleEnabled
                    player.repeatMode = saved.repeatMode

                    restorationInProgress = false
                    return@launch
                }

                val originalSelectedIndex = saved.selectedIndex.coerceIn(
                    0,
                    saved.queueSongIds.lastIndex
                )

                /*
                 * Prefer the saved item. If it is unavailable, select the
                 * next available original queue position, or the last
                 * available one if none follows it.
                 */
                val restoredSelectedIndex =
                    result.originalIndices.indexOf(originalSelectedIndex)
                        .takeIf { it >= 0 }
                        ?: result.originalIndices.indexOfFirst {
                            it > originalSelectedIndex
                        }.takeIf { it >= 0 }
                        ?: result.originalIndices.lastIndex

                val selectedOriginalIndex =
                    result.originalIndices[restoredSelectedIndex]

                val restoredPosition = if (
                    selectedOriginalIndex == originalSelectedIndex
                ) {
                    val knownDuration =
                        result.songs[restoredSelectedIndex].durationMs

                    if (knownDuration > 0L) {
                        saved.positionMs.coerceIn(0L, knownDuration)
                    } else {
                        saved.positionMs.coerceAtLeast(0L)
                    }
                } else {
                    0L
                }

                val items = result.songs.map { song ->
                    MediaItemMapper.fromSong(song)
                }

                player.setMediaItems(
                    items,
                    restoredSelectedIndex,
                    restoredPosition
                )

                applySavedShuffleOrder(
                    savedOrder = saved.shuffleOrder,
                    restoredSongs = result.songs
                )

                player.shuffleModeEnabled = saved.shuffleEnabled
                player.repeatMode = saved.repeatMode
                player.playWhenReady = false
                player.prepare()

                restorationInProgress = false
                enqueueSnapshot()
            } catch (error: CancellationException) {
                /*
                 * An explicit clear cancels restoration. Do not turn that
                 * cancellation into a recovery failure or write the old queue.
                 */
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not restore playback.", error)

                if (!destroyed) {
                    mutableRecoveryNotice.value =
                        "The saved playback queue could not be restored."
                    preserveSavedSnapshot = true
                    restorationInProgress = false
                }
            }
        }
    }

    /**
     * Runs on Dispatchers.IO.
     */
    private suspend fun loadRestorableQueue(): RecoveryResult {
        val snapshot = preferences.getPlaybackSnapshot()

        if (snapshot.queueSongIds.isEmpty()) {
            return RecoveryResult(
                snapshot = snapshot,
                songs = emptyList(),
                originalIndices = emptyList(),
                missingCount = 0
            )
        }

        val songsById = repository.getAllSongs().associateBy { it.id }

        /*
         * Cache access checks so a repeated queue item is checked once.
         */
        val accessById = mutableMapOf<String, Boolean>()
        val songs = mutableListOf<SongEntity>()
        val originalIndices = mutableListOf<Int>()
        var missingCount = 0

        snapshot.queueSongIds.forEachIndexed { index, songId ->
            val song = songsById[songId]

            val accessible = if (song == null) {
                false
            } else {
                accessById.getOrPut(songId) {
                    canOpen(song.uri)
                }
            }

            if (song != null && accessible) {
                songs += song
                originalIndices += index
            } else {
                missingCount += 1
            }
        }

        return RecoveryResult(
            snapshot = snapshot,
            songs = songs,
            originalIndices = originalIndices,
            missingCount = missingCount
        )
    }

    private fun canOpen(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)

            if (uri.scheme != "content") {
                false
            } else {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    true
                } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Restores an explicit shuffled traversal order when the saved order
     * can be matched to the available queue. Missing entries are skipped.
     * If the saved order is invalid, ExoPlayer keeps its generated order.
     */
    private fun applySavedShuffleOrder(
        savedOrder: List<String>,
        restoredSongs: List<SongEntity>
    ) {
        if (savedOrder.isEmpty() || restoredSongs.isEmpty()) return

        val indicesById = mutableMapOf<String, ArrayDeque<Int>>()

        restoredSongs.forEachIndexed { index, song ->
            indicesById.getOrPut(song.id) { ArrayDeque() }.addLast(index)
        }

        val shuffledIndices = mutableListOf<Int>()

        for (songId in savedOrder) {
            val indices = indicesById[songId] ?: continue

            if (indices.isNotEmpty()) {
                shuffledIndices += indices.removeFirst()
            }
        }

        if (shuffledIndices.size != restoredSongs.size) return

        player.setShuffleOrder(
            ShuffleOrder.DefaultShuffleOrder(
                shuffledIndices.toIntArray(),
                System.currentTimeMillis()
            )
        )
    }

    /**
     * Must be called on the player's main application thread.
     */
    private fun enqueueSnapshot() {
        if (destroyed || restorationInProgress) return

        val sent = snapshotChannel.trySend(
            SnapshotWrite(snapshot = captureSnapshot())
        )

        if (sent.isFailure) {
            Log.e(TAG, "Could not enqueue playback snapshot.")
        }
    }

    private fun captureSnapshot(): PlaybackSnapshot {
        val queueIds = buildList {
            for (index in 0 until player.mediaItemCount) {
                add(player.getMediaItemAt(index).mediaId)
            }
        }

        val selectedIndex = if (queueIds.isEmpty()) {
            0
        } else {
            player.currentMediaItemIndex.coerceIn(0, queueIds.lastIndex)
        }

        return PlaybackSnapshot(
            queueSongIds = queueIds,
            selectedIndex = selectedIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            shuffleOrder = readShuffleOrder(queueIds)
        )
    }

    private fun readShuffleOrder(queueIds: List<String>): List<String> {
        val timeline = player.currentTimeline

        if (timeline.isEmpty || timeline.windowCount != queueIds.size) {
            return emptyList()
        }

        val result = mutableListOf<String>()
        var windowIndex = timeline.getFirstWindowIndex(true)

        while (windowIndex != C.INDEX_UNSET &&
            result.size < queueIds.size
        ) {
            result += queueIds[windowIndex]

            windowIndex = timeline.getNextWindowIndex(
                windowIndex,
                Player.REPEAT_MODE_OFF,
                true
            )
        }

        return if (result.size == queueIds.size) {
            result
        } else {
            emptyList()
        }
    }

    override fun onDestroy() {
        /*
         * Capture before releasing the player. DataStore writes are
         * asynchronous; periodic checkpoints reduce loss if the process is
         * killed without this callback.
         */
        if (!restorationInProgress &&
            !(preserveSavedSnapshot && player.mediaItemCount == 0)
        ) {
            snapshotChannel.trySend(
                SnapshotWrite(snapshot = captureSnapshot())
            )
        }

        destroyed = true
        mainScope.cancel()

        player.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        player.release()

        snapshotChannel.close()

        super.onDestroy()
    }

    private data class SnapshotWrite(
        val snapshot: PlaybackSnapshot,
        val result: SettableFuture<SessionResult>? = null
    )

    private data class RecoveryResult(
        val snapshot: PlaybackSnapshot,
        val songs: List<SongEntity>,
        val originalIndices: List<Int>,
        val missingCount: Int
    )
}
