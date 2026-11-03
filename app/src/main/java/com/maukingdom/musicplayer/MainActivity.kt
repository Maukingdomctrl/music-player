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
import com.maukingdom.musicplayer.ui.MusicPlayerScreen
import com.maukingdom.musicplayer.ui.MusicViewModel

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
            MusicPlayerScreen(musicViewModel)
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
