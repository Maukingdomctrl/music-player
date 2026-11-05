package com.maukingdom.musicplayer.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.maukingdom.musicplayer.data.FavouritesRules
import com.maukingdom.musicplayer.data.FavouritesStore
import com.maukingdom.musicplayer.data.FolderPlaylistEntity
import com.maukingdom.musicplayer.data.ImportResult
import com.maukingdom.musicplayer.data.MetadataReader
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicImporter
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.PlaylistEdit
import com.maukingdom.musicplayer.data.QueueOriginRecord
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMetadata
import com.maukingdom.musicplayer.ui.v2.cover.COVER_CANDIDATES_PER_PLAYLIST
import com.maukingdom.musicplayer.ui.v2.cover.CoverCandidateRef
import com.maukingdom.musicplayer.ui.v2.cover.coverCandidates
import com.maukingdom.musicplayer.ui.v2.playlistpicker.ImportOffer
import com.maukingdom.musicplayer.ui.v2.playlistpicker.PlaylistEditKind
import com.maukingdom.musicplayer.ui.v2.playlistpicker.PlaylistEditResult
import com.maukingdom.musicplayer.ui.v2.playlistpicker.QueueEditResult
import com.maukingdom.musicplayer.ui.v2.playlistpicker.importOffer
import com.maukingdom.musicplayer.ui.v2.playlistpicker.playlistEditMessage
import com.maukingdom.musicplayer.playback.ArtworkPaths
import com.maukingdom.musicplayer.playback.MediaItemMapper
import com.maukingdom.musicplayer.playback.PlaybackService
import com.maukingdom.musicplayer.playback.PlayerConnection
import com.maukingdom.musicplayer.playback.EqPreset
import com.maukingdom.musicplayer.playback.SleepTimerState
import com.maukingdom.musicplayer.ui.v2.queue.QueueEntry
import com.maukingdom.musicplayer.ui.v2.queue.QueueOrigin
import com.maukingdom.musicplayer.ui.v2.queue.QueueUi
import com.maukingdom.musicplayer.ui.v2.queue.QueueUndo
import com.maukingdom.musicplayer.ui.v2.queue.canUndoInsert
import com.maukingdom.musicplayer.ui.v2.queue.insertAt
import com.maukingdom.musicplayer.ui.v2.queue.keepOnly
import com.maukingdom.musicplayer.ui.v2.queue.moveItem
import com.maukingdom.musicplayer.ui.v2.queue.playNextIndex
import com.maukingdom.musicplayer.ui.v2.queue.queueAddMessage
import com.maukingdom.musicplayer.ui.v2.queue.queueFingerprint
import com.maukingdom.musicplayer.ui.v2.queue.queueKeys
import com.maukingdom.musicplayer.ui.v2.queue.removeAt
import com.maukingdom.musicplayer.ui.v2.queue.resolveOrigin
import com.maukingdom.musicplayer.ui.v2.queue.upcomingIndices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUiState(
    val connected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val currentSongId: String? = null,
    val currentIndex: Int = 0,
    val queueSize: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val volume: Float = 0f,
    val error: String? = null
)

/**
 * The selected playlist's songs tagged with the playlist they belong to, so
 * a screen can tell a list that is still the previous playlist's.
 */
data class SelectedPlaylistSongs(
    val playlistId: Long? = null,
    val songs: List<SongEntity> = emptyList()
)

data class LibraryOperationState(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class MusicViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val database = MusicDatabase.getInstance(appContext)
    private val repository = MusicRepository(database)
    private val preferences = PlaybackPreferences(appContext)
    private val metadataReader = MetadataReader(appContext)
    private val importer = MusicImporter(
        context = appContext,
        repository = repository,
        metadataReader = metadataReader
    )

    private val connection = PlayerConnection(appContext)

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val songs: StateFlow<List<SongEntity>> = repository.songs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val playlists: StateFlow<List<PlaylistEntity>> = repository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val favourites = FavouritesStore(repository, preferences)

    /** The Favourites playlist id while it exists (heart badge), else null. Read-only. */
    val favouritesPlaylistId: StateFlow<Long?> = favourites.playlistId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /** Ids of the songs in Favourites. Read-only. */
    val favouriteSongIds: StateFlow<Set<String>> = favourites.songIds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet()
    )

    // Saved, so the open playlist survives Android killing the app (#19).
    private val mutableSelectedPlaylistId =
        MutableStateFlow(savedState.get<Long>(KEY_SELECTED_PLAYLIST))
    val selectedPlaylistId: StateFlow<Long?> =
        mutableSelectedPlaylistId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedPlaylistContent: StateFlow<SelectedPlaylistSongs> =
        mutableSelectedPlaylistId
            .flatMapLatest { playlistId ->
                if (playlistId == null) {
                    flowOf(SelectedPlaylistSongs())
                } else {
                    repository.observePlaylistSongs(playlistId)
                        .map { songs -> SelectedPlaylistSongs(playlistId, songs) }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SelectedPlaylistSongs()
            )

    /** The selected playlist's songs (Classic). Same source as [selectedPlaylistContent]. */
    val selectedPlaylistSongs: StateFlow<List<SongEntity>> =
        selectedPlaylistContent
            .map { it.songs }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** Read-only: the selected playlist's SD-card folder link, or null. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedFolderLink: StateFlow<FolderPlaylistEntity?> =
        mutableSelectedPlaylistId
            .flatMapLatest { playlistId ->
                if (playlistId == null) flowOf(null) else repository.observeFolderLink(playlistId)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    private val mutablePlaybackState = MutableStateFlow(PlaybackUiState())

    // Mirrors of two playbackState fields, so internal collectors do not
    // count as UI subscribers of mutablePlaybackState.
    private val mutableIsPlaying = MutableStateFlow(false)
    private val mutableCurrentSongId = MutableStateFlow<String?>(null)
    val playbackState: StateFlow<PlaybackUiState> =
        mutablePlaybackState.asStateFlow()

    private val mutableLibraryOperation =
        MutableStateFlow(LibraryOperationState())
    val libraryOperation: StateFlow<LibraryOperationState> =
        mutableLibraryOperation.asStateFlow()

    /** Shares the libraryOperation.loading guard with runImport. */
    private val folderController = FolderSyncController(
        scope = viewModelScope,
        isBusy = { mutableLibraryOperation.value.loading },
        setBusy = { busy ->
            if (busy) {
                mutableLibraryOperation.value = LibraryOperationState(loading = true)
            } else {
                mutableLibraryOperation.update { it.copy(loading = false) }
            }
        }
    )

    val folderOperation: StateFlow<FolderOperationState> = folderController.state

    val folderLinkedPlaylistIds: StateFlow<Set<Long>> =
        repository.observeFolderLinkedPlaylistIds()
            .map { ids -> ids.toSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
            )

    /** Songs per playlist id; playlists without songs are absent. */
    val playlistSongCounts: StateFlow<Map<Long, Int>> =
        repository.observePlaylistSongCounts()
            .map { counts -> counts.associate { it.playlistId to it.songCount } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

    /**
     * Read-only: up to [COVER_CANDIDATES_PER_PLAYLIST] song ids per playlist
     * whose covers Modern's living covers may use (different albums first).
     */
    val playlistCoverCandidates: StateFlow<Map<Long, List<String>>> =
        repository.observePlaylistCoverRefs()
            .map { refs ->
                coverCandidates(
                    refs.map { CoverCandidateRef(it.playlistId, it.songId, it.album) },
                    COVER_CANDIDATES_PER_PLAYLIST
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

    /** Modern only: results of Add / Move / Remove for the snackbar, each delivered once. */
    private val playlistEditChannel = Channel<PlaylistEditResult>(Channel.BUFFERED)
    val playlistEditResults: Flow<PlaylistEditResult> = playlistEditChannel.receiveAsFlow()

    /** Modern only: songs a "Select files" import just added, offered for a playlist. */
    private val mutableImportOffer = MutableStateFlow<ImportOffer?>(null)
    val importOffer: StateFlow<ImportOffer?> = mutableImportOffer.asStateFlow()

    private val mutableEditingSong = MutableStateFlow<SongEntity?>(null)
    val editingSong: StateFlow<SongEntity?> = mutableEditingSong.asStateFlow()

    val recoveryNotice: StateFlow<String?> = PlaybackService.recoveryNotice
    val connectionError: StateFlow<String?> = connection.connectionError

    /**
     * Null means DataStore has not emitted its initial value yet.
     */
    val carModeEnabled: StateFlow<Boolean?> = preferences.appPreferences
        .map { appPreferences -> appPreferences.carModeEnabled as Boolean? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val playerSkin: StateFlow<PlayerSkin> = preferences.appPreferences
        .map { appPreferences -> PlayerSkin.fromId(appPreferences.playerSkinId) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlayerSkin.NEON_WALKMAN
        )

    /**
     * Classic or Modern. Null only until the saved choice has been read, so
     * the activity can wait instead of flashing the wrong layout.
     */
    val appLayout: StateFlow<AppLayout?> = preferences.appPreferences
        .map { appPreferences -> AppLayout.fromId(appPreferences.appLayoutId) as AppLayout? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    /** Modern only: whether the Studio player takes the cover's colour (default on). */
    val useCoverColour: StateFlow<Boolean> = preferences.appPreferences
        .map { it.useCoverColour }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true
        )

    /** Bass in whole dB, -6..+6. */
    val bassDb: StateFlow<Int> = preferences.appPreferences
        .map { it.bassDb }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    /** The equaliser preset; bass and treble apply on top of it. */
    val eqPreset: StateFlow<EqPreset> = preferences.appPreferences
        .map { EqPreset.fromId(it.eqPresetId) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EqPreset.FLAT
        )

    /** Treble in whole dB, -6..+6. */
    val trebleDb: StateFlow<Int> = preferences.appPreferences
        .map { it.trebleDb }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    /** Null until the playback service has tried; false if the phone refused. */
    val toneControlAvailable: StateFlow<Boolean?> = PlaybackService.toneControlAvailable

    private val mutableCurrentArtwork = MutableStateFlow<ImageBitmap?>(null)

    /**
     * The current song's cover from [ArtworkPaths.songUri], at most 512 px.
     * Null when nothing is playing or the image cannot be read.
     */
    val currentArtwork: StateFlow<ImageBitmap?> = mutableCurrentArtwork.asStateFlow()

    /**
     * Where this app last set the queue from, saved so a restored queue
     * keeps its caption. Main thread only. It describes the player's queue
     * only while the queue's fingerprint still matches (see resolveOrigin).
     */
    private var queueOrigin: QueueOrigin? = null
    private var queueOriginSetHere = false
    /** The player's queue song ids, refreshed on timeline changes. Main thread only. */
    private var playerQueueIds: List<String> = emptyList()
    private val mutableQueuePlaylistId = MutableStateFlow<Long?>(null)
    private val mutableQueueEdited = MutableStateFlow(false)

    /** True when the user changed the queue in Modern (caption "PLAYING FROM QUEUE"). Read-only. */
    val queueEdited: StateFlow<Boolean> = mutableQueueEdited.asStateFlow()

    private val mutableQueue = MutableStateFlow(QueueUi())

    /** Now playing and up next, for Modern's Up next sheet. Rebuilt on queue changes only. */
    val queue: StateFlow<QueueUi> = mutableQueue.asStateFlow()

    /** Modern only: Play next / Add to queue results for the snackbar. */
    private val queueEditChannel = Channel<QueueEditResult>(Channel.BUFFERED)
    val queueEditResults: Flow<QueueEditResult> = queueEditChannel.receiveAsFlow()

    /** The service's sleep timer (Modern Studio player). */
    val sleepTimer: StateFlow<SleepTimerState> = PlaybackService.sleepTimer

    /** Id of the playlist the queue was started from in this app, or null. Read-only. */
    val queuePlaylistId: StateFlow<Long?> = mutableQueuePlaylistId.asStateFlow()

    /**
     * Name of the playlist the queue was started from in this app, or null
     * (whole library, or a queue set by another controller such as Android
     * Auto).
     */
    val queuePlaylistName: StateFlow<String?> =
        combine(mutableQueuePlaylistId, repository.playlists) { playlistId, all ->
            playlistId?.let { id -> all.firstOrNull { it.id == id }?.name }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private var observedPlayer: Player? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) {
                refreshQueue(player, timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
            }
            updatePlaybackState(player)
        }
    }

    init {
        connection.connect()

        // Reopen the metadata editor after process death; its text fields
        // restore their drafts with rememberSaveable.
        savedState.get<String>(KEY_EDITING_SONG)?.let { songId ->
            viewModelScope.launch {
                val song = try {
                    repository.getSong(songId)
                } catch (error: Exception) {
                    null
                }

                if (song == null) {
                    savedState.remove<String>(KEY_EDITING_SONG)
                } else if (mutableEditingSong.value == null) {
                    mutableEditingSong.value = song
                }
            }
        }

        viewModelScope.launch {
            connection.controller.collect { controller ->
                observedPlayer?.removeListener(playerListener)
                observedPlayer = controller

                if (controller != null) {
                    controller.addListener(playerListener)
                    refreshQueue(controller, timelineChanged = true)
                    updatePlaybackState(controller)
                } else {
                    publishPlaybackState(
                        PlaybackUiState(
                            connected = false,
                            volume = readSystemVolume()
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            /*
             * The 500 ms position poll runs only while the UI is visible
             * (collectAsStateWithLifecycle subscribes to playbackState only
             * while the screen is started) and something is playing. Player
             * events still update the state at any time.
             */
            combine(
                mutablePlaybackState.subscriptionCount.map { it > 0 },
                mutableIsPlaying
            ) { visible, playing -> visible to playing }
                .distinctUntilChanged()
                .collectLatest { (visible, playing) ->
                    // Catch up once when the screen comes back.
                    if (visible) observedPlayer?.let(::updatePlaybackState)

                    while (visible && playing && isActive) {
                        delay(500L)
                        observedPlayer?.let(::updatePlaybackState)
                    }
                }
        }

        viewModelScope.launch {
            folderController.state.collect(::onFolderOperationState)
        }

        viewModelScope.launch {
            val saved = try {
                preferences.getQueueOrigin()
            } catch (error: Exception) {
                null
            }
            // A queue started meanwhile wins over the saved origin.
            if (!queueOriginSetHere && saved != null) {
                queueOrigin = QueueOrigin(saved.playlistId, saved.edited, saved.fingerprint)
                applyQueueOrigin()
            }
        }

        viewModelScope.launch {
            // collectLatest cancels the previous load when the song changes.
            // Not collected from mutablePlaybackState, whose subscriber
            // count tells the poll above whether the UI is visible.
            mutableCurrentSongId
                .collectLatest { songId ->
                    mutableCurrentArtwork.value = if (songId == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) { loadArtwork(songId) }
                    }
                }
        }
    }

    /** Runs on Dispatchers.IO. Null on any error. */
    private fun loadArtwork(songId: String): ImageBitmap? {
        return try {
            val uri = Uri.parse(ArtworkPaths.songUri(songId))
            val resolver = appContext.contentResolver

            val decoded = decodeWithinBounds(
                open = { resolver.openInputStream(uri) },
                // Bounds-only: decodeStream returns null here by design; the size is in the options.
                readSize = { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    bounds.outWidth to bounds.outHeight
                },
                decode = { stream, sample ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
                },
                maxPx = ARTWORK_MAX_PX
            ) ?: return null

            val longest = maxOf(decoded.width, decoded.height)
            val bitmap = if (longest > ARTWORK_MAX_PX) {
                val scale = ARTWORK_MAX_PX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }

            bitmap.asImageBitmap()
        } catch (error: Exception) {
            null
        }
    }

    /** Mirrors folder results into the existing message/error banners. */
    private suspend fun onFolderOperationState(state: FolderOperationState) {
        when (state) {
            is FolderOperationState.Succeeded -> {
                val playlistId = state.summary.playlistId

                if (state.kind == FolderOperationKind.IMPORT) {
                    selectPlaylist(playlistId)
                }

                val name = try {
                    repository.playlists.first()
                        .firstOrNull { it.id == playlistId }
                        ?.name
                } catch (error: Exception) {
                    null
                }

                mutableLibraryOperation.update {
                    it.copy(
                        message = folderSummaryText(name, state.summary),
                        error = null
                    )
                }
            }

            is FolderOperationState.Failed -> {
                mutableLibraryOperation.update {
                    it.copy(message = null, error = state.message)
                }
            }

            FolderOperationState.Idle,
            is FolderOperationState.Running -> Unit
        }
    }

    private fun updatePlaybackState(player: Player) {
        val metadata = player.mediaMetadata
        val duration = player.duration

        publishPlaybackState(PlaybackUiState(
            connected = true,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            currentSongId = player.currentMediaItem?.mediaId,
            currentIndex = if (player.mediaItemCount > 0) {
                player.currentMediaItemIndex.coerceAtLeast(0)
            } else {
                0
            },
            queueSize = player.mediaItemCount,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET) {
                0L
            } else {
                duration.coerceAtLeast(0L)
            },
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            volume = readSystemVolume(),
            error = player.playerError?.message
        ))
    }

    private fun publishPlaybackState(state: PlaybackUiState) {
        mutablePlaybackState.value = state
        mutableIsPlaying.value = state.isPlaying
        mutableCurrentSongId.value = state.currentSongId
    }

    fun scanMediaStore() {
        runImport {
            importer.scanMediaStore()
        }
    }

    /**
     * With [offerPlaylist] (Modern), a successful import is reported through
     * [importOffer] instead of the info banner, so the songs can be added to
     * a playlist. Warnings still use the banner.
     */
    fun importDocuments(uris: List<Uri>, resultFlags: Int, offerPlaylist: Boolean = false) {
        if (uris.isEmpty()) return

        runImport(offerPlaylist) {
            importer.importDocuments(uris, resultFlags)
        }
    }

    fun consumeImportOffer() {
        mutableImportOffer.value = null
    }

    fun importFolder(treeUri: Uri) {
        val started = folderController.run(FolderOperationKind.IMPORT, null) {
            importer.importFolder(treeUri)
        }

        if (!started) {
            showError(ANOTHER_OPERATION_RUNNING)
        }
    }

    fun syncFolderPlaylist(playlistId: Long) {
        val started = folderController.run(FolderOperationKind.SYNC, playlistId) {
            importer.syncFolderPlaylist(playlistId)
        }

        if (!started) {
            showError(ANOTHER_OPERATION_RUNNING)
        }
    }

    /**
     * RE-SELECT FOLDER: points the existing folder playlist at [treeUri] and
     * syncs it, instead of importing a second playlist.
     */
    fun relinkFolder(playlistId: Long, treeUri: Uri) {
        val started = folderController.run(FolderOperationKind.SYNC, playlistId) {
            importer.relinkFolder(playlistId, treeUri)
        }

        if (!started) {
            showError(ANOTHER_OPERATION_RUNNING)
        }
    }

    fun clearFolderOperation() {
        folderController.clear()
    }

    private fun runImport(offerPlaylist: Boolean = false, operation: suspend () -> ImportResult) {
        if (
            mutableLibraryOperation.value.loading ||
            folderController.state.value is FolderOperationState.Running
        ) {
            showError(ANOTHER_OPERATION_RUNNING)
            return
        }

        // Set synchronously so the shared guard is closed before launch.
        mutableLibraryOperation.value = LibraryOperationState(loading = true)

        viewModelScope.launch {
            try {
                val result = operation()

                val warning = if (result.failures.isNotEmpty()) {
                    val first = result.failures.first()
                    "${result.failures.size} warning(s). ${first.message}"
                } else {
                    null
                }

                val skipped = if (result.skippedRemoved > 0) {
                    " ${result.skippedRemoved} removed song(s) were left out."
                } else {
                    ""
                }

                val offer = if (offerPlaylist) importOffer(result.importedSongIds) else null
                mutableImportOffer.value = offer

                mutableLibraryOperation.value = LibraryOperationState(
                    message = if (offer != null) null else "Imported ${result.importedCount} item(s).$skipped",
                    error = warning
                )
            } catch (error: Exception) {
                mutableLibraryOperation.value = LibraryOperationState(
                    error = error.message ?: "The import could not be completed."
                )
            }
        }
    }

    /** Keeps [LibraryOperationState.loading] so a running operation stays guarded. */
    fun showError(message: String) {
        mutableLibraryOperation.update {
            LibraryOperationState(loading = it.loading, error = message)
        }
    }

    fun clearOperationMessages() {
        mutableLibraryOperation.update {
            LibraryOperationState(loading = it.loading)
        }
    }

    fun clearRecoveryNotice() {
        PlaybackService.clearRecoveryNotice()
    }

    fun playSongs(songsToPlay: List<SongEntity>, startIndex: Int = 0) {
        if (songsToPlay.isEmpty()) {
            showError("There are no songs to play.")
            return
        }

        // Remember the playlist only when these are exactly its songs.
        // selectedPlaylistContent is live whenever either layout shows the
        // selected playlist (Classic collects it through selectedPlaylistSongs).
        val songIds = songsToPlay.map { it.id }
        val sourcePlaylistId = mutableSelectedPlaylistId.value?.takeIf { selectedId ->
            val content = selectedPlaylistContent.value
            content.playlistId == selectedId && content.songs.map { song -> song.id } == songIds
        }

        startQueue(songsToPlay, startIndex, sourcePlaylistId)
    }

    /**
     * Plays a whole playlist from its first song (Modern Home tiles and
     * folder cards). Does not change the selected playlist.
     */
    fun playPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val playlistSongs = try {
                repository.getPlaylistSongs(playlistId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message ?: "Could not open the playlist.")
                return@launch
            }

            if (playlistSongs.isEmpty()) {
                showError("This playlist has no songs yet.")
            } else {
                startQueue(playlistSongs, 0, playlistId)
            }
        }
    }

    private fun startQueue(
        songsToPlay: List<SongEntity>,
        startIndex: Int,
        sourcePlaylistId: Long?
    ) {
        val songIds = songsToPlay.map { it.id }

        viewModelScope.launch {
            try {
                val safeIndex = startIndex.coerceIn(0, songsToPlay.lastIndex)

                val items = withContext(Dispatchers.IO) {
                    songsToPlay.mapIndexed { index, song ->
                        val artwork = if (
                            index == safeIndex && song.artworkUri == null
                        ) {
                            metadataReader.readEmbeddedArtwork(Uri.parse(song.uri))
                        } else {
                            null
                        }

                        MediaItemMapper.fromSong(song, artwork)
                    }
                }

                // Set first: the controller may report the new queue during playQueue.
                val previous = queueOrigin
                setQueueOrigin(QueueOrigin(sourcePlaylistId, edited = false, fingerprint = queueFingerprint(songIds)))

                if (!connection.playQueue(items, safeIndex)) {
                    setQueueOrigin(previous)
                    showError("Playback service is not connected yet.")
                }
            } catch (error: Exception) {
                showError(error.message ?: "Could not start playback.")
            }
        }
    }

    /** Rebuilds [queue] and re-checks the origin. Main thread. */
    private fun refreshQueue(player: Player, timelineChanged: Boolean) {
        val count = player.mediaItemCount
        val previous = mutableQueue.value
        val entries: List<QueueEntry>

        if (timelineChanged || previous.current == null && count > 0) {
            val ids = List(count) { player.getMediaItemAt(it).mediaId }
            if (ids != playerQueueIds) {
                playerQueueIds = ids
                applyQueueOrigin()
            }
            val keys = queueKeys(ids)
            entries = List(count) { index ->
                val item = player.getMediaItemAt(index)
                QueueEntry(
                    index = index,
                    key = keys[index],
                    songId = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty()
                )
            }
            cachedQueueEntries = entries
        } else {
            entries = cachedQueueEntries
        }

        val current = player.currentMediaItemIndex
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val upcoming = if (timeline.isEmpty || current !in entries.indices) {
            emptyList()
        } else {
            upcomingIndices(current, entries.size) { index ->
                timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle).takeIf { it != C.INDEX_UNSET }
            }
        }

        mutableQueue.value = QueueUi(
            current = entries.getOrNull(current),
            upcoming = upcoming.mapNotNull { entries.getOrNull(it) },
            shuffle = shuffle
        )
    }

    /** Main thread only; see [refreshQueue]. */
    private var cachedQueueEntries: List<QueueEntry> = emptyList()

    private fun applyQueueOrigin() {
        val resolved = resolveOrigin(queueOrigin, playerQueueIds)
        mutableQueuePlaylistId.value = resolved.playlistId
        mutableQueueEdited.value = resolved.edited
    }

    /** Remembers (and saves) where the queue now comes from, updating the caption at once. */
    private fun setQueueOrigin(origin: QueueOrigin?) {
        queueOrigin = origin
        queueOriginSetHere = true
        mutableQueuePlaylistId.value = origin?.takeUnless { it.edited }?.playlistId
        mutableQueueEdited.value = origin?.edited == true

        if (origin != null) {
            viewModelScope.launch {
                try {
                    preferences.saveQueueOrigin(QueueOriginRecord(origin.playlistId, origin.edited, origin.fingerprint))
                } catch (error: Exception) {
                    // Only the caption after a restart is affected.
                }
            }
        }
    }

    /** Marks the queue as edited by the user, expecting it to become [expectedIds]. */
    private fun markQueueEdited(expectedIds: List<String>) {
        setQueueOrigin(QueueOrigin(playlistId = null, edited = true, fingerprint = queueFingerprint(expectedIds)))
    }

    private fun currentQueueIds(player: Player): List<String> =
        List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    /** Modern: inserts [songIds] after the current song, or starts playback if nothing is queued. */
    fun playNext(songIds: List<String>) = enqueue(songIds, playNext = true)

    /** Modern: appends [songIds] to the queue, or starts playback if nothing is queued. */
    fun addToQueue(songIds: List<String>) = enqueue(songIds, playNext = false)

    private fun enqueue(songIds: List<String>, playNext: Boolean) {
        if (songIds.isEmpty()) return

        viewModelScope.launch {
            try {
                val songsToAdd = withContext(Dispatchers.IO) { songIds.mapNotNull { repository.getSong(it) } }
                if (songsToAdd.isEmpty()) {
                    showError("Those songs are no longer in the library.")
                    return@launch
                }

                val player = observedPlayer ?: run {
                    showError("Playback service is not connected yet.")
                    return@launch
                }

                if (player.mediaItemCount == 0) {
                    startQueue(songsToAdd, 0, null)
                    queueEditChannel.send(QueueEditResult(message = "Playing now", undo = null))
                    return@launch
                }

                val ids = currentQueueIds(player)
                val index = if (playNext) {
                    playNextIndex(player.currentMediaItemIndex, ids.size)
                } else {
                    ids.size
                }
                val addedIds = songsToAdd.map { it.id }
                val previous = queueOrigin

                markQueueEdited(insertAt(ids, index, addedIds))
                player.addMediaItems(index, songsToAdd.map { MediaItemMapper.fromSong(it) })

                queueEditChannel.send(
                    QueueEditResult(
                        message = queueAddMessage(playNext, addedIds.size),
                        undo = QueueUndo(index, addedIds, previous)
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message ?: "Could not change the queue.")
            }
        }
    }

    /** Takes out what Play next / Add to queue put in, if it is still where it went. */
    fun undoQueueEdit(undo: QueueUndo) {
        val player = observedPlayer ?: return
        val ids = currentQueueIds(player)
        if (!canUndoInsert(ids, undo)) {
            showError("The queue changed, so that could not be undone.")
            return
        }
        setQueueOrigin(undo.previousOrigin)
        player.removeMediaItems(undo.index, undo.index + undo.songIds.size)
    }

    /** Up next: moves item [from] to [to] (player item indices). */
    fun moveQueueItem(from: Int, to: Int) {
        val player = observedPlayer ?: return
        val ids = currentQueueIds(player)
        if (from !in ids.indices || to !in ids.indices || from == to) return
        markQueueEdited(moveItem(ids, from, to))
        player.moveMediaItem(from, to)
    }

    /** Up next: removes item [index]; never the song playing now. */
    fun removeQueueItem(index: Int) {
        val player = observedPlayer ?: return
        if (index == player.currentMediaItemIndex || index !in 0 until player.mediaItemCount) return
        markQueueEdited(removeAt(currentQueueIds(player), index))
        player.removeMediaItem(index)
    }

    /** Up next: keeps only the song playing now. */
    fun clearUpcoming() {
        val player = observedPlayer ?: return
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (count <= 1 || current !in 0 until count) return
        markQueueEdited(keepOnly(currentQueueIds(player), current))
        if (current + 1 < count) player.removeMediaItems(current + 1, count)
        if (current > 0) player.removeMediaItems(0, current)
    }

    /** Up next: plays item [index] from its start. */
    fun playQueueItem(index: Int) {
        val player = observedPlayer ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    /** [arg]: minutes, SleepTimerMath.ARG_END_OF_SONG or ARG_OFF. */
    fun setSleepTimer(arg: Int) {
        val args = Bundle().apply { putInt(PlaybackService.EXTRA_SLEEP_ARG, arg) }
        if (!connection.sendCommand(PlaybackService.ACTION_SET_SLEEP_TIMER, args)) {
            showError("Playback service is not connected yet.")
        }
    }

    /**
     * Submits an explicit queue-clear request to the playback service.
     *
     * Queue UI state is updated by the existing player listener when the
     * controller reports the actual queue change. A successful submission
     * does not itself confirm that snapshot persistence succeeded.
     */
    fun clearQueue() {
        if (!connection.clearQueue()) {
            showError("Could not submit the queue-clear request.")
        }
    }

    fun togglePlayPause() {
        val player = observedPlayer ?: run {
            showError("Playback service is not connected yet.")
            return
        }

        if (player.mediaItemCount == 0) {
            showError("Choose a song first.")
            return
        }

        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun next() {
        observedPlayer?.seekToNextMediaItem()
    }

    fun previous() {
        observedPlayer?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        observedPlayer?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun toggleShuffle() {
        observedPlayer?.let { player ->
            player.shuffleModeEnabled = !player.shuffleModeEnabled
        }
    }

    fun cycleRepeat() {
        observedPlayer?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setVolume(fraction: Float) {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (fraction.coerceIn(0f, 1f) * maximum).toInt()

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            target,
            0
        )

        observedPlayer?.let(::updatePlaybackState)
    }

    private fun readSystemVolume(): Float {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) return 0f

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current.toFloat() / maximum.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * "Play X on MyCar Music" from the phone's Assistant (the
     * MEDIA_PLAY_FROM_SEARCH intent). Hands the query to the playback
     * service, which uses the same matching as Android Auto voice search; a
     * blank query resumes or plays the latest folder playlist. Waits for the
     * controller if it is still connecting.
     */
    fun playFromSearch(query: String) {
        viewModelScope.launch {
            try {
                val controller = connection.controller.filterNotNull().first()

                val request = MediaItem.Builder()
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setSearchQuery(query)
                            .build()
                    )
                    .build()

                controller.setMediaItem(request)
                controller.prepare()
                controller.play()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message ?: "Could not start playback from the search.")
            }
        }
    }

    fun selectPlaylist(playlistId: Long?) {
        setSelectedPlaylist(playlistId)
    }

    private fun setSelectedPlaylist(playlistId: Long?) {
        mutableSelectedPlaylistId.value = playlistId
        savedState[KEY_SELECTED_PLAYLIST] = playlistId
    }

    fun createPlaylist(name: String) {
        runLibraryAction {
            val id = repository.createPlaylist(name)
            setSelectedPlaylist(id)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        runLibraryAction {
            check(repository.renamePlaylist(playlistId, name)) {
                "Playlist no longer exists."
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        runLibraryAction {
            check(repository.deletePlaylist(playlistId)) {
                "Playlist no longer exists."
            }

            if (mutableSelectedPlaylistId.value == playlistId) {
                setSelectedPlaylist(null)
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: String) {
        runLibraryAction {
            check(repository.addSongToPlaylist(playlistId, songId)) {
                "The song is already in the playlist or is unavailable."
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: String) {
        runLibraryAction {
            check(repository.removeSongFromPlaylist(playlistId, songId)) {
                "The song is no longer in this playlist."
            }
        }
    }

    /** Read-only: ids of the playlists that already hold [songId]. */
    fun observePlaylistIdsContaining(songId: String): Flow<Set<Long>> =
        repository.observePlaylistIdsContaining(songId).map { it.toSet() }

    /** Modern: appends [songIds] (skipping ones already there) and reports through [playlistEditResults]. */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<String>) {
        runPlaylistEdit(PlaylistEditKind.ADD, playlistId) {
            repository.addSongsToPlaylist(playlistId, songIds)
        }
    }

    /** Modern: creates playlist [name] with [songIds]. Does not change the selected playlist. */
    fun addSongsToNewPlaylist(name: String, songIds: List<String>) {
        runLibraryAction {
            val edit = repository.createPlaylistWithSongs(name, songIds)
            reportPlaylistEdit(PlaylistEditKind.ADD, name.trim(), edit)
        }
    }

    /**
     * Modern: moves [songIds] from [fromPlaylistId] to [toPlaylistId].
     * Only Room changes; the player's queue (and the song playing) is untouched.
     */
    fun moveSongsToPlaylist(fromPlaylistId: Long, toPlaylistId: Long, songIds: List<String>) {
        runPlaylistEdit(PlaylistEditKind.MOVE, toPlaylistId) {
            repository.moveSongs(fromPlaylistId, toPlaylistId, songIds)
        }
    }

    /** Modern: creates playlist [name] and moves [songIds] into it. Does not change the selected playlist. */
    fun moveSongsToNewPlaylist(name: String, fromPlaylistId: Long, songIds: List<String>) {
        runLibraryAction {
            val edit = repository.moveSongsToNewPlaylist(name, fromPlaylistId, songIds)
            reportPlaylistEdit(PlaylistEditKind.MOVE, name.trim(), edit)
        }
    }

    /** Modern multi-select: removes [songIds] from [playlistId], with undo. */
    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<String>) {
        runPlaylistEdit(PlaylistEditKind.REMOVE, playlistId) {
            repository.removeSongsFromPlaylist(playlistId, songIds)
        }
    }

    /** Studio heart: adds or removes [songId]; the heart itself shows the result. */
    fun toggleFavourite(songId: String) {
        runLibraryAction { favourites.toggle(songId) }
    }

    /** Menus and multi-select: puts [songIds] at the top of Favourites, with an Undo snackbar. */
    fun addToFavourites(songIds: List<String>) {
        if (songIds.isEmpty()) return
        runLibraryAction {
            reportPlaylistEdit(PlaylistEditKind.ADD, FavouritesRules.NAME, favourites.add(songIds))
        }
    }

    fun removeFromFavourites(songIds: List<String>) {
        if (songIds.isEmpty()) return
        runLibraryAction {
            reportPlaylistEdit(PlaylistEditKind.REMOVE, FavouritesRules.NAME, favourites.remove(songIds))
        }
    }

    fun undoPlaylistEdit(edit: PlaylistEdit) {
        runLibraryAction {
            repository.undoPlaylistEdit(edit)
        }
    }

    private fun runPlaylistEdit(
        kind: PlaylistEditKind,
        playlistId: Long,
        edit: suspend () -> PlaylistEdit
    ) {
        runLibraryAction {
            val result = edit()
            val name = repository.getPlaylist(playlistId)?.name.orEmpty()
            reportPlaylistEdit(kind, name, result)
        }
    }

    private suspend fun reportPlaylistEdit(kind: PlaylistEditKind, name: String, edit: PlaylistEdit) {
        playlistEditChannel.send(
            PlaylistEditResult(
                message = playlistEditMessage(kind, edit, name),
                edit = edit.takeIf { it.changedAnything }
            )
        )
    }

    fun reorderPlaylist(playlistId: Long, orderedSongIds: List<String>) {
        runLibraryAction {
            check(repository.reorderPlaylist(playlistId, orderedSongIds)) {
                "The playlist changed. Please try again."
            }
        }
    }

    fun removeSong(songId: String) {
        runLibraryAction {
            val song = repository.getSong(songId)

            check(repository.removeSong(songId)) {
                "The song is no longer in the library."
            }

            // "Scan this phone" will not add it back; an explicit import will.
            preferences.addRemovedSongId(songId)

            if (song != null) {
                importer.releaseFileGrant(song.uri)
            }
        }
    }

    fun editSong(song: SongEntity) {
        mutableEditingSong.value = song
        savedState[KEY_EDITING_SONG] = song.id
    }

    fun cancelMetadataEdit() {
        mutableEditingSong.value = null
        savedState.remove<String>(KEY_EDITING_SONG)
    }

    fun saveMetadata(songId: String, metadata: SongMetadata) {
        viewModelScope.launch {
            try {
                check(repository.updateMetadata(songId, metadata)) {
                    "The song is no longer in the library."
                }

                val updatedSong = repository.getSong(songId)

                if (updatedSong != null) {
                    refreshQueuedMetadata(updatedSong)
                }

                mutableEditingSong.value = null
                savedState.remove<String>(KEY_EDITING_SONG)
                mutableLibraryOperation.value = LibraryOperationState(
                    message = "Metadata saved in the app library."
                )
            } catch (error: Exception) {
                showError(error.message ?: "Could not save metadata.")
            }
        }
    }

    private suspend fun refreshQueuedMetadata(song: SongEntity) {
        val player = observedPlayer ?: return

        val artwork = if (song.artworkUri == null) {
            withContext(Dispatchers.IO) {
                metadataReader.readEmbeddedArtwork(Uri.parse(song.uri))
            }
        } else {
            null
        }

        val updatedItem = MediaItemMapper.fromSong(song, artwork)

        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId == song.id) {
                player.replaceMediaItem(index, updatedItem)
            }
        }
    }

    fun setCarModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.setCarModeEnabled(enabled)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save Car Mode preference.")
            }
        }
    }

    fun setUseCoverColour(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.setUseCoverColour(enabled)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the setting.")
            }
        }
    }

    fun setAppLayout(layout: AppLayout) {
        viewModelScope.launch {
            try {
                preferences.setAppLayout(layout.id)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the app layout.")
            }
        }
    }

    fun setPlayerSkin(skin: PlayerSkin) {
        viewModelScope.launch {
            try {
                preferences.setPlayerSkin(skin.id)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the player skin.")
            }
        }
    }

    fun setBassDb(db: Int) {
        viewModelScope.launch {
            try {
                preferences.setBassDb(db)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the bass setting.")
            }
        }
    }

    fun setEqPreset(preset: EqPreset) {
        viewModelScope.launch {
            try {
                preferences.setEqPreset(preset.id)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the equaliser preset.")
            }
        }
    }

    /** Flat preset, bass and treble 0. */
    fun resetTone() {
        viewModelScope.launch {
            try {
                preferences.resetTone(EqPreset.FLAT.id)
            } catch (error: Exception) {
                showError(error.message ?: "Could not reset the equaliser.")
            }
        }
    }

    fun setTrebleDb(db: Int) {
        viewModelScope.launch {
            try {
                preferences.setTrebleDb(db)
            } catch (error: Exception) {
                showError(error.message ?: "Could not save the treble setting.")
            }
        }
    }

    private fun runLibraryAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                showError(error.message ?: "The library operation failed.")
            }
        }
    }

    override fun onCleared() {
        observedPlayer?.removeListener(playerListener)
        observedPlayer = null
        connection.close()
        super.onCleared()
    }

    private companion object {
        const val ANOTHER_OPERATION_RUNNING =
            "Another library operation is already running."

        const val ARTWORK_MAX_PX = 512

        const val KEY_SELECTED_PLAYLIST = "selected_playlist_id"
        const val KEY_EDITING_SONG = "editing_song_id"
    }
}
