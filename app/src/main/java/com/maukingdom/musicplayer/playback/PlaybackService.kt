package com.maukingdom.musicplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.maukingdom.musicplayer.MainActivity
import com.maukingdom.musicplayer.R
import com.maukingdom.musicplayer.playback.widget.PlayerWidget
import com.maukingdom.musicplayer.playback.widget.WidgetModel
import com.maukingdom.musicplayer.playback.widget.WidgetState
import com.maukingdom.musicplayer.ui.decodeWithinBounds
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.FavouritesStore
import com.maukingdom.musicplayer.data.PlaybackSnapshot
import com.maukingdom.musicplayer.playback.visualiser.BandSmoother
import com.maukingdom.musicplayer.playback.visualiser.SpectrumAudioProcessor
import com.maukingdom.musicplayer.playback.visualiser.VisualiserBus
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns playback independently of the activity lifecycle.
 *
 * ExoPlayer and the session are accessed on the main thread. Playback
 * snapshots are written sequentially by a separate IO coroutine.
 *
 * The session is a MediaLibrarySession so media browsers such as Android
 * Auto can browse the library (see [BrowseTree]) and start playback. Items
 * that already carry a URI, as sent by this app's own UI, are passed through
 * unchanged; items without one are resolved from the library.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val WIDGET_CHANNEL_ID = "widget"
        private const val WIDGET_NOTIFICATION_ID = 7_301

        private const val NOTHING_RESOLVED =
            "None of the requested songs are in the library."

        /**
         * Send this Media3 custom session command to explicitly clear both
         * the player queue and the saved queue.
         *
         * The command is made available only to controllers running under
         * this app's UID.
         */
        const val ACTION_CLEAR_QUEUE =
            "com.maukingdom.musicplayer.action.CLEAR_QUEUE"

        /**
         * Sets the sleep timer. Argument [EXTRA_SLEEP_ARG]: minutes, or
         * SleepTimerMath.ARG_END_OF_SONG / ARG_OFF. This app's UID only.
         */
        const val ACTION_SET_SLEEP_TIMER =
            "com.maukingdom.musicplayer.action.SET_SLEEP_TIMER"
        const val EXTRA_SLEEP_ARG = "sleep_arg"

        /**
         * Adds or removes the current song from Favourites. Available to every
         * controller (it backs the heart button on the Android Auto player).
         */
        const val ACTION_TOGGLE_FAVOURITE =
            "com.maukingdom.musicplayer.action.TOGGLE_FAVOURITE"

        private val mutableSleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)

        /** The running sleep timer, for the UI. In-process only. */
        val sleepTimer: StateFlow<SleepTimerState> = mutableSleepTimer.asStateFlow()

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

        private val mutableToneControlAvailable = MutableStateFlow<Boolean?>(null)

        /**
         * Whether bass/treble can be applied: null until the service has
         * tried, false if the phone refused the equaliser.
         */
        val toneControlAvailable: StateFlow<Boolean?> =
            mutableToneControlAvailable.asStateFlow()
    }

    // Bass and treble (see ToneControl). Main thread only.
    private var equalizer: Equalizer? = null
    private var toneBassDb = 0
    private var toneTrebleDb = 0
    private var tonePreset = EqPreset.FLAT

    private val toneListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachEqualizer(audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // After a failure, try the equaliser again once per track change.
            if (equalizer == null && mutableToneControlAvailable.value == false) {
                attachEqualizer(player.audioSessionId)
            }
        }
    }

    // Beat visualiser: tells the timeline when sound is actually playing.
    private val visualiserListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            VisualiserBus.timeline.onPlayingChanged(isPlaying, System.nanoTime())
        }
    }

    /**
     * While a Studio player wants bars, about 30 times a second: takes the
     * analysed frame that is being heard now, smooths it, publishes it.
     * Paused or stalled, the bars ease down to silence. Nothing runs while
     * no one wants bars.
     */
    private fun startVisualiserPublisher() {
        mainScope.launch(Dispatchers.Default) {
            VisualiserBus.wanted.collectLatest { wanted ->
                if (!wanted) return@collectLatest

                val smoother = BandSmoother()
                var target: FloatArray? = null
                var lastTick = System.nanoTime()
                var lastFrameAt = 0L
                var publishedSilence = false

                while (isActive) {
                    delay(VISUALISER_TICK_MS)
                    val now = System.nanoTime()
                    val dt = (now - lastTick) / 1e9
                    lastTick = now

                    VisualiserBus.timeline.take(now)?.let {
                        target = it
                        lastFrameAt = now
                    }
                    val sounding = VisualiserBus.timeline.isPlaying &&
                        now - lastFrameAt < VISUALISER_STALL_NANOS
                    smoother.update(if (sounding) target else null, dt)

                    val silent = smoother.levels.all { it < 0.002f }
                    if (silent && publishedSilence) continue
                    publishedSilence = silent
                    VisualiserBus.publish(smoother.levels.copyOf())
                }
            }
        }
    }

    // Skipping unplayable files (see PlaybackRecovery). Main thread only.
    private var consecutiveSkips = 0
    private var skipResetJob: Job? = null

    private val recoveryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            recoverFromError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            skipResetJob?.cancel()
            skipResetJob = null

            if (isPlaying && consecutiveSkips > 0) {
                // A song that plays for more than 2 s resets the count.
                val startedAt = SystemClock.elapsedRealtime()
                skipResetJob = mainScope.launch {
                    delay(PlaybackRecovery.RESET_AFTER_PLAYED_MS + 1)
                    if (player.isPlaying) {
                        consecutiveSkips = PlaybackRecovery.skipsAfterPlaying(
                            consecutiveSkips,
                            SystemClock.elapsedRealtime() - startedAt
                        )
                    }
                }
            }
        }
    }

    /**
     * The last snapshot handed to the writer. Used to skip identical
     * checkpoints and to write the queue only when it changed. Main thread
     * only; cleared after a failed write so the next one is complete.
     */
    private var lastEnqueuedSnapshot: PlaybackSnapshot? = null

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

    private var mediaSession: MediaLibrarySession? = null
    private var restorationJob: Job? = null

    private var restorationInProgress = true
    private var changedDuringRestoration = false
    private var preserveSavedSnapshot = false
    private var destroyed = false

    private val clearQueueCommand = SessionCommand(
        ACTION_CLEAR_QUEUE,
        Bundle.EMPTY
    )

    private val sleepTimerCommand = SessionCommand(
        ACTION_SET_SLEEP_TIMER,
        Bundle.EMPTY
    )

    private var sleepTimer: SleepTimerController? = null

    private val favouriteCommand = SessionCommand(
        ACTION_TOGGLE_FAVOURITE,
        Bundle.EMPTY
    )

    private lateinit var favourites: FavouritesStore

    /** The current item's media id, for the heart button. Main thread. */
    private val currentMediaId = MutableStateFlow<String?>(null)

    // Home-screen widget. Main thread.
    /** The song whose cover the widget should show. */
    private val widgetSongId = MutableStateFlow<String?>(null)
    /** A widget play tap that arrived while the saved queue was still loading. */
    private var widgetPlayPending = false

    /** Pushes title / artist / play state to the widget on real changes only (never position ticks). */
    private val widgetListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                pushWidget()
            }
        }
    }

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

            currentMediaId.value = player.currentMediaItem?.mediaId
            enqueueSnapshot()
        }
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val builder =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)

            /*
             * Continue accepting external media controllers with the default
             * commands (for a library session these include the browse
             * commands). The destructive custom command is available only to
             * controllers under this application's UID. Every controller may
             * use the Favourites heart.
             */
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(favouriteCommand)

            if (controller.uid == Process.myUid()) {
                commands.add(clearQueueCommand)
                commands.add(sleepTimerCommand)
            }

            builder.setAvailableSessionCommands(commands.build())
            builder.setMediaButtonPreferences(heartButtons)

            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_FAVOURITE) {
                if (destroyed) {
                    return completedResult(SessionError.ERROR_SESSION_DISCONNECTED)
                }
                val songId = player.currentMediaItem?.mediaId
                    ?: return completedResult(SessionError.ERROR_BAD_VALUE)
                return ioFuture {
                    favourites.toggle(songId)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }

            if (customCommand.customAction != ACTION_CLEAR_QUEUE &&
                customCommand.customAction != ACTION_SET_SLEEP_TIMER
            ) {
                return completedResult(SessionError.ERROR_NOT_SUPPORTED)
            }

            /*
             * Check again at execution time rather than relying only on the
             * commands advertised when the controller connected.
             */
            if (controller.uid != Process.myUid()) {
                return completedResult(SessionError.ERROR_PERMISSION_DENIED)
            }

            if (destroyed) {
                return completedResult(SessionError.ERROR_SESSION_DISCONNECTED)
            }

            if (customCommand.customAction == ACTION_SET_SLEEP_TIMER) {
                val timer = sleepTimer
                    ?: return completedResult(SessionError.ERROR_SESSION_DISCONNECTED)
                timer.set(args.getInt(EXTRA_SLEEP_ARG, SleepTimerMath.ARG_OFF))
                return completedResult(SessionResult.RESULT_SUCCESS)
            }

            return clearQueueExplicitly()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(nodeItem(BrowseId.Root), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val parent = BrowseTree.decode(parentId) as? BrowseId.Node
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )

            return libraryFuture(
                onError = { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }
            ) {
                val children = loadChildren(parent, page, pageSize)

                if (children == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val id = BrowseTree.decode(mediaId)
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )

            return libraryFuture(
                onError = { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }
            ) {
                val item = loadItem(id)

                if (item == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            /*
             * This app's UI sends complete items with URIs. Passing them
             * through unchanged keeps the existing behaviour, including any
             * embedded artwork already attached to the selected item.
             * URIs from any other controller are never trusted: those items
             * are resolved from the library by media ID instead.
             */
            val trustUris = controller.uid == Process.myUid()

            if (trustUris && mediaItems.all { it.isReadyToPlay() }) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex,
                        startPositionMs
                    )
                )
            }

            val searchQuery = mediaItems.firstNotNullOfOrNull { it.requestMetadata.searchQuery }

            if (searchQuery != null) {
                // Blank voice query ("play MyCar Music"): resume the current queue.
                if (VoiceSearch.normalize(searchQuery).isEmpty() && player.mediaItemCount > 0) {
                    val current = List(player.mediaItemCount) { player.getMediaItemAt(it) }

                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            current,
                            player.currentMediaItemIndex,
                            player.currentPosition
                        )
                    )
                }

                return searchFuture(mediaSession, controller, searchQuery) { queue ->
                    MediaSession.MediaItemsWithStartPosition(
                        queue.items,
                        queue.startIndex,
                        C.TIME_UNSET
                    )
                }
            }

            val requested = mediaItems.toList()

            return ioFuture {
                val queue = resolveQueue(requested, startIndex, trustUris)
                    ?: throw IllegalArgumentException(NOTHING_RESOLVED)

                MediaSession.MediaItemsWithStartPosition(
                    queue.items,
                    queue.startIndex,
                    if (queue.startMoved) C.TIME_UNSET else startPositionMs
                )
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Same rule as onSetMediaItems: only this app's URIs are trusted.
            val trustUris = controller.uid == Process.myUid()

            if (trustUris && mediaItems.all { it.isReadyToPlay() }) {
                return Futures.immediateFuture(mediaItems)
            }

            val searchQuery = mediaItems.firstNotNullOfOrNull { it.requestMetadata.searchQuery }

            if (searchQuery != null) {
                // Adding never "resumes"; a blank query adds the fallback list.
                return searchFuture(mediaSession, controller, searchQuery) { queue ->
                    queue.items.toMutableList()
                }
            }

            val requested = mediaItems.toList()

            return ioFuture {
                val resolved = requested.flatMap { resolveOne(it, trustUris) }

                if (resolved.isEmpty()) {
                    throw IllegalArgumentException(NOTHING_RESOLVED)
                }

                resolved.toMutableList()
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()

            val job = mainScope.launch {
                try {
                    val count = withContext(Dispatchers.IO) { searchItems(query).size }
                    session.notifySearchResultChanged(browser, query, count, params)
                    future.set(LibraryResult.ofVoid())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Search failed.", error)
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }

            job.invokeOnCompletion {
                if (!future.isDone) future.cancel(false)
            }

            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return libraryFuture(
                onError = { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }
            ) {
                val items = BrowseTree.page(searchItems(query), page, pageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
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
                    preferences.savePlaybackSnapshot(request.snapshot, request.writeQueue)
                    request.result?.set(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Could not save playback snapshot.", error)
                    // Make the next checkpoint a complete one.
                    mainScope.launch { lastEnqueuedSnapshot = null }
                    request.result?.set(
                        SessionResult(SessionError.ERROR_IO)
                    )
                }
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Same audio sink as Media3's default, plus a pass-through tap for
        // the Modern beat visualiser (output is byte-for-byte unchanged).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(SpectrumAudioProcessor()))
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)

        player.addListener(recoveryListener)

        player.addListener(toneListener)
        attachEqualizer(player.audioSessionId)

        player.addListener(visualiserListener)

        favourites = FavouritesStore(repository, preferences)

        val timer = SleepTimerController(mainScope, player)
        sleepTimer = timer
        player.addListener(timer.listener)
        mainScope.launch { timer.state.collect { mutableSleepTimer.value = it } }

        player.addListener(widgetListener)
        // Covers are decoded off the main thread, at most 256 px, once per song.
        mainScope.launch {
            widgetSongId.collectLatest { songId ->
                val cover = if (songId == null) null else withContext(Dispatchers.IO) { loadWidgetCover(songId) }
                PlayerWidget.update(this@PlaybackService, PlayerWidget.state, cover, songId)
            }
        }
        VisualiserBus.timeline.onPlayingChanged(player.isPlaying, System.nanoTime())
        startVisualiserPublisher()

        mainScope.launch {
            preferences.appPreferences
                .map { Triple(it.bassDb, it.trebleDb, EqPreset.fromId(it.eqPresetId)) }
                .distinctUntilChanged()
                .collect { (bass, treble, preset) ->
                    toneBassDb = bass
                    toneTrebleDb = treble
                    tonePreset = preset

                    // After a failure, a knob change tries attaching again.
                    if (equalizer == null) {
                        attachEqualizer(player.audioSessionId)
                    } else {
                        applyTone()
                    }
                }
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_stat_music)
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

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()

        restorePlayback()

        // The heart on Android Auto's player (and the media notification) follows the current song.
        mainScope.launch {
            combine(favourites.songIds, currentMediaId) { favouriteIds, songId ->
                songId?.let { it in favouriteIds }
            }
                .distinctUntilChanged()
                .collect { isFavourite ->
                    heartButtons = heartButtonsFor(isFavourite)
                    mediaSession?.setMediaButtonPreferences(heartButtons)
                }
        }

        mainScope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)

                if (!restorationInProgress &&
                    !(preserveSavedSnapshot && player.mediaItemCount == 0)
                ) {
                    // Identical snapshots (paused, nothing changed) are skipped.
                    enqueueSnapshot()
                }
            }
        }
    }

    // Unplayable files

    /**
     * Skips a song whose file is missing, unreadable or unsupported, and
     * carries on if playback was intended. Nothing is removed from the
     * queue or the library.
     */
    private fun recoverFromError(error: PlaybackException) {
        if (destroyed) return

        val decision = PlaybackRecovery.decide(
            errorCode = error.errorCode,
            hasNext = player.hasNextMediaItem(),
            consecutiveSkips = consecutiveSkips
        )
        consecutiveSkips = decision.consecutiveSkips

        when (decision.action) {
            RecoveryAction.IGNORE, RecoveryAction.STOP -> Unit

            RecoveryAction.SKIP_TO_NEXT -> {
                Log.w(TAG, "Skipping an unplayable song (${error.errorCodeName}).", error)
                // playWhenReady survives the error, so playback resumes on
                // its own if it was playing; a paused player stays paused.
                player.seekToNextMediaItem()
                player.prepare()
            }

            RecoveryAction.GIVE_UP -> {
                Log.w(TAG, "Too many unplayable songs in a row; stopping.", error)
                player.pause()
                mutableRecoveryNotice.value = PlaybackRecovery.GIVE_UP_NOTICE
            }
        }
    }

    // Bass and treble

    /** (Re)creates the equaliser on [sessionId] and applies the current tone. */
    private fun attachEqualizer(sessionId: Int) {
        releaseEqualizer()

        if (destroyed || sessionId == C.AUDIO_SESSION_ID_UNSET) return

        try {
            equalizer = Equalizer(0, sessionId)
            applyTone()
        } catch (error: Exception) {
            toneUnavailable(error)
        }
    }

    /**
     * Sets every band from the current bass/treble values. The effect is
     * enabled only when either value is non-zero.
     */
    private fun applyTone() {
        val eq = equalizer ?: return

        try {
            val range = eq.bandLevelRange
            val bands = eq.numberOfBands.toInt()
            // getCenterFreq is in milliHertz.
            val centreHz = List(bands) { eq.getCenterFreq(it.toShort()) / 1_000 }

            val levels = ToneControl.bandLevels(
                centreHz = centreHz,
                minMb = range[0].toInt(),
                maxMb = range[1].toInt(),
                bassDb = toneBassDb,
                trebleDb = toneTrebleDb,
                preset = tonePreset
            )

            levels.forEachIndexed { band, level ->
                eq.setBandLevel(band.toShort(), level.toShort())
            }

            val status = eq.setEnabled(ToneControl.isActive(toneBassDb, toneTrebleDb, tonePreset))
            check(status == AudioEffect.SUCCESS) { "Equalizer setEnabled returned $status." }

            mutableToneControlAvailable.value = true
        } catch (error: Exception) {
            toneUnavailable(error)
        }
    }

    private fun toneUnavailable(error: Exception) {
        Log.w(TAG, "Bass/treble is not available.", error)
        releaseEqualizer()
        mutableToneControlAvailable.value = false
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Could not release the equalizer.", error)
        }
        equalizer = null
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        return mediaSession
    }

    // Media browsing (Android Auto and other browsers)

    /**
     * Runs [block] on Dispatchers.IO and completes the future on the main
     * thread. The future is cancelled, never left pending, if the service
     * scope is cancelled first.
     */
    private fun <T> ioFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()

        val job = mainScope.launch {
            try {
                future.set(withContext(Dispatchers.IO) { block() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                future.setException(error)
            }
        }

        job.invokeOnCompletion {
            if (!future.isDone) future.cancel(false)
        }

        return future
    }

    /**
     * Like [ioFuture], but unexpected errors become [onError]'s result. The
     * caller supplies it so the LibraryResult type is concrete.
     */
    private fun <T> libraryFuture(
        onError: () -> LibraryResult<T>,
        block: suspend () -> LibraryResult<T>
    ): ListenableFuture<LibraryResult<T>> {
        return ioFuture {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not load browse content.", error)
                onError()
            }
        }
    }

    /** Runs on Dispatchers.IO. Returns null if [parent] no longer exists. */
    private suspend fun loadChildren(
        parent: BrowseId.Node,
        page: Int,
        pageSize: Int
    ): List<MediaItem>? {
        return when (parent) {
            BrowseId.Root -> BrowseTree.page(
                BrowseTree.rootChildren.map { nodeItem(it) },
                page,
                pageSize
            )

            BrowseId.Playlists -> {
                val linked = repository.getFolderLinkedPlaylistIds().toSet()
                val ordered = BrowseTree.orderPlaylists(
                    repository.getAllPlaylists(),
                    linked
                )

                BrowseTree.page(ordered, page, pageSize).map { playlist ->
                    nodeItem(
                        id = BrowseId.Playlist(playlist.id),
                        title = playlist.name,
                        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
                    )
                }
            }

            is BrowseId.Playlist -> {
                if (repository.getPlaylist(parent.playlistId) == null) {
                    null
                } else {
                    BrowseTree.page(
                        repository.getPlaylistSongs(parent.playlistId),
                        page,
                        pageSize
                    ).map { song ->
                        songItem(BrowseId.PlaylistSong(parent.playlistId, song.id), song)
                    }
                }
            }

            BrowseId.Recent -> BrowseTree.page(
                repository.getRecentSongs(BrowseTree.RECENT_LIMIT),
                page,
                pageSize
            ).map { song -> songItem(BrowseId.RecentSong(song.id), song) }

            BrowseId.Library -> BrowseTree.page(
                repository.getAllSongs(),
                page,
                pageSize
            ).map { song -> songItem(BrowseId.LibrarySong(song.id), song) }

            BrowseId.Favourites -> {
                // Played as its playlist, so the whole Favourites list is queued.
                val playlistId = favourites.playlistId.first()
                if (playlistId == null) {
                    emptyList()
                } else {
                    BrowseTree.page(repository.getPlaylistSongs(playlistId), page, pageSize).map { song ->
                        songItem(BrowseId.PlaylistSong(playlistId, song.id), song)
                    }
                }
            }
        }
    }

    /** Runs on Dispatchers.IO. Returns null if the node or song is unknown. */
    private suspend fun loadItem(id: BrowseId): MediaItem? {
        return when (id) {
            is BrowseId.Playlist -> repository.getPlaylist(id.playlistId)?.let {
                nodeItem(id, it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }

            is BrowseId.Node -> nodeItem(id)

            is BrowseId.Playable -> repository.getSong(id.songId)?.let { song ->
                songItem(id, song)
            }
        }
    }

    /**
     * Runs on Dispatchers.IO. A single context entry queues its whole list,
     * starting at that song; otherwise each item is resolved on its own.
     * Unknown songs are skipped. Returns null if nothing could be resolved.
     *
     * When [trustUris] is false (any controller other than this app), URIs
     * sent with the items are ignored and everything is resolved by media ID.
     */
    private suspend fun resolveQueue(
        requested: List<MediaItem>,
        startIndex: Int,
        trustUris: Boolean
    ): ResolvedQueue? {
        val single = requested.singleOrNull()

        if (single != null && !(trustUris && single.localConfiguration != null)) {
            val id = BrowseTree.decode(single.mediaId)

            val context = when (id) {
                is BrowseId.PlaylistSong -> repository.getPlaylistSongs(id.playlistId)
                is BrowseId.RecentSong -> repository.getRecentSongs(BrowseTree.RECENT_LIMIT)
                is BrowseId.LibrarySong -> repository.getAllSongs()
                else -> null
            }

            if (id is BrowseId.Playable && context != null) {
                val index = context.indexOfFirst { it.id == id.songId }

                if (index >= 0) {
                    return ResolvedQueue(
                        items = context.map { MediaItemMapper.fromSong(it) },
                        startIndex = index,
                        startMoved = false
                    )
                }
                // The song is no longer in that list; fall through and
                // queue just the song if it is still in the library.
            }
        }

        val items = mutableListOf<MediaItem>()
        val originalIndices = mutableListOf<Int>()

        requested.forEachIndexed { index, item ->
            for (resolved in resolveOne(item, trustUris)) {
                items += resolved
                originalIndices += index
            }
        }

        if (items.isEmpty()) return null

        if (startIndex == C.INDEX_UNSET) {
            return ResolvedQueue(items, C.INDEX_UNSET, startMoved = false)
        }

        val exact = originalIndices.indexOf(startIndex)
        if (exact >= 0) {
            return ResolvedQueue(items, exact, startMoved = false)
        }

        // The requested start item was skipped: start at the next one.
        val next = originalIndices.indexOfFirst { it > startIndex }
            .takeIf { it >= 0 }
            ?: items.lastIndex

        return ResolvedQueue(items, next, startMoved = true)
    }

    /**
     * Runs on Dispatchers.IO. Queue items always use the plain song ID.
     *
     * An item keeps its own URI only when [trustUris] is true. Otherwise it
     * is resolved by media ID: a song or ctx: ID gives that song, and a
     * playlist, "Recently added" or "All songs" node gives its songs.
     * Unknown IDs, and the root and playlist-list nodes, give nothing.
     */
    private suspend fun resolveOne(
        item: MediaItem,
        trustUris: Boolean
    ): List<MediaItem> {
        if (trustUris && item.isReadyToPlay()) return listOf(item)

        val songs = when (val id = BrowseTree.decode(item.mediaId)) {
            is BrowseId.Playable -> listOfNotNull(repository.getSong(id.songId))
            is BrowseId.Playlist -> repository.getPlaylistSongs(id.playlistId)
            BrowseId.Recent -> repository.getRecentSongs(BrowseTree.RECENT_LIMIT)
            BrowseId.Library -> repository.getAllSongs()
            BrowseId.Favourites -> favourites.playlistId.first()?.let { repository.getPlaylistSongs(it) }.orEmpty()
            BrowseId.Root, BrowseId.Playlists, null -> emptyList()
        }

        return songs.map { MediaItemMapper.fromSong(it) }
    }

    /** Main thread. What the heart button currently shows. */
    private var heartButtons: List<CommandButton> = emptyList()

    /** One heart for [isFavourite]; none while nothing is playing (null). */
    private fun heartButtonsFor(isFavourite: Boolean?): List<CommandButton> {
        if (isFavourite == null) return emptyList()
        return listOf(
            CommandButton.Builder(
                if (isFavourite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
            )
                .setSessionCommand(favouriteCommand)
                .setDisplayName(if (isFavourite) "Remove from Favourites" else "Add to Favourites")
                .build()
        )
    }

    private fun nodeItem(
        id: BrowseId.Node,
        title: String = BrowseTree.title(id).orEmpty(),
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)

        if (id is BrowseId.Playlist) {
            metadata.setArtworkUri(Uri.parse(ArtworkPaths.playlistUri(id.playlistId)))
        }

        val built = metadata.build()

        return MediaItem.Builder()
            .setMediaId(BrowseTree.encode(id))
            .setMediaMetadata(built)
            .build()
    }

    /** A browse entry; it has no URI and is resolved when played. */
    private fun songItem(id: BrowseId.Playable, song: SongEntity): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(Uri.parse(ArtworkPaths.songUri(song.id)))

        return MediaItem.Builder()
            .setMediaId(BrowseTree.encode(id))
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun MediaItem.isReadyToPlay(): Boolean {
        return localConfiguration != null && !hasSearchQuery()
    }

    private fun MediaItem.hasSearchQuery(): Boolean {
        return requestMetadata.searchQuery != null
    }

    // Voice and on-screen search

    /**
     * Resolves a voice query on IO and builds the result on the main thread.
     * With no match, the controller is sent ERROR_BAD_VALUE and the future
     * fails, so the current queue is never replaced.
     */
    private fun <T> searchFuture(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        query: String,
        build: (ResolvedQueue) -> T
    ): ListenableFuture<T> {
        val future = SettableFuture.create<T>()

        val job = mainScope.launch {
            try {
                val queue = withContext(Dispatchers.IO) { resolveSearch(query) }

                if (queue == null) {
                    reportNoMatch(session, controller, query)
                    future.setException(IllegalArgumentException(noMatchMessage(query)))
                } else {
                    future.set(build(queue))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Voice search failed.", error)
                future.setException(error)
            }
        }

        job.invokeOnCompletion {
            if (!future.isDone) future.cancel(false)
        }

        return future
    }

    /**
     * Runs on Dispatchers.IO. A blank query plays the most recently synced
     * folder playlist, else all songs shuffled. Returns null for no match.
     */
    private suspend fun resolveSearch(query: String): ResolvedQueue? {
        val songs = repository.getAllSongs()

        val match = VoiceSearch.match(
            query = query,
            playlists = repository.getAllPlaylists(),
            folderLinkedIds = repository.getFolderLinkedPlaylistIds().toSet(),
            songs = songs
        )

        val queueSongs: List<SongEntity> = when (match) {
            VoiceMatch.BlankQuery -> {
                val recentFolderSongs = repository.getMostRecentlySyncedFolderPlaylistId()
                    ?.let { repository.getPlaylistSongs(it) }
                    .orEmpty()

                recentFolderSongs.ifEmpty { songs.shuffled() }
            }

            VoiceMatch.NoMatch -> emptyList()

            is VoiceMatch.Playlist -> repository.getPlaylistSongs(match.playlistId)

            is VoiceMatch.Song -> {
                val song = songs.firstOrNull { it.id == match.songId }

                if (song == null) {
                    emptyList()
                } else {
                    // The song, then the rest of its first folder playlist.
                    val rest = repository.getFirstFolderPlaylistIdContaining(song.id)
                        ?.let { repository.getPlaylistSongs(it) }
                        .orEmpty()
                        .filter { it.id != song.id }

                    listOf(song) + rest
                }
            }

            is VoiceMatch.Artist -> songsInOrder(songs, match.songIds)
            is VoiceMatch.Album -> songsInOrder(songs, match.songIds)
        }

        if (queueSongs.isEmpty()) return null

        return ResolvedQueue(
            items = queueSongs.map { MediaItemMapper.fromSong(it) },
            startIndex = 0,
            startMoved = false
        )
    }

    private fun songsInOrder(songs: List<SongEntity>, ids: List<String>): List<SongEntity> {
        val byId = songs.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Runs on Dispatchers.IO. Playlists, then songs, at most 50. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        val results = VoiceSearch.searchResults(
            query = query,
            playlists = repository.getAllPlaylists(),
            folderLinkedIds = repository.getFolderLinkedPlaylistIds().toSet(),
            songs = repository.getAllSongs()
        )

        return results.playlists.map { playlist ->
            nodeItem(
                id = BrowseId.Playlist(playlist.id),
                title = playlist.name,
                mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST
            )
        } + results.songs.map { song ->
            songItem(BrowseId.LibrarySong(song.id), song)
        }
    }

    /**
     * Sends ERROR_BAD_VALUE to the requesting controller. For legacy
     * controllers (Android Auto, Assistant) it is also sent to the media
     * notification controller, which Media3 maps to the platform session's
     * error state that those clients read.
     */
    private fun reportNoMatch(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        query: String
    ) {
        val error = SessionError(SessionError.ERROR_BAD_VALUE, noMatchMessage(query))

        try {
            session.sendError(controller, error)

            if (controller.controllerVersion ==
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
            ) {
                session.mediaNotificationControllerInfo
                    ?.takeIf { it != controller }
                    ?.let { session.sendError(it, error) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not report the search error.", error)
        }
    }

    private fun noMatchMessage(query: String): String = VoiceSearch.noMatchMessage(query)

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
                    writeQueue = true,
                    result = result
                )
            )
            lastEnqueuedSnapshot = emptySnapshot

            if (sent.isFailure) {
                result.set(
                    SessionResult(SessionError.ERROR_SESSION_DISCONNECTED)
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not clear playback queue.", error)
            result.set(SessionResult(SessionError.ERROR_UNKNOWN))
        }

        return result
    }

    /** Saved snapshots are clamped to 0..2; map them onto Player's typed constants. */
    @Player.RepeatMode
    private fun Int.toPlayerRepeatMode(): Int = when (this) {
        PlaybackSnapshot.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        PlaybackSnapshot.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
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
                    player.repeatMode = saved.repeatMode.toPlayerRepeatMode()

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
                player.repeatMode = saved.repeatMode.toPlayerRepeatMode()
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

            if (!destroyed) onRestorationFinished()
        }
    }

    /** After the saved queue is loaded (or not): a widget play tap waiting for it, and the widget itself. */
    private fun onRestorationFinished() {
        pushWidget()
        if (!widgetPlayPending) return
        widgetPlayPending = false
        startFromWidget(foreground = false)
    }

    // Home-screen widget

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PlayerWidget.ACTION_PLAY_PAUSE,
            PlayerWidget.ACTION_NEXT,
            PlayerWidget.ACTION_PREVIOUS -> handleWidgetAction(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleWidgetAction(intent: Intent) {
        val foreground = intent.getBooleanExtra(PlayerWidget.EXTRA_FOREGROUND, false)

        when (intent.action) {
            PlayerWidget.ACTION_PLAY_PAUSE -> when {
                restorationInProgress -> {
                    // Cold start from the widget: meet the foreground start now
                    // (loading can take a while), and play once the queue is back.
                    widgetPlayPending = true
                    if (foreground) meetForegroundStart(R.string.widget_starting)
                }
                player.isPlaying -> {
                    player.pause()
                    if (foreground) meetForegroundStart()
                }
                else -> startFromWidget(foreground)
            }

            PlayerWidget.ACTION_NEXT -> {
                if (player.mediaItemCount > 0) player.seekToNextMediaItem()
                if (foreground) meetForegroundStart()
            }

            PlayerWidget.ACTION_PREVIOUS -> {
                if (player.mediaItemCount > 0) player.seekToPreviousMediaItem()
                if (foreground) meetForegroundStart()
            }
        }
    }

    /**
     * Plays the queue. Started with startForegroundService, the service must
     * become foreground: Media3 does that once playback starts; if nothing
     * can play (or it has not within 4 s on Android 10+), a brief notice
     * meets the obligation instead of letting Android stop the app.
     */
    private fun startFromWidget(foreground: Boolean) {
        if (player.mediaItemCount == 0) {
            if (foreground) meetForegroundStart()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()

        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mainScope.launch {
                delay(4_000L)
                if (!destroyed && foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) meetForegroundStart()
            }
        }
    }

    /** Becomes foreground for a moment with a quiet notice, then drops it. */
    private fun meetForegroundStart(textRes: Int = R.string.widget_nothing_to_play) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        ) {
            return
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(WIDGET_CHANNEL_ID, "Home-screen widget", NotificationManager.IMPORTANCE_LOW)
            )
            val notice = NotificationCompat.Builder(this, WIDGET_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentText(getString(textRes))
                .setSilent(true)
                .build()
            ServiceCompat.startForeground(
                this,
                WIDGET_NOTIFICATION_ID,
                notice,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (error: Exception) {
            Log.w(TAG, "Could not meet the widget's foreground start.", error)
        }
    }

    /** Main thread. The widget's text and button state from the player. */
    private fun pushWidget() {
        if (destroyed) return
        val item = player.currentMediaItem
        val songId = item?.mediaId?.takeIf { player.mediaItemCount > 0 }
        val metadata = player.mediaMetadata
        val next = WidgetState(
            hasSong = songId != null,
            songId = songId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying
        )
        PlayerWidget.update(this, next)
        widgetSongId.value = songId
    }

    /** Runs on Dispatchers.IO. The song's cover, rounded, at most 256 px; null if it has none. */
    private fun loadWidgetCover(songId: String): android.graphics.Bitmap? = try {
        val uri = Uri.parse(ArtworkPaths.songUri(songId))
        val decoded = decodeWithinBounds(
            open = { contentResolver.openInputStream(uri) },
            readSize = { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                bounds.outWidth to bounds.outHeight
            },
            decode = { stream, sample ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
            },
            maxPx = WidgetModel.COVER_MAX_PX
        )
        decoded?.let { bitmap -> PlayerWidget.roundedCover(bitmap).also { if (it !== bitmap) bitmap.recycle() } }
    } catch (error: Exception) {
        null
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
         * Only the song that plays first and at most two after it are
         * opened, so a long queue restores quickly. Songs that turn out to
         * be missing later are skipped during playback (recoverFromError).
         * Songs no longer in the library are always left out.
         */
        val checked = PlaybackRecovery.restoreCheckIndices(
            queueSize = snapshot.queueSongIds.size,
            selectedIndex = snapshot.selectedIndex
        )

        // Cache access checks so a repeated queue item is checked once.
        val accessById = mutableMapOf<String, Boolean>()
        val songs = mutableListOf<SongEntity>()
        val originalIndices = mutableListOf<Int>()
        var missingCount = 0

        snapshot.queueSongIds.forEachIndexed { index, songId ->
            val song = songsById[songId]

            val accessible = when {
                song == null -> false
                index !in checked -> true
                else -> accessById.getOrPut(songId) { canOpen(song.uri) }
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
        sendSnapshot()
    }

    /**
     * Sends the current state to the writer unless it equals the last one
     * sent. The queue is included only when it (or its shuffle order)
     * changed since then.
     */
    private fun sendSnapshot() {
        val snapshot = captureSnapshot()
        val last = lastEnqueuedSnapshot

        if (snapshot == last) return

        val writeQueue = last == null ||
            snapshot.queueSongIds != last.queueSongIds ||
            snapshot.shuffleOrder != last.shuffleOrder

        val sent = snapshotChannel.trySend(
            SnapshotWrite(snapshot = snapshot, writeQueue = writeQueue)
        )

        if (sent.isFailure) {
            Log.e(TAG, "Could not enqueue playback snapshot.")
        } else {
            lastEnqueuedSnapshot = snapshot
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
            sendSnapshot()
        }

        destroyed = true
        // Restores the volume if a fade was running.
        sleepTimer?.let { timer ->
            timer.release()
            player.removeListener(timer.listener)
        }
        sleepTimer = null
        mutableSleepTimer.value = SleepTimerState.Off
        player.removeListener(widgetListener)
        PlayerWidget.update(this, PlayerWidget.state.copy(isPlaying = false))
        mainScope.cancel()

        player.removeListener(playerListener)
        player.removeListener(recoveryListener)
        player.removeListener(toneListener)
        releaseEqualizer()
        mediaSession?.release()
        mediaSession = null
        player.release()

        snapshotChannel.close()

        super.onDestroy()
    }

    private data class SnapshotWrite(
        val snapshot: PlaybackSnapshot,
        /** False for position-only checkpoints: the saved queue is unchanged. */
        val writeQueue: Boolean = true,
        val result: SettableFuture<SessionResult>? = null
    )

    private data class ResolvedQueue(
        val items: List<MediaItem>,
        val startIndex: Int,
        /** True when the requested start item was skipped. */
        val startMoved: Boolean
    )

    private data class RecoveryResult(
        val snapshot: PlaybackSnapshot,
        val songs: List<SongEntity>,
        val originalIndices: List<Int>,
        val missingCount: Int
    )
}

/** Visualiser publish interval (~30 Hz) and how long without audio counts as a stall. */
private const val VISUALISER_TICK_MS = 33L
private const val VISUALISER_STALL_NANOS = 300_000_000L
