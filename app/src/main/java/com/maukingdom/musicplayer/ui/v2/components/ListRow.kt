package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens

/**
 * A list row: cover, title, subtitle and an optional trailing slot. Defaults
 * are the 72 dp row with a 56 dp cover; songs use [songRow] sizes (60 / 44).
 * Text scales with the (clamped) font size, so the height is a minimum.
 */
@Composable
fun ListRow(
    coverUri: String,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = Modern.type.rowTitle,
    titleColor: Color = Modern.colors.textPrimary,
    rowHeight: Dp = ModernDimens.listRowHeight,
    coverSize: Dp = ModernDimens.listRowCover,
    coverCorner: Dp = ModernDimens.coverSmall,
    onClickLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(uri = coverUri, size = coverSize, corner = coverCorner)

        Spacer(modifier = Modifier.width(ModernDimens.itemGap))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = Modern.type.rowSubtitle,
                    color = Modern.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Song-row sizes for [ListRow]: 60 dp row, 44 dp cover. */
object SongRowSizes {
    val height = ModernDimens.songRowHeight
    val cover = ModernDimens.songRowCover
}
