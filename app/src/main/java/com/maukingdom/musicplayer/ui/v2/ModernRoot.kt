package com.maukingdom.musicplayer.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.ui.MusicPlayerTheme
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.PlaybackUiState
import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.SettingsScreen
import com.maukingdom.musicplayer.ui.player.SkinPlayer
import com.maukingdom.musicplayer.ui.player.toPlayerUi
import com.maukingdom.musicplayer.ui.v2.components.BottomNavBar
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerBar
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerSideMargin
import com.maukingdom.musicplayer.ui.v2.components.MiniPlayerState
import com.maukingdom.musicplayer.ui.v2.components.ModernTab
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton
import com.maukingdom.musicplayer.ui.v2.home.HomeHeaderWash
import com.maukingdom.musicplayer.ui.v2.home.HomeRoute
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
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    BackHandler(enabled = sheet.isOpen) { closeSheet() }

                    TabsLayer(
                        tab = tab,
                        onSelectTab = { tabName = it.name },
                        onSettings = { showSettings = true },
                        onOpenLibrary = { tabName = ModernTab.LIBRARY.name },
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (sheet.isOpen) Modifier.clearAndSetSemantics { } else Modifier)
    ) {
        // Behind the status bar and the greeting, at the very top.
        if (tab == ModernTab.HOME) {
            HomeHeaderWash()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
        ) {
            if (tab == ModernTab.HOME) {
                // Home draws its own greeting and gear, so no TopBar.
                HomeRoute(
                    musicViewModel = musicViewModel,
                    onSettings = onSettings,
                    onOpenLibrary = onOpenLibrary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // HomeRoute has no contentPadding parameter yet; keep it clear of the bars.
                        .padding(contentPadding)
                )
            } else {
                TopBar(onSettings = onSettings)

                TabPlaceholder(
                    tab = tab,
                    contentPadding = contentPadding,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
    val actions = rememberModernPlayerActions(musicViewModel, onOpenSkinPicker = onOpenSettings)
    val playback = playbackState.value

    NowPlayingSheet(
        state = sheet,
        playlistName = playlistName,
        canClearQueue = playback.connected && playback.queueSize > 0,
        onClose = onClose,
        onSettle = onSettle,
        onChangeSkin = onOpenSettings,
        onClearQueue = actions::clearQueue
    ) {
        SkinPlayer(
            skin = skin,
            ui = playback.toPlayerUi(
                artwork = artwork,
                playlistName = playlistName,
                bassDb = bassDb,
                trebleDb = trebleDb,
                toneAvailable = toneAvailable != false
            ),
            actions = actions,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = ModernDimens.screenPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MyCar Music",
            style = Modern.type.screenTitle,
            color = Modern.colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )

        V2IconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            onClick = onSettings
        )
    }
}

/**
 * Empty per-tab content until the next steps fill it. [contentPadding] is
 * what the bottom bars cover; real lists pass it to their contentPadding.
 */
@Composable
private fun TabPlaceholder(
    tab: ModernTab,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(contentPadding)
            .padding(ModernDimens.screenPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = tab.label,
                style = Modern.type.sectionTitle,
                color = Modern.colors.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Modern layout — coming in the next steps",
                style = Modern.type.rowSubtitle,
                color = Modern.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
