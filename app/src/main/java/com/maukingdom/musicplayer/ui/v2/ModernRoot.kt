package com.maukingdom.musicplayer.ui.v2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.ui.MusicPlayerTheme
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.SettingsScreen
import com.maukingdom.musicplayer.ui.v2.components.BottomNavBar
import com.maukingdom.musicplayer.ui.v2.components.ModernTab
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton

/**
 * The Modern layout's root. For now a shell: top bar with the settings
 * gear (always reachable, so the user can switch back to Classic), a
 * placeholder per tab, and the bottom navigation.
 *
 * Shares [musicViewModel] with Classic, so switching layouts never touches
 * playback.
 */
@Composable
fun ModernRoot(musicViewModel: MusicViewModel) {
    val skin by musicViewModel.playerSkin.collectAsStateWithLifecycle()

    var tabName by rememberSaveable { mutableStateOf(ModernTab.HOME.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val tab = ModernTab.entries.firstOrNull { it.name == tabName } ?: ModernTab.HOME

    MusicPlayerTheme(skin = skin) {
        ModernTheme {
            val colors = Modern.colors

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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        TopBar(onSettings = { showSettings = true })

                        TabPlaceholder(
                            tab = tab,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        BottomNavBar(
                            selected = tab,
                            onSelect = { tabName = it.name }
                        )
                    }
                }
            }
        }
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

/** Empty per-tab content until the next steps fill it. */
@Composable
private fun TabPlaceholder(tab: ModernTab, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(ModernDimens.screenPadding),
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
