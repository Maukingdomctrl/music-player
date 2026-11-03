package com.maukingdom.musicplayer.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private enum class MusicPage {
    LIBRARY,
    PLAYLISTS,
    PLAYER,
    CAR,
    SIMULATOR,
    SETTINGS
}

/**
 * The installed versionName. BuildConfig is not generated for this module,
 * so the package manager is used instead.
 */
private fun appVersionName(context: Context): String {
    return try {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }

        info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}

@Composable
fun MusicPlayerScreen(
    musicViewModel: MusicViewModel = viewModel()
) {
    val context = LocalContext.current

    val songs by musicViewModel.songs.collectAsStateWithLifecycle()
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val selectedPlaylistId by musicViewModel.selectedPlaylistId
        .collectAsStateWithLifecycle()
    val selectedPlaylistSongs by musicViewModel.selectedPlaylistSongs
        .collectAsStateWithLifecycle()
    val playback by musicViewModel.playbackState.collectAsStateWithLifecycle()
    val operation by musicViewModel.libraryOperation.collectAsStateWithLifecycle()
    val editingSong by musicViewModel.editingSong.collectAsStateWithLifecycle()
    val recoveryNotice by musicViewModel.recoveryNotice.collectAsStateWithLifecycle()
    val connectionError by musicViewModel.connectionError.collectAsStateWithLifecycle()
    val carModeEnabled by musicViewModel.carModeEnabled.collectAsStateWithLifecycle()
    val playerSkin by musicViewModel.playerSkin.collectAsStateWithLifecycle()
    val folderOperation by musicViewModel.folderOperation.collectAsStateWithLifecycle()
    val folderLinkedIds by musicViewModel.folderLinkedPlaylistIds
        .collectAsStateWithLifecycle()

    var pageName by rememberSaveable {
        mutableStateOf(MusicPage.LIBRARY.name)
    }

    var pageBeforeSettings by rememberSaveable {
        mutableStateOf(MusicPage.LIBRARY.name)
    }

    var initialCarModeApplied by rememberSaveable {
        mutableStateOf(false)
    }

    val page = MusicPage.entries.firstOrNull { it.name == pageName }
        ?: MusicPage.LIBRARY

    LaunchedEffect(carModeEnabled) {
        val savedPreference = carModeEnabled

        if (!initialCarModeApplied && savedPreference != null) {
            if (savedPreference) {
                pageName = MusicPage.CAR.name
            }

            initialCarModeApplied = true
        }
    }

    fun navigateTo(destination: MusicPage) {
        initialCarModeApplied = true
        pageName = destination.name
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            musicViewModel.scanMediaStore()
        } else {
            musicViewModel.showError(
                "Audio-library access was denied. You can still select files."
            )
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = buildList {
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index).uri?.let(::add)
                    }
                }

                data?.data?.let(::add)
            }.distinct()

            if (uris.isEmpty()) {
                musicViewModel.showError("No audio files were selected.")
            } else {
                musicViewModel.importDocuments(
                    uris = uris,
                    resultFlags = data?.flags ?: 0
                )
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        // Null means the user cancelled the picker.
        if (treeUri != null) {
            musicViewModel.importFolder(treeUri)
        }
    }

    fun openFolderPicker(initialUri: Uri? = null) {
        try {
            folderLauncher.launch(initialUri)
        } catch (_: ActivityNotFoundException) {
            musicViewModel.showError("No folder picker is available.")
        }
    }

    /** Clears both the folder result and the banner that mirrors it. */
    fun dismissOperationMessages() {
        musicViewModel.clearOperationMessages()
        musicViewModel.clearFolderOperation()
    }

    /**
     * True when [error] is a sync failure that PlaylistsScreen is already
     * showing inline with a RE-SELECT FOLDER button, so the banner would
     * only duplicate it.
     */
    fun folderFailureShownInline(error: String): Boolean {
        val failure = inlineFolderFailure(folderOperation) ?: return false

        return page == MusicPage.PLAYLISTS &&
            editingSong == null &&
            error == failure.message &&
            playlists.any { it.id == failure.playlistId } &&
            (selectedPlaylistId == null || selectedPlaylistId == failure.playlistId)
    }

    fun requestLibraryScan() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            musicViewModel.scanMediaStore()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    fun openDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            documentLauncher.launch(intent)
        } catch (_: Exception) {
            musicViewModel.showError("No document picker is available.")
        }
    }

    fun openCarMode() {
        musicViewModel.setCarModeEnabled(true)
        navigateTo(MusicPage.CAR)
    }

    fun openSettings() {
        pageBeforeSettings = page.name
        navigateTo(MusicPage.SETTINGS)
    }

    fun closeSettings() {
        val previous = MusicPage.entries.firstOrNull { it.name == pageBeforeSettings }
            ?.takeIf { it != MusicPage.SETTINGS }
            ?: MusicPage.LIBRARY
        navigateTo(previous)
    }

    BackHandler(enabled = page == MusicPage.SETTINGS && editingSong == null) {
        closeSettings()
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val showBottomNav = editingSong == null &&
        page != MusicPage.CAR &&
        page != MusicPage.SETTINGS &&
        !imeVisible

    /** The menu and its swipe gesture exist only on the three main tabs. */
    val drawerAvailable = editingSong == null && (
        page == MusicPage.LIBRARY ||
            page == MusicPage.PLAYLISTS ||
            page == MusicPage.PLAYER
        )

    val selectedNavItem = when (page) {
        MusicPage.LIBRARY -> RetroNavItem.LIBRARY
        MusicPage.PLAYLISTS -> RetroNavItem.LISTS
        MusicPage.PLAYER -> RetroNavItem.PLAYER
        MusicPage.CAR, MusicPage.SIMULATOR, MusicPage.SETTINGS -> null
    }

    val versionName = remember(context) { appVersionName(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    fun openDrawer() {
        drawerScope.launch { drawerState.open() }
    }

    /**
     * Runs [action] immediately and closes the drawer concurrently, so an
     * interrupted close animation can never drop the tap.
     */
    fun closeDrawerAnd(action: () -> Unit) {
        action()
        drawerScope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }

    MusicPlayerTheme(skin = playerSkin) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerAvailable || drawerState.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            drawerContent = {
                RetroDrawerSheet(
                    skin = playerSkin,
                    versionName = versionName,
                    loading = operation.loading,
                    onClose = { closeDrawerAnd {} },
                    onScanPhone = { closeDrawerAnd(::requestLibraryScan) },
                    onSelectFiles = { closeDrawerAnd(::openDocumentPicker) },
                    onImportFolder = { closeDrawerAnd { openFolderPicker() } },
                    onPlayerSkin = { closeDrawerAnd(::openSettings) },
                    onCarMode = { closeDrawerAnd(::openCarMode) }
                )
            }
        ) {
            RetroBackground {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RetroHeader(
                        onMenu = if (drawerAvailable) ::openDrawer else null
                    )

                    recoveryNotice?.let { notice ->
                        StereoMessage(
                            message = notice,
                            isError = true,
                            onDismiss = musicViewModel::clearRecoveryNotice
                        )
                    }

                    connectionError?.let { error ->
                        StereoMessage(
                            message = error,
                            isError = true,
                            onDismiss = null
                        )
                    }

                    operation.message?.let { message ->
                        StereoMessage(
                            message = message,
                            isError = false,
                            onDismiss = ::dismissOperationMessages
                        )
                    }

                    operation.error
                        ?.takeIf { error -> !folderFailureShownInline(error) }
                        ?.let { error ->
                            StereoMessage(
                                message = error,
                                isError = true,
                                onDismiss = ::dismissOperationMessages
                            )
                        }

                    if (operation.loading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = StereoColors.Amber,
                                modifier = Modifier.padding(4.dp)
                            )
                            Text(
                                text = "Reading local music…",
                                color = StereoColors.Amber,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    val songBeingEdited = editingSong

                    if (songBeingEdited != null) {
                        MetadataEditorScreen(
                            song = songBeingEdited,
                            onSave = { metadata ->
                                musicViewModel.saveMetadata(
                                    songBeingEdited.id,
                                    metadata
                                )
                            },
                            onCancel = musicViewModel::cancelMetadataEdit,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        when (page) {
                            MusicPage.LIBRARY -> LibraryScreen(
                                songs = songs,
                                playlists = playlists,
                                loading = operation.loading,
                                onPlay = { index ->
                                    musicViewModel.playSongs(songs, index)
                                    navigateTo(MusicPage.PLAYER)
                                },
                                onEdit = musicViewModel::editSong,
                                onRemove = musicViewModel::removeSong,
                                onAddToPlaylist = musicViewModel::addSongToPlaylist,
                                modifier = Modifier.weight(1f)
                            )

                            MusicPage.PLAYLISTS -> PlaylistsScreen(
                                playlists = playlists,
                                selectedPlaylistId = selectedPlaylistId,
                                selectedSongs = selectedPlaylistSongs,
                                onSelectPlaylist = musicViewModel::selectPlaylist,
                                onCreatePlaylist = musicViewModel::createPlaylist,
                                onRenamePlaylist = musicViewModel::renamePlaylist,
                                onDeletePlaylist = musicViewModel::deletePlaylist,
                                onRemoveSong = musicViewModel::removeSongFromPlaylist,
                                onReorder = musicViewModel::reorderPlaylist,
                                onPlay = { index ->
                                    musicViewModel.playSongs(selectedPlaylistSongs, index)
                                    navigateTo(MusicPage.PLAYER)
                                },
                                folderLinkedIds = folderLinkedIds,
                                folderOperation = folderOperation,
                                loading = operation.loading,
                                onImportFolder = { openFolderPicker() },
                                onSyncFolder = musicViewModel::syncFolderPlaylist,
                                onReselectFolder = {
                                    dismissOperationMessages()
                                    openFolderPicker()
                                },
                                onDismissFolderProblem = ::dismissOperationMessages,
                                modifier = Modifier.weight(1f)
                            )

                            MusicPage.PLAYER -> Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PlayerScreen(
                                    playback = playback,
                                    onPlayPause = musicViewModel::togglePlayPause,
                                    onPrevious = musicViewModel::previous,
                                    onNext = musicViewModel::next,
                                    onSeek = musicViewModel::seekTo,
                                    onShuffle = musicViewModel::toggleShuffle,
                                    onRepeat = musicViewModel::cycleRepeat,
                                    onVolumeChange = musicViewModel::setVolume,
                                    modifier = Modifier.weight(1f)
                                )

                                StereoButton(
                                    text = "CLEAR QUEUE",
                                    onClick = musicViewModel::clearQueue,
                                    enabled = playback.connected,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            MusicPage.CAR -> CarModeScreen(
                                playback = playback,
                                onPlayPause = musicViewModel::togglePlayPause,
                                onPrevious = musicViewModel::previous,
                                onNext = musicViewModel::next,
                                onSeek = musicViewModel::seekTo,
                                onShuffle = musicViewModel::toggleShuffle,
                                onRepeat = musicViewModel::cycleRepeat,
                                onVolumeChange = musicViewModel::setVolume,
                                onExit = {
                                    musicViewModel.setCarModeEnabled(false)
                                    navigateTo(MusicPage.PLAYER)
                                },
                                modifier = Modifier.weight(1f)
                            )

                            MusicPage.SIMULATOR -> HarmanSimulatorScreen(
                                onBack = { navigateTo(MusicPage.PLAYER) },
                                modifier = Modifier.weight(1f)
                            )

                            MusicPage.SETTINGS -> SettingsScreen(
                                selectedSkin = playerSkin,
                                onSelectSkin = musicViewModel::setPlayerSkin,
                                onBack = ::closeSettings,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (showBottomNav) {
                        RetroBottomNav(
                            selected = selectedNavItem,
                            onSelect = { item ->
                                navigateTo(
                                    when (item) {
                                        RetroNavItem.LIBRARY -> MusicPage.LIBRARY
                                        RetroNavItem.LISTS -> MusicPage.PLAYLISTS
                                        RetroNavItem.PLAYER -> MusicPage.PLAYER
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
