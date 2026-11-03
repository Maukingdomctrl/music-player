package com.maukingdom.musicplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maukingdom.musicplayer.ui.PlayerSkin

/**
 * Renders the Now Playing page for [skin]. Skins without their own player
 * yet use [NeonWalkmanPlayer].
 */
@Composable
fun SkinPlayer(
    skin: PlayerSkin,
    ui: PlayerUi,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    when (skin) {
        PlayerSkin.NEON_WALKMAN -> NeonWalkmanPlayer(ui, actions, modifier)

        // Not built yet.
        PlayerSkin.GLASS_COCKPIT,
        PlayerSkin.INSTRUMENT,
        PlayerSkin.CALM_BLUE,
        PlayerSkin.SUNLIGHT,
        PlayerSkin.HEAD_UNIT,
        PlayerSkin.MINIMAL -> NeonWalkmanPlayer(ui, actions, modifier)
    }
}
