package com.maukingdom.musicplayer.ui.v2.library

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.maukingdom.musicplayer.ui.MusicViewModel

/**
 * The three ways to add music, plus RE-SELECT FOLDER. Same launchers,
 * permission request and ViewModel calls as Classic's MusicPlayerScreen;
 * the import work itself (persistable grants, scanning, linking) stays in
 * MusicViewModel / MusicImporter.
 */
@Stable
class LibraryPickers internal constructor(
    val openFolderPicker: () -> Unit,
    val openRelinkPicker: (playlistId: Long) -> Unit,
    val requestPhoneScan: () -> Unit,
    val openFilePicker: () -> Unit
)

@Composable
fun rememberLibraryPickers(musicViewModel: MusicViewModel): LibraryPickers {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            musicViewModel.scanMediaStore()
        } else {
            musicViewModel.showError("Audio-library access was denied. You can still select files.")
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
                musicViewModel.importDocuments(uris = uris, resultFlags = data?.flags ?: 0, offerPlaylist = true)
            }
        }
    }

    // Set while the picker is open for RE-SELECT FOLDER; saved across
    // process death so the result still relinks the right playlist.
    var relinkPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        val relinkId = relinkPlaylistId
        relinkPlaylistId = null

        // Null means the user cancelled the picker.
        if (treeUri != null) {
            if (relinkId != null) {
                musicViewModel.relinkFolder(relinkId, treeUri)
            } else {
                musicViewModel.importFolder(treeUri)
            }
        }
    }

    return remember(musicViewModel, context) {
        fun launchFolder() {
            try {
                folderLauncher.launch(null)
            } catch (_: ActivityNotFoundException) {
                relinkPlaylistId = null
                musicViewModel.showError("No folder picker is available.")
            }
        }

        LibraryPickers(
            openFolderPicker = {
                relinkPlaylistId = null
                launchFolder()
            },
            openRelinkPicker = { playlistId ->
                relinkPlaylistId = playlistId
                launchFolder()
            },
            requestPhoneScan = {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    musicViewModel.scanMediaStore()
                } else {
                    permissionLauncher.launch(permission)
                }
            },
            openFilePicker = {
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
        )
    }
}
