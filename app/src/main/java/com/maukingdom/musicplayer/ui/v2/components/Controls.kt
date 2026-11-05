package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens

/**
 * A plain icon button: 48 dp touch area with a bounded 44 dp ripple and
 * no background.
 */
@Composable
fun V2IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Modern.colors.textPrimary
) {
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(ModernDimens.touchEffective)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, radius = ModernDimens.touchVisual / 2),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = null,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ModernDimens.icon)
        )
    }
}

/**
 * A filter chip: 32 dp pill in a 48 dp touch area. Selected chips use the
 * skin accent with dark text.
 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = Modern.colors
    val shape = RoundedCornerShape(ModernDimens.chip)

    Box(
        modifier = modifier
            .heightIn(min = ModernDimens.touchEffective)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = Modern.type.chip,
            color = if (selected) colors.chipSelectedText else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .background(if (selected) colors.chipSelected else colors.chip)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/** A section title with an optional small action on the right ("Show all"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.touchEffective),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = Modern.type.sectionTitle,
            color = Modern.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )

        if (actionLabel != null && onAction != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = ModernDimens.touchEffective)
                    .clip(RoundedCornerShape(ModernDimens.chip))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    style = Modern.type.caption,
                    color = Modern.colors.textSecondary
                )
            }
        }
    }
}

