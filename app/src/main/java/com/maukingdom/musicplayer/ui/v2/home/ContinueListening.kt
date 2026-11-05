package com.maukingdom.musicplayer.ui.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.PlaybackUiState
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.components.CoverImage
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import com.maukingdom.musicplayer.ui.v2.queue.continueSubtitle

private val PlayButton = 48.dp
private val Cover = 56.dp

/** What the Continue listening card shows. */
@Immutable
data class ContinueUi(
    val songId: String,
    val title: String,
    val subtitle: String,
    /** 0..1 of the current song. */
    val progress: Float
)

/**
 * The card's content when there is a queue that is not playing, else null
 * (while playing, the mini player covers it). Pure.
 */
fun continueUi(playback: PlaybackUiState, playlistName: String?, queueEdited: Boolean): ContinueUi? {
    val songId = playback.currentSongId ?: return null
    if (!playback.connected || playback.queueSize == 0 || playback.isPlaying) return null
    val duration = playback.durationMs
    return ContinueUi(
        songId = songId,
        title = playback.title.ifBlank { "Unknown title" },
        subtitle = continueSubtitle(playlistName, queueEdited, playback.positionMs, duration),
        progress = if (duration > 0L) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    )
}

/** Cover, title, "From Hindi 90s · 2:14 left", a thin progress line and a round play button. Tap resumes. */
@Composable
fun ContinueListeningCard(ui: ContinueUi, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Modern.colors
    val shape = RoundedCornerShape(ModernDimens.cardCover)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onResume)
            .clearAndSetSemantics {
                contentDescription = "Continue listening: ${ui.title}. ${ui.subtitle}"
                role = Role.Button
                onClick(label = "Resume") { onResume(); true }
            }
            // The progress line along the bottom edge, drawn (no layout).
            .drawBehind {
                val stroke = 2.dp.toPx()
                val y = size.height - stroke / 2f
                drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                drawLine(colors.accent, Offset(0f, y), Offset(size.width * ui.progress, y), strokeWidth = stroke)
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(uri = songCoverUri(ui.songId), size = Cover, corner = ModernDimens.coverSmall)
            Spacer(Modifier.width(ModernDimens.itemGap))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Continue listening",
                    style = Modern.type.caption,
                    color = colors.textPrimary.copy(alpha = 0.5f),
                    maxLines = 1
                )
                Text(
                    text = ui.title,
                    style = Modern.type.playlistTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = ui.subtitle,
                    style = Modern.type.rowSubtitle,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(PlayButton)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = colors.chipSelectedText, modifier = Modifier.size(28.dp))
            }
        }
    }
}
