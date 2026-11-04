package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.modernTween

enum class ModernTab(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("Search", Icons.Filled.Search, Icons.Outlined.Search),
    LIBRARY("Your Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic)
}

/**
 * Three equal tabs, 72 dp tall, over a gradient that fades the content
 * above into the background. Selected: filled icon and white label;
 * otherwise outline icon and secondary text. Items press-scale to 97 %
 * and the icon crossfades on selection (instant with animations off).
 */
@Composable
fun BottomNavBar(
    selected: ModernTab,
    onSelect: (ModernTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ModernDimens.bottomNavHeight)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to colors.bg.copy(alpha = 0.92f),
                    1f to colors.bg
                )
            )
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModernTab.entries.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One tab: scales to 97 % while pressed; the icon crossfades between outline and filled. */
@Composable
private fun NavItem(
    tab: ModernTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) ModernMotion.PRESS_SCALE else 1f,
        animationSpec = modernTween(ModernMotion.PRESS_MS),
        label = "navPress"
    )
    val tint = if (selected) colors.textPrimary else colors.textSecondary

    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Tab,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Crossfade(
            targetState = selected,
            animationSpec = modernTween(ModernMotion.CROSSFADE_MS),
            label = "navIcon"
        ) { isSelected ->
            Icon(
                imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                contentDescription = null,
                tint = if (isSelected) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.size(ModernDimens.icon)
            )
        }

        Text(
            text = tab.label,
            style = if (selected) Modern.type.navLabelSelected else Modern.type.navLabel,
            color = tint,
            maxLines = 1
        )
    }
}
