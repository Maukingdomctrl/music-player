package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavPillTop = Color(0xFF2B2733)
private val NavPillBottom = Color(0xFF15131B)
private val NeonSelectedTop = Color(0xFFFF8A3D)
private val NeonSelectedBottom = Color(0xFFE85A0C)
private val NeonOutline = listOf(
    Color(0xFF5FFBE0),
    Color(0xFFB86CFF),
    Color(0xFFFF3EA5)
)

enum class RetroNavItem(
    val label: String,
    val icon: ImageVector
) {
    LIBRARY("LIBRARY", Icons.Filled.LibraryMusic),
    LISTS("LISTS", Icons.AutoMirrored.Filled.QueueMusic),
    PLAYER("PLAYER", Icons.Filled.Album)
}

/** Full-size radial glow from the skin's top colour into its background. */
@Composable
fun RetroBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = LocalSkinTokens.current
    val middle = lerp(tokens.backgroundTop, tokens.background, 0.6f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to tokens.backgroundTop,
                            0.45f to middle,
                            1f to tokens.background
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
fun RetroHeader(
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null
) {
    val tokens = LocalSkinTokens.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onMenu != null) {
            IconButton(
                onClick = onMenu,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Open menu" }
            ) {
                MenuGlyph(color = tokens.text)
            }

            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "MyCar Music",
                color = tokens.text,
                fontFamily = BrandFont,
                fontSize = 18.sp,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )

            Text(
                text = "DIGITAL AUDIO PLAYER",
                color = tokens.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                maxLines = 1
            )
        }
    }
}

/** Three-line menu glyph with a shorter middle line. */
@Composable
private fun MenuGlyph(color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(20.dp, 13.dp, 20.dp).forEach { lineWidth ->
            Box(
                modifier = Modifier
                    .width(lineWidth)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

private val DrawerBottom = Color(0xFF16131C)
private const val BUSY = "Busy — please wait"

/**
 * Minimal navigation drawer content: plain 56dp rows grouped by section,
 * no cards or borders. Disabled rows are shown dimmed and are not clickable.
 */
@Composable
fun RetroDrawerSheet(
    skin: PlayerSkin,
    versionName: String,
    loading: Boolean,
    onClose: () -> Unit,
    onScanPhone: () -> Unit,
    onSelectFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onPlayerSkin: () -> Unit,
    onCarMode: () -> Unit
) {
    val tokens = LocalSkinTokens.current
    val shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    val edgeColor = StereoColors.NeonPurple.copy(alpha = 0.35f)

    ModalDrawerSheet(
        modifier = Modifier
            .width(316.dp)
            .background(
                brush = Brush.verticalGradient(listOf(tokens.panel, DrawerBottom)),
                shape = shape
            )
            .drawBehind {
                val inset = 28.dp.toPx()
                val x = size.width - 0.5.dp.toPx()
                drawLine(
                    color = edgeColor,
                    start = Offset(x, inset),
                    end = Offset(x, size.height - inset),
                    strokeWidth = 1.dp.toPx()
                )
            },
        drawerShape = shape,
        drawerContainerColor = Color.Transparent,
        drawerContentColor = tokens.text
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MyCar Music",
                    color = tokens.text,
                    fontFamily = BrandFont,
                    fontSize = 16.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close menu",
                        tint = tokens.muted
                    )
                }
            }

            DrawerSection("ADD MUSIC")
            DrawerRow(
                icon = Icons.Outlined.PhoneAndroid,
                label = "Scan this phone",
                description = if (loading) BUSY else "Find music already on this device",
                enabled = !loading,
                onClick = onScanPhone
            )
            DrawerRow(
                icon = Icons.Outlined.AudioFile,
                label = "Select files",
                description = if (loading) BUSY else "Pick individual audio files",
                enabled = !loading,
                onClick = onSelectFiles
            )
            DrawerRow(
                icon = Icons.Outlined.SdCard,
                label = "Import SD-card folder",
                description = if (loading) BUSY else "Creates a playlist you can sync",
                enabled = !loading,
                onClick = onImportFolder
            )

            DrawerSection("LOOK & SOUND")
            DrawerRow(
                icon = Icons.Outlined.Palette,
                label = "Player skin",
                description = "Change how the player looks",
                trailing = skin.shortName,
                onClick = onPlayerSkin
            )
            DrawerRow(
                icon = Icons.Outlined.GraphicEq,
                label = "Bass & treble",
                description = "Coming soon",
                enabled = false,
                onClick = {}
            )

            DrawerSection("DRIVING")
            DrawerRow(
                icon = Icons.Outlined.DirectionsCar,
                label = "Car mode",
                description = "Large controls for the dashboard",
                onClick = onCarMode
            )
            DrawerRow(
                icon = Icons.Outlined.Devices,
                label = "Android Auto",
                description = "Coming soon",
                enabled = false,
                onClick = {}
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Offline · no account · version $versionName",
                color = tokens.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        text = title,
        color = LocalSkinTokens.current.secondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .padding(start = 12.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: String? = null
) {
    val tokens = LocalSkinTokens.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.muted,
            modifier = Modifier.size(20.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = tokens.text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = description,
                color = tokens.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailing != null) {
            Text(
                text = trailing,
                color = tokens.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Floating pill navigation with three equal tabs. The selected tab uses the
 * skin's accent; Neon Walkman keeps its orange gradient.
 */
@Composable
fun RetroBottomNav(
    selected: RetroNavItem?,
    onSelect: (RetroNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalPlayerSkin.current
    val tokens = LocalSkinTokens.current
    val pillShape = RoundedCornerShape(32.dp)

    val pillColors = if (skin == PlayerSkin.NEON_WALKMAN) {
        listOf(NavPillTop, NavPillBottom)
    } else {
        listOf(tokens.panelRaised, tokens.panel)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(elevation = 12.dp, shape = pillShape)
            .background(
                brush = Brush.verticalGradient(pillColors),
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
    val skin = LocalPlayerSkin.current
    val tokens = LocalSkinTokens.current
    val tabShape = RoundedCornerShape(26.dp)

    val (selectedTop, selectedBottom) = if (skin == PlayerSkin.NEON_WALKMAN) {
        NeonSelectedTop to NeonSelectedBottom
    } else {
        tokens.accent to lerp(tokens.accent, Color.Black, 0.18f)
    }

    val contentColor = if (selected) tokens.accentOn else tokens.muted

    val decorated = if (selected) {
        modifier
            .shadow(
                elevation = 10.dp,
                shape = tabShape,
                ambientColor = selectedTop,
                spotColor = selectedTop
            )
            .background(
                brush = Brush.verticalGradient(listOf(selectedTop, selectedBottom)),
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

/**
 * Rounded panel. Neon Walkman gets a cyan-purple-pink gradient outline;
 * other skins get a subtle 1dp outline in their muted colour.
 */
@Composable
fun NeonOutlinePanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val skin = LocalPlayerSkin.current
    val tokens = LocalSkinTokens.current
    val shape = RoundedCornerShape(28.dp)

    val outlined = if (skin == PlayerSkin.NEON_WALKMAN) {
        modifier.border(
            width = 2.dp,
            brush = Brush.linearGradient(NeonOutline),
            shape = shape
        )
    } else {
        modifier.border(
            width = 1.dp,
            color = tokens.muted.copy(alpha = 0.3f),
            shape = shape
        )
    }

    Column(
        modifier = outlined
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(tokens.panelRaised, tokens.panel)
                )
            )
            .padding(contentPadding),
        content = content
    )
}
