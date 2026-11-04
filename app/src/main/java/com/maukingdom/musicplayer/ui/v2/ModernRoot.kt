package com.maukingdom.musicplayer.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.ui.MusicPlayerTheme
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.PlaybackUiState
import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.SettingsScreen
import com.maukingdom.musicplayer.ui.player.toPlayerUi
import com.maukingdom.musicplayer.ui.v2.components.BottomNavBar
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerBar
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerSideMargin
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerState
import com.maukingdom.musicplayer.ui.v2.components.ModernTab
import com.maukingdom.musicplayer.ui.v2.cover.CoversCovered
import com.maukingdom.musicplayer.ui.v2.cover.ProvideLivingCovers
import com.maukingdom.musicplayer.ui.v2.home.HomeHeaderWash
import com.maukingdom.musicplayer.ui.v2.home.HomeRoute
import com.maukingdom.musicplayer.ui.v2.library.LibraryRoute
import com.maukingdom.musicplayer.ui.v2.playlist.PlaylistRoute
import com.maukingdom.musicplayer.ui.v2.search.SearchRoute
import com.maukingdom.musicplayer.ui.v2.studio.CoverColourSetting
import com.maukingdom.musicplayer.ui.v2.studio.StudioPlayer
import com.maukingdom.musicplayer.ui.v2.studio.formatBadge
import com.maukingdom.musicplayer.ui.v2.studio.rememberStudioTint
import com.maukingdom.musicplayer.ui.v2.studio.studioSkinTint
import kotlinx.coroutines.launch

/**
 * The Modern layout's root, bottom to top: tab content (drawn behind the
 * bars, with matching bottom padding), the mini player and navigation, and
 * the Now Playing sheet over everything. The settings gear stays reachable
 * so the user can always switch back to Classic.
 *
 * Shares [musicViewModel] with Classic, so switching layouts never touches
 * playback.
 */
@Composable
fun ModernRoot(musicViewModel: MusicViewModel) {
    val skin by musicViewModel.playerSkin.collectAsStateWithLifecycle()
    // Kept as a State: only the children that need the 500 ms position read it.
    val playbackState = musicViewModel.playbackState.collectAsStateWithLifecycle()
    val hasQueue by remember {
        derivedStateOf { playbackState.value.let { it.queueSize > 0 && it.currentSongId != null } }
    }

    var tabName by rememberSaveable { mutableStateOf(ModernTab.HOME.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // The open Playlist screen, over the current tab; survives Settings and rotation.
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Search text, kept across tab switches and rotation.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Set by Library's search icon: the Search field takes focus once.
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    // Above the Settings branch, so the player is still open after Back.
    val sheet = rememberNowPlayingSheetState()

    val tab = ModernTab.entries.firstOrNull { it.name == tabName } ?: ModernTab.HOME

    MusicPlayerTheme(skin = skin) {
        ModernTheme {
            val colors = Modern.colors
            val reduceMotion = LocalReduceMotion.current
            val scope = rememberCoroutineScope()

            fun openSheet() { scope.launch { sheet.open(reduceMotion) } }
            fun closeSheet() { scope.launch { sheet.close(reduceMotion) } }

            // Close the player when the queue is cleared (from either menu).
            var hadQueue by remember { mutableStateOf(hasQueue) }
            LaunchedEffect(hasQueue) {
                if (hadQueue && !hasQueue && sheet.isOpen) sheet.close(reduceMotion)
                hadQueue = hasQueue
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
            ) {
                if (showSettings) {
                    BackHandler { showSettings = false }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        SettingsScreen(
                            selectedSkin = skin,
                            onSelectSkin = musicViewModel::setPlayerSkin,
                            onBack = { showSettings = false },
                            layoutViewModel = musicViewModel,
                            modifier = Modifier.weight(1f),
                            layoutOptions = {
                                val useCoverColour by musicViewModel.useCoverColour.collectAsStateWithLifecycle()
                                CoverColourSetting(
                                    checked = useCoverColour,
                                    onCheckedChange = musicViewModel::setUseCoverColour
                                )
                            }
                        )
                    }
                } else {
                    BackHandler(enabled = sheet.isOpen) { closeSheet() }
                    BackHandler(enabled = openPlaylistId != null && !sheet.isOpen) { openPlaylistId = null }

                    TabsLayer(
                        tab = tab,
                        // Tapping any tab, the selected one too, closes an open playlist.
                        onSelectTab = {
                            openPlaylistId = null
                            tabName = it.name
                        },
                        openPlaylistId = openPlaylistId,
                        onOpenPlaylist = { id ->
                            musicViewModel.selectPlaylist(id)
                            openPlaylistId = id
                        },
                        onClosePlaylist = { openPlaylistId = null },
                        onSettings = { showSettings = true },
                        onOpenLibrary = { tabName = ModernTab.LIBRARY.name },
                        onOpenSearch = {
                            focusSearch = true
                            tabName = ModernTab.SEARCH.name
                        },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        focusSearch = focusSearch,
                        onSearchFocusHandled = { focusSearch = false },
                        hasQueue = hasQueue,
                        playbackState = playbackState,
                        musicViewModel = musicViewModel,
                        sheet = sheet,
                        onOpenPlayer = ::openSheet
                    )

                    if (sheet.isVisible) {
                        NowPlayingLayer(
                            musicViewModel = musicViewModel,
                            playbackState = playbackState,
                            skin = skin,
                            sheet = sheet,
                            onClose = ::closeSheet,
                            onSettle = { open -> if (open) openSheet() else closeSheet() },
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }
}

/** Top bar, tab content, mini player and navigation. Hidden from TalkBack while the player is open. */
@Composable
private fun TabsLayer(
    tab: ModernTab,
    onSelectTab: (ModernTab) -> Unit,
    onSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSearch: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    focusSearch: Boolean,
    onSearchFocusHandled: () -> Unit,
    openPlaylistId: Long?,
    onOpenPlaylist: (Long) -> Unit,
    onClosePlaylist: () -> Unit,
    hasQueue: Boolean,
    playbackState: State<PlaybackUiState>,
    musicViewModel: MusicViewModel,
    sheet: NowPlayingSheetState,
    onOpenPlayer: () -> Unit
) {
    val colors = Modern.colors
    val navInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    // Everything the bottom stack covers, so lists can scroll clear of it.
    val contentPadding = PaddingValues(
        bottom = navInset.calculateBottomPadding() +
            ModernDimens.bottomNavHeight +
            (if (hasQueue) ModernDimens.miniPlayerHeight else 0.dp)
    )

    val playlistVisibility = remember { MutableTransitionState(openPlaylistId != null) }
    LaunchedEffect(openPlaylistId != null) { playlistVisibility.targetState = openPlaylistId != null }
    // Once the playlist fully covers the tab, the tab is not drawn and TalkBack skips it.
    val tabCovered = playlistVisibility.currentState && playlistVisibility.isIdle
    // Keeps the last playlist on screen while it slides away (plain holder, never observed).
    val shownPlaylistId = remember { longArrayOf(-1L) }
    openPlaylistId?.let { shownPlaylistId[0] = it }

    val coverCandidates by musicViewModel.playlistCoverCandidates.collectAsStateWithLifecycle()

    // One clock for every living cover on screen; stops while Now Playing covers it.
    ProvideLivingCovers(candidates = coverCandidates, covered = sheet.isOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (sheet.isOpen) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
            val covered = if (tabCovered) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { } else Modifier

            // Behind the status bar and the greeting, at the very top.
            if (tab == ModernTab.HOME) {
                HomeHeaderWash(modifier = covered)
            }

            // The tab's covers hold still while an open playlist covers it.
            CoversCovered(covered = tabCovered) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(covered)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                ) {
                    when (tab) {
                        // Each tab draws its own header.
                        ModernTab.HOME -> HomeRoute(
                            musicViewModel = musicViewModel,
                            onSettings = onSettings,
                            onOpenLibrary = onOpenLibrary,
                            onOpenPlaylist = onOpenPlaylist,
                            contentPadding = contentPadding,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        ModernTab.LIBRARY -> LibraryRoute(
                            musicViewModel = musicViewModel,
                            onOpenSearch = onOpenSearch,
                            onOpenPlaylist = onOpenPlaylist,
                            contentPadding = contentPadding,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        ModernTab.SEARCH -> SearchRoute(
                            musicViewModel = musicViewModel,
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            focusOnOpen = focusSearch,
                            onFocusHandled = onSearchFocusHandled,
                            onOpenPlaylist = onOpenPlaylist,
                            contentPadding = contentPadding,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }

            // The Playlist screen: over the tab content, under the mini player and navigation.
            AnimatedVisibility(
                visibleState = playlistVisibility,
                enter = fadeIn(modernTween(ModernMotion.PLAYLIST_ENTER_MS)) +
                    slideInHorizontally(modernTween(ModernMotion.PLAYLIST_ENTER_MS)) { it / 10 },
                exit = fadeOut(modernTween(ModernMotion.PLAYLIST_EXIT_MS)) +
                    slideOutHorizontally(modernTween(ModernMotion.PLAYLIST_EXIT_MS)) { it / 10 },
                label = "playlist"
            ) {
                if (shownPlaylistId[0] >= 0L) {
                    PlaylistRoute(
                        playlistId = shownPlaylistId[0],
                        musicViewModel = musicViewModel,
                        onBack = onClosePlaylist,
                        contentPadding = contentPadding,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // Grows in when a queue starts and folds away when it is cleared.
                AnimatedVisibility(
                    visible = hasQueue,
                    enter = expandVertically(modernTween(ModernMotion.COLOR_MS), expandFrom = Alignment.Top) +
                        fadeIn(modernTween(ModernMotion.COLOR_MS)),
                    exit = shrinkVertically(modernTween(ModernMotion.TEXT_SWAP_MS), shrinkTowards = Alignment.Top) +
                        fadeOut(modernTween(ModernMotion.TEXT_SWAP_MS)),
                    label = "miniPlayer"
                ) {
                    MiniPlayerHost(
                        playbackState = playbackState,
                        musicViewModel = musicViewModel,
                        onOpen = onOpenPlayer,
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = MiniPlayerSideMargin)
                            .fillMaxWidth()
                            // Fades out as the sheet rises over it, back in as it closes.
                            .graphicsLayer { alpha = sheet.offset }
                    )
                }

                BottomNavBar(
                    selected = tab,
                    onSelect = onSelectTab,
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                )

                // The bar's colour continues under the gesture / 3-button navigation bar.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bg)
                        .windowInsetsBottomHeight(WindowInsets.safeDrawing)
                )
            }
        }
    }
}

/**
 * Feeds the mini player. Its state is derived, so 500 ms position ticks do
 * not recompose anything here; the progress line reads the position while
 * drawing. Keeps the last song while the bar folds away after Clear queue.
 */
@Composable
private fun MiniPlayerHost(
    playbackState: State<PlaybackUiState>,
    musicViewModel: MusicViewModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current by remember(playbackState) {
        derivedStateOf {
            val playback = playbackState.value
            playback.currentSongId?.let { songId ->
                MiniPlayerState(
                    songId = songId,
                    title = playback.title,
                    artist = playback.artist,
                    isPlaying = playback.isPlaying
                )
            }
        }
    }
    // Plain holder, not snapshot state: written here, never observed.
    val lastShown = remember { arrayOfNulls<MiniPlayerState>(1) }
    current?.let { lastShown[0] = it }
    val state = current ?: lastShown[0] ?: return

    val progressProvider = remember(playbackState) {
        { playbackState.value.let { miniProgress(it.positionMs, it.durationMs) } }
    }
    val artwork by musicViewModel.currentArtwork.collectAsStateWithLifecycle()
    val tint = rememberCoverTint(artwork)

    MiniPlayerBar(
        state = state,
        progressProvider = progressProvider,
        onTogglePlay = musicViewModel::togglePlayPause,
        onOpen = onOpen,
        onNext = musicViewModel::next,
        onPrevious = musicViewModel::previous,
        tint = tint,
        modifier = modifier
    )
}

@Composable
private fun NowPlayingLayer(
    musicViewModel: MusicViewModel,
    playbackState: State<PlaybackUiState>,
    skin: PlayerSkin,
    sheet: NowPlayingSheetState,
    onClose: () -> Unit,
    onSettle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val artwork by musicViewModel.currentArtwork.collectAsStateWithLifecycle()
    val playlistName by musicViewModel.queuePlaylistName.collectAsStateWithLifecycle()
    val bassDb by musicViewModel.bassDb.collectAsStateWithLifecycle()
    val trebleDb by musicViewModel.trebleDb.collectAsStateWithLifecycle()
    val toneAvailable by musicViewModel.toneControlAvailable.collectAsStateWithLifecycle()
    val useCoverColour by musicViewModel.useCoverColour.collectAsStateWithLifecycle()
    val songs by musicViewModel.songs.collectAsStateWithLifecycle()
    val actions = rememberModernPlayerActions(musicViewModel, onOpenSkinPicker = onOpenSettings)
    val playback = playbackState.value

    // One hue for the whole player: the cover's colour, or the skin's.
    val targetTint = rememberStudioTint(artwork, useCoverColour, Color(studioSkinTint(skin)))
    val tint = animateColorAsState(targetTint, modernTween(ModernMotion.STUDIO_TINT_MS), label = "studioTint")
    val currentSong = remember(songs, playback.currentSongId) {
        songs.firstOrNull { it.id == playback.currentSongId }
    }
    val badge = remember(currentSong) { currentSong?.let { formatBadge(it.mimeType, it.uri) } }

    NowPlayingSheet(
        state = sheet,
        playlistName = playlistName,
        canClearQueue = playback.connected && playback.queueSize > 0,
        onClose = onClose,
        onSettle = onSettle,
        onChangeSkin = onOpenSettings,
        onClearQueue = actions::clearQueue,
        tintProvider = { tint.value }
    ) {
        StudioPlayer(
            ui = playback.toPlayerUi(
                artwork = artwork,
                playlistName = playlistName,
                bassDb = bassDb,
                trebleDb = trebleDb,
                toneAvailable = toneAvailable != false
            ),
            actions = actions,
            tint = tint,
            formatBadge = badge,
            modifier = Modifier.fillMaxSize()
        )
    }
}
