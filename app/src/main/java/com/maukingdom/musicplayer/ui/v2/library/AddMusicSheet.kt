package com.maukingdom.musicplayer.ui.v2.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens

private val IconWell = Color(0xFF2A2A32)
private val RowHeight = 64.dp

/**
 * "Add music": import an SD-card folder (recommended), scan the phone, or
 * pick files. Choosing a row calls [onDismiss] first, then the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMusicSheet(
    onDismiss: () -> Unit,
    onImportFolder: () -> Unit,
    onScanPhone: () -> Unit,
    onSelectFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Modern.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun choose(action: () -> Unit) {
        onDismiss()
        action()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.surfaceHigh,
        contentColor = colors.textPrimary,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = ModernDimens.sheetTop, topEnd = ModernDimens.sheetTop),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textSecondary.copy(alpha = 0.4f)) }
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "Add music",
                style = Modern.type.sectionTitle.copy(fontSize = 17.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp),
                color = colors.textPrimary,
                modifier = Modifier
                    .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, bottom = 8.dp)
                    .semantics { heading() }
            )

            AddMusicRow(
                icon = Icons.Outlined.Folder,
                title = "Import SD-card folder",
                subtitle = "Becomes a playlist you can sync",
                recommended = true,
                onClick = { choose(onImportFolder) }
            )
            AddMusicRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = "Scan this phone",
                subtitle = "Find music already on the device",
                onClick = { choose(onScanPhone) }
            )
            AddMusicRow(
                icon = Icons.Outlined.AudioFile,
                title = "Select files",
                subtitle = "Pick individual songs",
                onClick = { choose(onSelectFiles) }
            )
        }
    }
}

@Composable
private fun AddMusicRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    recommended: Boolean = false
) {
    val colors = Modern.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ModernDimens.touchVisual)
                .clip(CircleShape)
                .background(IconWell),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(ModernDimens.iconSmall)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = Modern.type.playlistTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (recommended) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recommended",
                        style = Modern.type.navLabelSelected,
                        color = colors.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(ModernDimens.chip))
                            .background(colors.accent.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = Modern.type.rowSubtitle,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
