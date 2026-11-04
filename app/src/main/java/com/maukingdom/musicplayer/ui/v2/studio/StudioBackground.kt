package com.maukingdom.musicplayer.ui.v2.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.LocalSkinTokens

/**
 * The Studio player's one-hue background: [tint] at 42 % at the top, 14 %
 * at 38 % of the height, gone by 72 %, over [background]; plus a soft
 * tint glow behind the top. No other hue.
 */
fun DrawScope.drawStudioBackground(tint: Color, background: Color) {
    drawRect(background)
    drawRect(
        Brush.verticalGradient(
            0f to tint.copy(alpha = 0.42f),
            0.38f to tint.copy(alpha = 0.14f),
            0.72f to tint.copy(alpha = 0f)
        )
    )
    val glowCentre = Offset(size.width / 2f, size.width * 0.36f)
    drawRect(
        Brush.radialGradient(
            0f to tint.copy(alpha = 0.28f),
            1f to tint.copy(alpha = 0f),
            center = glowCentre,
            radius = size.width * 0.5f
        )
    )
}

/**
 * Modern-only setting shown in Settings under the layout choice: tint the
 * Studio player with the cover's colour (default) or the skin's.
 */
@Composable
fun CoverColourSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalSkinTokens.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Use cover colour", color = tokens.text, style = MaterialTheme.typography.titleMedium)
            Text(
                "Modern Now Playing takes its colour from the song's cover. Off: the skin's colour.",
                color = tokens.muted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        // The row handles the toggle, so TalkBack reads one switch.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = tokens.accent,
                checkedThumbColor = Color.White
            )
        )
    }
}
