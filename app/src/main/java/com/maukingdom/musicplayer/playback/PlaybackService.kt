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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
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
             * controllers under this application's UID.
             */
            if (controller.uid == Process.myUid()) {
                val commands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
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
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )

            return libraryFuture(
                onError = { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) }
            ) {
                val children = loadChildren(parent, page, pageSize)

                if (children == null) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
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
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )

            return libraryFuture(
                onError = { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) }
            ) {
                val item = loadItem(id)

                if (item == null) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
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
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
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
                onError = { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) }
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

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivity)
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
            BrowseId.Root, BrowseId.Playlists, null -> emptyList()
        }

        return songs.map { MediaItemMapper.fromSong(it) }
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

    private fun noMatchMessage(query: String): String {
        val shown = query.trim().take(80)
        return "Couldn't find \"$shown\" in MyCar Music."
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
