package com.maukingdom.musicplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun StereoHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = StereoColors.Panel,
                shape = RoundedCornerShape(5.dp)
            )
            .border(
                width = 1.dp,
                color = StereoColors.DisplayBorder,
                shape = RoundedCornerShape(5.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "MUSIC PLAYER",
                color = StereoColors.Amber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.2.sp
            )

            Text(
                text = "LOCAL AUDIO • DIGITAL STEREO",
                color = StereoColors.Green,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
        }

        Text(
            text = "●",
            color = StereoColors.Green,
            fontSize = 18.sp
        )
    }
}

@Composable
fun StereoNavButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(
            1.dp,
            if (selected) StereoColors.Amber else StereoColors.DisplayBorder
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                StereoColors.DisplayBorder
            } else {
                StereoColors.Panel
            },
            contentColor = if (selected) {
                StereoColors.Amber
            } else {
                StereoColors.Text
            }
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
fun StereoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    illuminated: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(
            1.dp,
            if (illuminated) StereoColors.Amber else StereoColors.DisplayBorder
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = StereoColors.PanelRaised,
            contentColor = if (illuminated) {
                StereoColors.Amber
            } else {
                StereoColors.Text
            },
            disabledContainerColor = StereoColors.Panel,
            disabledContentColor = StereoColors.MutedText
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
fun StereoDisplay(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = StereoColors.Display,
                shape = RoundedCornerShape(4.dp)
            )
            .border(
                width = 2.dp,
                color = StereoColors.DisplayBorder,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title.ifBlank { "NO TRACK SELECTED" },
            color = StereoColors.Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "SINGER  ${artist.ifBlank { "—" }}",
            color = StereoColors.Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "MOVIE / ALBUM  ${album.ifBlank { "—" }}",
            color = StereoColors.AmberDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StereoMessage(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = StereoColors.Panel,
                shape = RoundedCornerShape(4.dp)
            )
            .border(
                width = 1.dp,
                color = if (isError) StereoColors.Error else StereoColors.Green,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = if (isError) StereoColors.Error else StereoColors.Green,
            style = MaterialTheme.typography.bodyMedium
        )

        if (onDismiss != null) {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "DISMISS",
                    color = StereoColors.Amber
                )
            }
        }
    }
}

/**
 * A functional rotary-styled volume control. The slider provides accessible,
 * precise adjustment; the circular indicator gives it a physical-knob look.
 */
@Composable
fun StereoVolumeControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeValue = value.coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = StereoColors.Panel,
                shape = RoundedCornerShape(5.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StereoColors.PanelRaised,
                            StereoColors.Black
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 2.dp,
                    color = StereoColors.DisplayBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${(safeValue * 100).toInt()}",
                color = StereoColors.Amber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "VOLUME",
                color = StereoColors.Green,
                style = MaterialTheme.typography.labelLarge
            )

            Slider(
                value = safeValue,
                onValueChange = onValueChange,
                colors = SliderDefaults.colors(
                    thumbColor = StereoColors.Amber,
                    activeTrackColor = StereoColors.Amber,
                    inactiveTrackColor = StereoColors.DisplayBorder
                )
            )
        }
    }
}

@Composable
fun StereoEmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = StereoColors.MutedText,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
