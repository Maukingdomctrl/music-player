package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavPillTop = Color(0xFF2B2733)
private val NavPillBottom = Color(0xFF15131B)
private val NavSelectedTop = Color(0xFFFF8A3D)
private val NavSelectedBottom = Color(0xFFE85A0C)

enum class RetroNavItem(
    val label: String,
    val icon: ImageVector
) {
    LIBRARY("LIBRARY", Icons.Filled.LibraryMusic),
    LISTS("LISTS", Icons.AutoMirrored.Filled.QueueMusic),
    PLAYER("PLAYER", Icons.Filled.Album)
}

/** Full-size purple-to-black radial glow behind every screen. */
@Composable
fun RetroBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StereoColors.Fascia)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to StereoColors.BgTop,
                            0.45f to StereoColors.BgMid,
                            1f to StereoColors.Fascia
                        ),
                        center = Offset(size.width / 2f, 0f),
                        radius = maxOf(size.width, size.height)
                    )
                )
            },
        content = content
    )
}

@Composable
fun RetroHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MyCar Music",
            color = StereoColors.SilverLight,
            fontFamily = BrandFont,
            fontSize = 18.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )

        Text(
            text = "DIGITAL AUDIO PLAYER",
            color = StereoColors.MutedText,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            maxLines = 1
        )
    }
}

/**
 * Floating pill navigation with three equal tabs. The selected tab is an
 * orange gradient pill with an orange glow.
 */
@Composable
fun RetroBottomNav(
    selected: RetroNavItem?,
    onSelect: (RetroNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(32.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(elevation = 12.dp, shape = pillShape)
            .background(
                brush = Brush.verticalGradient(listOf(NavPillTop, NavPillBottom)),
                shape = pillShape
            )
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RetroNavItem.entries.forEach { item ->
            RetroNavTab(
                item = item,
                selected = item == selected,
                onClick = { onSelect(item) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun RetroNavTab(
    item: RetroNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabShape = RoundedCornerShape(26.dp)
    val contentColor = if (selected) StereoColors.Black else StereoColors.MutedText

    val decorated = if (selected) {
        modifier
            .shadow(
                elevation = 10.dp,
                shape = tabShape,
                ambientColor = NavSelectedTop,
                spotColor = NavSelectedTop
            )
            .background(
                brush = Brush.verticalGradient(
                    listOf(NavSelectedTop, NavSelectedBottom)
                ),
                shape = tabShape
            )
    } else {
        modifier
    }

    Column(
        modifier = decorated
            .clip(tabShape)
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )

        Text(
            text = item.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1
        )
    }
}
