package com.maukingdom.musicplayer.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.maukingdom.musicplayer.playback.ToneControl
import com.maukingdom.musicplayer.ui.BrandFont
import com.maukingdom.musicplayer.ui.ShareTechMono
import com.maukingdom.musicplayer.ui.StereoColors
import com.maukingdom.musicplayer.ui.repeatLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val Cyan = StereoColors.Green
private val Purple = StereoColors.NeonPurple
private val Pink = StereoColors.NeonPink
private val Orange = StereoColors.NeonOrange
private val VfdBackground = StereoColors.Display

/** Translucent graphite: the blurred cover tints through. */
private val PanelFill = StereoColors.Graphite.copy(alpha = 0.72f)
private val PanelText = Color(0xFFECEDEF)

/**
 * Lighter than the skin's muted colour so it keeps 4.5:1 even when a bright
 * cover shows through the panel.
 */
private val PanelMuted = Color(0xFFD2CDE0)
private val ControlFill = Color(0xFF15121B)
private val CentreFill = Color(0xFF0E0B12)
private val NeonOutline = listOf(Cyan, Purple, Pink)

private const val METER_BARS = 26

/**
 * The Neon Walkman Now Playing page: a blurred cover backdrop with one
 * translucent dark panel holding the display, seek bar, transport, volume
 * dial and (disabled) bass/treble knobs. Sized to fit a 390×844 dp phone
 * without scrolling; on shorter screens the volume dial shrinks first.
 */
@Composable
fun NeonWalkmanPlayer(
    ui: PlayerUi,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
    ) {
        CoverBackdrop(artwork = ui.artwork)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val compact = maxHeight < 680.dp

            NeonPanel(
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                PanelContent(ui = ui, actions = actions, compact = compact)
            }
        }
    }
}

/**
 * The current cover, full-bleed, blurred and darkened towards the bottom.
 * With no cover nothing is drawn, so the page's RetroBackground shows.
 */
@Composable
private fun CoverBackdrop(artwork: ImageBitmap?) {
    if (artwork == null) return

    Box(modifier = Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= 31) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
            )
        } else {
            // Modifier.blur has no effect before API 31: scale down to ~24 px
            // and let bilinear upscaling blur it.
            val tiny = remember(artwork) { downscaled(artwork, 24) }

            Image(
                bitmap = tiny,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.5f to Color.Black.copy(alpha = 0.85f),
                        1f to Color.Black
                    )
                )
        )
    }
}

private fun downscaled(artwork: ImageBitmap, width: Int): ImageBitmap {
    return try {
        val source = artwork.asAndroidBitmap()
        val height = (width.toFloat() * source.height / source.width)
            .roundToInt()
            .coerceAtLeast(1)

        Bitmap.createScaledBitmap(source, width, height, true).asImageBitmap()
    } catch (_: Exception) {
        artwork
    }
}

@Composable
private fun NeonPanel(
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(30.dp)

    Column(
        modifier = modifier
            .drawBehind {
                // Soft purple glow outside the outline.
                val corner = 30.dp.toPx()
                for (ring in 1..4) {
                    val spread = ring * 2.dp.toPx()
                    drawRoundRect(
                        color = Purple.copy(alpha = 0.16f - ring * 0.03f),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(corner + spread),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(NeonOutline),
                shape = shape
            )
            .clip(shape)
            .background(PanelFill)
            .padding(
                horizontal = 16.dp,
                vertical = if (compact) 10.dp else 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun ColumnScope.PanelContent(
    ui: PlayerUi,
    actions: PlayerActions,
    compact: Boolean
) {
    val transportEnabled = ui.connected && ui.hasQueue
    val context = LocalContext.current

    // The device's music volume steps (usually 15 or 25).
    val volumeSteps = remember(context) { musicVolumeSteps(context) }
    val volumeStep = RetroDialMath.snap(ui.volume, volumeSteps)

    BrandRow(ui = ui, actions = actions)

    VfdDisplay(ui = ui, compact = compact)

    NeonSeekBar(
        positionMs = ui.positionMs,
        durationMs = ui.durationMs,
        enabled = transportEnabled && ui.durationMs > 0L,
        onSeek = actions::seekTo
    )

    // Takes what is left, up to the full dial size.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
        contentAlignment = Alignment.Center
    ) {
        val buttonSize = 60.dp
        val gap = 12.dp
        val dialSize = min(164.dp, min(maxHeight, maxWidth - (buttonSize + gap) * 2))
            .coerceAtLeast(96.dp)

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportButton(
                icon = Icons.Filled.SkipPrevious,
                description = "Previous track",
                enabled = transportEnabled,
                size = buttonSize,
                onClick = actions::previous
            )

            Spacer(modifier = Modifier.width(gap))

            RetroDial(
                value = volumeStep.toFloat() / volumeSteps,
                arcColor = Orange,
                size = dialSize,
                steps = volumeSteps,
                contentDescription = "Volume",
                stateDescription = "${(ui.volume * 100f).roundToInt()}%",
                onStepChange = { step ->
                    actions.setVolume(RetroDialMath.requestFraction(step, volumeSteps))
                }
            ) {
                PlayPauseButton(
                    playing = ui.isPlaying,
                    enabled = transportEnabled,
                    size = (dialSize * 0.4f).coerceAtLeast(48.dp),
                    onClick = actions::playPause
                )
            }

            Spacer(modifier = Modifier.width(gap))

            TransportButton(
                icon = Icons.Filled.SkipNext,
                description = "Next track",
                enabled = transportEnabled,
                size = buttonSize,
                onClick = actions::next
            )
        }
    }

    Text(
        text = String.format(Locale.ROOT, "VOLUME %02d", volumeStep),
        color = PanelText,
        fontFamily = ShareTechMono,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.clearAndSetSemantics { }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToneKnob(
            label = "BASS",
            spokenName = "Bass",
            db = ui.bassDb,
            color = Cyan,
            available = ui.toneAvailable,
            onDbChange = actions::setBassDb
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            ModePill(
                icon = Icons.Filled.Shuffle,
                text = if (ui.shuffle) "SHUFFLE ON" else "SHUFFLE OFF",
                active = ui.shuffle,
                activeColor = Cyan,
                enabled = ui.connected,
                interaction = Modifier.toggleable(
                    value = ui.shuffle,
                    enabled = ui.connected,
                    role = Role.Switch,
                    onValueChange = { actions.toggleShuffle() }
                )
            )

            ModePill(
                icon = if (ui.repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                text = repeatLabel(ui.repeatMode),
                active = ui.repeatMode != Player.REPEAT_MODE_OFF,
                activeColor = Pink,
                enabled = ui.connected,
                interaction = Modifier.clickable(
                    enabled = ui.connected,
                    role = Role.Button,
                    onClickLabel = "Change repeat mode",
                    onClick = actions::cycleRepeat
                )
            )
        }

        ToneKnob(
            label = "TREBLE",
            spokenName = "Treble",
            db = ui.trebleDb,
            color = Pink,
            available = ui.toneAvailable,
            onDbChange = actions::setTrebleDb
        )
    }
}

private fun musicVolumeSteps(context: Context): Int {
    return try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    } catch (_: Exception) {
        15
    }
}

@Composable
private fun BrandRow(ui: PlayerUi, actions: PlayerActions) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumbnail(artwork = ui.artwork)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MyCar Music",
                color = PanelText,
                fontFamily = BrandFont,
                fontSize = 16.sp,
                maxLines = 1
            )
            Text(
                text = "DIGITAL AUDIO PLAYER",
                color = PanelMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                maxLines = 1
            )
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = PanelText
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = StereoColors.PanelRaised
            ) {
                DropdownMenuItem(
                    text = { Text("Clear queue", color = PanelText) },
                    leadingIcon = {
                        Icon(Icons.Outlined.ClearAll, contentDescription = null, tint = PanelMuted)
                    },
                    enabled = ui.connected && ui.hasQueue,
                    onClick = {
                        menuOpen = false
                        actions.clearQueue()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Change skin", color = PanelText) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Palette, contentDescription = null, tint = PanelMuted)
                    },
                    onClick = {
                        menuOpen = false
                        actions.openSkinPicker()
                    }
                )
            }
        }
    }
}

@Composable
private fun CoverThumbnail(artwork: ImageBitmap?) {
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier
        .size(48.dp)
        .clip(shape)
        .border(1.dp, Purple.copy(alpha = 0.5f), shape)

    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = base
        )
    } else {
        Box(
            modifier = base.background(
                Brush.linearGradient(listOf(StereoColors.BgTop, ControlFill))
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Album,
                contentDescription = null,
                tint = Cyan,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun VfdDisplay(ui: PlayerUi, compact: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val glow = Shadow(color = Cyan.copy(alpha = 0.75f), blurRadius = 18f)

    val source = ui.playlistName?.uppercase() ?: "ALL SONGS"
    val track = String.format(Locale.ROOT, "TRK %03d/%03d", ui.trackNumber, ui.trackCount)

    val title = when {
        !ui.connected -> "CONNECTING…"
        !ui.hasQueue -> "PICK A SONG FROM LIBRARY"
        else -> ui.title.ifBlank { "Unknown title" }.uppercase()
    }

    val subtitle = when {
        ui.error != null -> "Playback error: ${ui.error}"
        !ui.hasQueue -> "or open a playlist in Lists"
        else -> ui.artist.ifBlank { "Unknown artist" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(VfdBackground)
            .border(1.dp, Cyan.copy(alpha = 0.22f), shape)
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp)
            .semantics(mergeDescendants = true) { }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = source,
                color = Pink,
                fontFamily = ShareTechMono,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = track,
                color = Pink,
                fontFamily = ShareTechMono,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                maxLines = 1
            )
        }

        Text(
            text = if (ui.hasQueue) formatClock(ui.positionMs) else "00:00",
            style = TextStyle(
                color = Cyan,
                fontFamily = ShareTechMono,
                fontSize = if (compact) 46.sp else 56.sp,
                lineHeight = if (compact) 50.sp else 60.sp,
                shadow = glow
            ),
            maxLines = 1
        )

        Text(
            text = title,
            style = TextStyle(
                color = Color(0xFFDFFFF9),
                fontFamily = ShareTechMono,
                fontSize = 20.sp,
                letterSpacing = 0.5.sp,
                shadow = glow
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = subtitle,
            color = if (ui.error != null) Pink else Cyan.copy(alpha = 0.85f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

        LevelMeter(
            playing = ui.isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 16.dp else 20.dp)
        )
    }
}

/**
 * Decorative level meter. It moves only while [playing] and freezes where it
 * is when paused. It does not reflect the real audio level.
 */
@Composable
private fun LevelMeter(playing: Boolean, modifier: Modifier = Modifier) {
    val phase = remember { mutableFloatStateOf(1.7f) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect

        val base = phase.floatValue
        var start = -1L

        while (true) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                phase.floatValue = base + (now - start) / 1_000_000_000f
            }
        }
    }

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (METER_BARS - 1)) / METER_BARS
        val corner = CornerRadius(1.dp.toPx())
        val t = phase.floatValue

        for (index in 0 until METER_BARS) {
            val x = index * (barWidth + gap)
            val position = index / (METER_BARS - 1f)
            val color = if (position < 0.5f) {
                lerp(Cyan, Purple, position * 2f)
            } else {
                lerp(Purple, Pink, (position - 0.5f) * 2f)
            }

            drawRoundRect(
                color = color.copy(alpha = 0.12f),
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = corner
            )

            val height = size.height * meterLevel(index, t)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = corner
            )
        }
    }
}

private fun meterLevel(index: Int, t: Float): Float {
    val a = sin(t * (3.1f + (index % 7) * 0.53f) + index * 0.9f)
    val b = sin(t * (1.7f + (index % 5) * 0.41f) + index * 2.3f)
    // Lower bands sit a little higher, like music usually does.
    val envelope = 1f - index / (METER_BARS * 2.2f)

    return (0.12f + 0.88f * abs(a * 0.6f + b * 0.4f) * envelope).coerceIn(0.08f, 1f)
}

/**
 * A 6 dp gradient seek bar in a 52 dp touch area, with elapsed and total
 * time underneath. Tap or drag to seek; the seek happens on release.
 */
@Composable
private fun NeonSeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit
) {
    val duration = durationMs.coerceAtLeast(0L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)

    // Empty bar and "--:--" until the player knows the duration.
    val fraction = if (duration > 0L) {
        dragFraction ?: playedFraction(positionMs, duration)
    } else {
        0f
    }
    val shownPosition = if (dragFraction != null) (fraction * duration).toLong() else positionMs

    val elapsedText = formatClock(shownPosition)
    val totalText = formatTotal(duration)
    val horizontalInset = 8.dp

    val gestures = if (enabled) {
        Modifier
            .pointerInput(duration) {
                val inset = horizontalInset.toPx()
                detectTapGestures { offset ->
                    val f = ((offset.x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    currentOnSeek((f * duration).toLong())
                }
            }
            .pointerInput(duration) {
                val inset = horizontalInset.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)

                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = at(offset.x) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = at(change.position.x)
                    },
                    onDragEnd = {
                        dragFraction?.let { currentOnSeek((it * duration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                contentDescription = "Seek"
                stateDescription = "$elapsedText of $totalText"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)

                if (enabled) {
                    setProgress { target ->
                        currentOnSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(gestures)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = horizontalInset)
                .align(Alignment.TopCenter)
        ) {
            val trackHeight = 6.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val corner = CornerRadius(trackHeight / 2f)

            drawRoundRect(
                color = StereoColors.DisplayBorder,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )

            val filled = size.width * fraction
            if (filled > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Cyan, Pink),
                        startX = 0f,
                        endX = size.width
                    ),
                    topLeft = Offset(0f, top),
                    size = Size(filled, trackHeight),
                    cornerRadius = corner
                )
            }

            val thumb = Offset(filled, size.height / 2f)
            drawCircle(color = Pink.copy(alpha = 0.3f), radius = 11.dp.toPx(), center = thumb)
            drawCircle(color = Pink, radius = 7.dp.toPx(), center = thumb)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = elapsedText,
                color = PanelMuted,
                fontFamily = ShareTechMono,
                fontSize = 12.sp
            )
            Text(
                text = totalText,
                color = PanelMuted,
                fontFamily = ShareTechMono,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                drawCircle(
                    color = Cyan.copy(alpha = 0.18f),
                    radius = this.size.minDimension / 2f + 3.dp.toPx()
                )
            }
            .clip(CircleShape)
            .background(ControlFill)
            .border(2.dp, Cyan, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(CentreFill)
            .border(1.dp, Orange.copy(alpha = 0.45f), CircleShape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Orange.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    radius = this.size.minDimension / 2f
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Orange,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/**
 * BASS / TREBLE: -6..+6 dB in 1 dB steps with one haptic tick per step and
 * a wider catch zone at 0. Double-tap resets to 0. [RetroDial] only draws
 * it; the gestures and the dB slider semantics are handled here.
 */
@Composable
private fun ToneKnob(
    label: String,
    spokenName: String,
    db: Int,
    color: Color,
    available: Boolean,
    onDbChange: (Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val currentDb by rememberUpdatedState(db)
    val currentOnChange by rememberUpdatedState(onDbChange)

    // The value shown while a drag is in progress.
    var dragDb by remember { mutableStateOf<Int?>(null) }
    val shownDb = dragDb ?: db

    fun set(newDb: Int) {
        val clamped = ToneControl.clampDb(newDb)
        if (clamped != currentDb) currentOnChange(clamped)
    }

    val gestures = if (available) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentDb != 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnChange(0)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                var raw = 0f
                var last = 0

                fun angleAt(position: Offset): Float = RetroDialMath.angleOf(
                    position.x - size.width / 2f,
                    position.y - size.height / 2f
                )

                fun emit() {
                    val next = ToneControl.dbForFraction(raw)
                    dragDb = next

                    if (next != last) {
                        last = next
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnChange(next)
                    }
                }

                detectDragGestures(
                    onDragStart = { start ->
                        last = currentDb
                        raw = RetroDialMath.angleToFraction(angleAt(start))
                        emit()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        raw = RetroDialMath.dragFraction(raw, angleAt(change.position))
                        emit()
                    },
                    onDragEnd = { dragDb = null },
                    onDragCancel = { dragDb = null }
                )
            }
    } else {
        Modifier
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .then(gestures)
                .clearAndSetSemantics {
                    if (available) {
                        contentDescription = spokenName
                        stateDescription = spokenDb(shownDb)
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = shownDb.toFloat(),
                            range = ToneControl.MIN_DB.toFloat()..ToneControl.MAX_DB.toFloat(),
                            steps = ToneControl.STEPS - 1
                        )
                        setProgress { target ->
                            set(target.roundToInt())
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Reset $spokenName to 0") {
                                set(0)
                                true
                            }
                        )
                    } else {
                        contentDescription = "Sound effects not available on this phone"
                        disabled()
                    }
                }
        ) {
            RetroDial(
                value = ToneControl.fractionForDb(shownDb),
                arcColor = color,
                size = 72.dp,
                steps = ToneControl.STEPS,
                contentDescription = spokenName,
                stateDescription = spokenDb(shownDb),
                enabled = available
            )
        }

        Text(
            text = "$label ${ToneControl.formatDb(shownDb)}",
            color = PanelText,
            fontFamily = ShareTechMono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .alpha(if (available) 1f else 0.4f)
                .clearAndSetSemantics { }
        )
    }
}

private fun spokenDb(db: Int): String = when {
    db > 0 -> "plus $db dB"
    db < 0 -> "minus ${-db} dB"
    else -> "0 dB"
}

@Composable
private fun ModePill(
    icon: ImageVector,
    text: String,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean,
    interaction: Modifier
) {
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (active) activeColor.copy(alpha = 0.16f) else ControlFill)
            .border(
                width = 1.5.dp,
                color = if (active) activeColor else PanelMuted.copy(alpha = 0.35f),
                shape = shape
            )
            .then(interaction)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) activeColor else PanelMuted,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = PanelText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            maxLines = 1
        )
    }
}
