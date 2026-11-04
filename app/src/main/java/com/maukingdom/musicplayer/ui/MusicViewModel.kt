package com.maukingdom.musicplayer.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.maukingdom.musicplayer.data.FolderPlaylistEntity
import com.maukingdom.musicplayer.data.ImportResult
import com.maukingdom.musicplayer.data.MetadataReader
import com.maukingdom.musicplayer.data.MusicDatabase
import com.maukingdom.musicplayer.data.MusicImporter
import com.maukingdom.musicplayer.data.MusicRepository
import com.maukingdom.musicplayer.data.PlaybackPreferences
import com.maukingdom.musicplayer.data.PlaylistEntity
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.data.SongMetadata
import com.maukingdom.musicplayer.ui.v2.cover.COVER_CANDIDATES_PER_PLAYLIST
import com.maukingdom.musicplayer.ui.v2.cover.CoverCandidateRef
import com.maukingdom.musicplayer.ui.v2.cover.coverCandidates
import com.maukingdom.musicplayer.playback.ArtworkPaths
import com.maukingdom.musicplayer.playback.MediaItemMapper
import com.maukingdom.musicplayer.playback.PlaybackService
import com.maukingdom.musicplayer.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    /** Bass in whole dB, -6..+6. */
    val bassDb: StateFlow<Int> = preferences.appPreferences
        .map { it.bassDb }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
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

    /** The playlist the current queue was started from, if any. */
    private data class QueueSource(val songIds: List<String>, val playlistId: Long)

    /** Main thread only. Cleared once the player's queue no longer matches it. */
    private var queueSource: QueueSource? = null
    private val mutableQueuePlaylistId = MutableStateFlow<Long?>(null)

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

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return null

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ARTWORK_MAX_PX &&
                bounds.outHeight / (sample * 2) >= ARTWORK_MAX_PX
            ) {
                sample *= 2
            }

            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            } ?: return null

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

        queueSource?.let { source ->
            val stillSameQueue = player.mediaItemCount == source.songIds.size &&
                player.currentMediaItem?.mediaId ==
                source.songIds.getOrNull(player.currentMediaItemIndex)

            if (!stillSameQueue) {
                queueSource = null
                mutableQueuePlaylistId.value = null
            }
        }

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

    fun importDocuments(uris: List<Uri>, resultFlags: Int) {
        if (uris.isEmpty()) return

        runImport {
            importer.importDocuments(uris, resultFlags)
        }
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

    private fun runImport(operation: suspend () -> ImportResult) {
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

                mutableLibraryOperation.value = LibraryOperationState(
                    message = "Imported ${result.importedCount} item(s).$skipped",
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

                if (connection.playQueue(items, safeIndex)) {
                    queueSource = sourcePlaylistId?.let { QueueSource(songIds, it) }
                    mutableQueuePlaylistId.value = sourcePlaylistId
                } else {
                    showError("Playback service is not connected yet.")
                }
            } catch (error: Exception) {
                showError(error.message ?: "Could not start playback.")
            }
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
