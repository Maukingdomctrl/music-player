package com.maukingdom.musicplayer

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.ui.AppLayout
import com.maukingdom.musicplayer.ui.MusicPlayerScreen
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.v2.ModernRoot

private val LoadingBackground = androidx.compose.ui.graphics.Color(0xFF07060C)

class MainActivity : ComponentActivity() {

    // The same instance MusicPlayerScreen gets from viewModel().
    private val musicViewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so system bar icons stay light even when
        // the device uses a light system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Only a fresh launch: not again after rotation or process restore.
        if (savedInstanceState == null) {
            handlePlayFromSearch(intent)
        }

        setContent {
            // Both layouts share musicViewModel, so switching never touches
            // playback. Until the saved choice is read, show only the dark
            // background rather than flashing the wrong layout.
            val layout by musicViewModel.appLayout.collectAsStateWithLifecycle()

            when (layout) {
                AppLayout.CLASSIC -> MusicPlayerScreen(musicViewModel)
                AppLayout.MODERN -> ModernRoot(musicViewModel)
                null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LoadingBackground)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayFromSearch(intent)
    }

    /** "Play X on MyCar Music" from the phone's Assistant. */
    private fun handlePlayFromSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return

        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        musicViewModel.playFromSearch(query)
    }
}
