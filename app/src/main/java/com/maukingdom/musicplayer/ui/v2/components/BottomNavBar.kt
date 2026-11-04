package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens

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
 * otherwise outline icon and secondary text.
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
            val isSelected = tab == selected

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = null,
                    tint = if (isSelected) colors.textPrimary else colors.textSecondary,
                    modifier = Modifier.size(ModernDimens.icon)
                )

                Text(
                    text = tab.label,
                    style = if (isSelected) Modern.type.navLabelSelected else Modern.type.navLabel,
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}
