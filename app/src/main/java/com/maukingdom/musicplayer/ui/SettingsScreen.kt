package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val SKIN_FOOTER =
    "The skin changes the app's colours. Your music and playlists are not affected."

@Composable
fun SettingsScreen(
    selectedSkin: PlayerSkin,
    onSelectSkin: (PlayerSkin) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalSkinTokens.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = tokens.text
                )
            }

            Text(
                text = "Settings",
                color = tokens.text,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "PLAYER SKIN",
                    color = tokens.secondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { heading() }
                )
            }

            items(PlayerSkin.entries, key = { it.id }) { skin ->
                SkinCard(
                    skin = skin,
                    selected = skin == selectedSkin,
                    onClick = { onSelectSkin(skin) }
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                NeonOutlinePanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = SKIN_FOOTER,
                        color = tokens.muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SkinCard(
    skin: PlayerSkin,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current = LocalSkinTokens.current
    val shape = RoundedCornerShape(20.dp)

    val ringed = if (selected) {
        modifier.border(2.dp, current.accent, shape)
    } else {
        modifier.border(1.dp, current.muted.copy(alpha = 0.3f), shape)
    }

    Column(
        modifier = ringed
            .clip(shape)
            .background(current.panel)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SkinPreview(tokens = skin.tokens)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = skin.displayName,
                color = current.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )

            if (selected) {
                Text(
                    text = "IN USE",
                    color = current.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

/** A miniature player drawn with another skin's tokens. */
@Composable
private fun SkinPreview(tokens: SkinTokens) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(tokens.backgroundTop, tokens.background))
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(tokens.artCorner))
                    .background(
                        Brush.linearGradient(listOf(tokens.panelRaised, tokens.panel))
                    )
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewBar(color = tokens.text, width = 0.9f, height = 7)
                PreviewBar(color = tokens.muted, width = 0.6f, height = 5)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(tokens.panelRaised)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight()
                        .background(tokens.accent)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tokens.accent)
            )
        }
    }
}

@Composable
private fun PreviewBar(color: Color, width: Float, height: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width)
            .height(height.dp)
            .clip(CircleShape)
            .background(color)
    )
}
