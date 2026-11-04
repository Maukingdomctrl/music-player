package com.maukingdom.musicplayer.ui.v2.studio

import android.graphics.Bitmap
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Player
import com.maukingdom.musicplayer.playback.visualiser.VISUALISER_BANDS
import com.maukingdom.musicplayer.playback.visualiser.VisualiserBus
import com.maukingdom.musicplayer.ui.player.PlayerActions
import com.maukingdom.musicplayer.ui.player.PlayerUi
import com.maukingdom.musicplayer.ui.v2.LocalReduceMotion
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val White64 = Color.White.copy(alpha = 0.64f)
private val White50 = Color.White.copy(alpha = 0.50f)
private val White24 = Color.White.copy(alpha = 0.24f)
private val Ink = Color(0xFF0B0B0F)
private val CoverEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The Modern Now Playing body ("v2 · Studio player"): cover, title and
 * format, the beat visualiser, seek bar, transport and volume with the
 * bass/treble sheet. One hue only: [tint] (the skin's colour or the
 * cover's), read while drawing so a tint change never recomposes. White
 * for everything else.
 */
@Composable
fun StudioPlayer(
    ui: PlayerUi,
    actions: PlayerActions,
    tint: State<Color>,
    formatBadge: String?,
    modifier: Modifier = Modifier
) {
    val reduceMotion = LocalReduceMotion.current
    var toneOpen by rememberSaveable { mutableStateOf(false) }

    val coverScale = animateFloatAsState(
        targetValue = if (ui.isPlaying || !ui.hasQueue) 1f else 0.9f,
        animationSpec = if (reduceMotion) snap() else tween(350, easing = CoverEasing),
        label = "coverScale"
    )

    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))

        // The largest square that fits: full width (less 24 dp margins) on tall screens.
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 24.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .align(Alignment.CenterHorizontally)
                .graphicsLayer {
                    scaleX = coverScale.value
                    scaleY = coverScale.value
                }
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(8.dp), clip = true)
                .semantics { contentDescription = "Album artwork, ${ui.title.ifBlank { "unknown title" }}" }
        ) {
            StudioCover(artwork = ui.artwork, tint = tint)
        }

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ui.hasQueue) ui.title.ifBlank { "Unknown title" } else "Nothing playing",
                    style = Modern.type.screenTitle.copy(fontSize = 22.sp, fontWeight = FontWeight(700), letterSpacing = (-0.5).sp),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (ui.hasQueue) ui.artist.ifBlank { "Unknown artist" } else "Pick a song from your library",
                    style = Modern.type.rowTitle.copy(fontWeight = FontWeight(400)),
                    color = White64,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (formatBadge != null) {
                Text(
                    text = formatBadge,
                    style = Modern.type.sheetEyebrow.copy(letterSpacing = 0.4.sp),
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .border(0.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        BeatVisualiser(
            tint = tint,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .height(36.dp)
        )

        Spacer(Modifier.height(4.dp))

        SeekBar(ui = ui, onSeek = actions::seekTo, modifier = Modifier.padding(horizontal = 24.dp))

        Spacer(Modifier.height(12.dp))

        Transport(ui = ui, actions = actions, tint = tint, modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(8.dp))

        VolumeRow(
            volume = ui.volume,
            onVolume = actions::setVolume,
            onOpenTone = { toneOpen = true },
            modifier = Modifier.padding(start = 24.dp, end = 12.dp)
        )

        Spacer(Modifier.height(12.dp))
    }

    if (toneOpen) {
        ToneSheet(ui = ui, actions = actions, tint = tint, onDismiss = { toneOpen = false })
    }
}

@Composable
private fun StudioCover(artwork: ImageBitmap?, tint: State<Color>) {
    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // No picture: a soft tint glow, like the mockup's placeholder.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = tint.value
                    drawRect(Color(0xFF16161B))
                    drawRect(
                        Brush.radialGradient(
                            0f to t.copy(alpha = 0.95f),
                            0.55f to t.copy(alpha = 0.55f),
                            1f to t.copy(alpha = 0.25f),
                            center = Offset(size.width * 0.3f, size.height * 0.25f),
                            radius = size.maxDimension * 0.85f
                        )
                    )
                }
        )
    }
}

/**
 * 40 bars, 3 dp wide, from the real audio (VisualiserBus). Asks for levels
 * only while shown and resumed; reads them while drawing only. With
 * animations off: low still bars, and no audio analysis at all.
 */
@Composable
fun BeatVisualiser(tint: State<Color>, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    if (!reduceMotion) {
        LifecycleResumeEffect(Unit) {
            VisualiserBus.request()
            onPauseOrDispose { VisualiserBus.release() }
        }
    }
    val levels = VisualiserBus.levels.collectAsState()

    Spacer(
        modifier = modifier
            .semantics { contentDescription = "Beat visualiser" }
            .drawWithCache {
                val t = tint.value
                // Gradient over the row: a bar's top is full tint, the floor 35 %.
                val brush = Brush.verticalGradient(listOf(t, t.copy(alpha = 0.35f)), startY = 0f, endY = size.height)
                val barWidth = 3.dp.toPx()
                val floor = 4.dp.toPx()
                val corner = CornerRadius(barWidth / 2f)
                val gap = (size.width - barWidth * VISUALISER_BANDS) / (VISUALISER_BANDS - 1)

                onDrawBehind {
                    val values = if (reduceMotion) STATIC_BAR_LEVELS else levels.value
                    for (i in 0 until VISUALISER_BANDS) {
                        val level = values.getOrElse(i) { 0f }
                        val h = floor + level * (size.height - floor)
                        val x = i * (barWidth + gap)
                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset(x, size.height - h),
                            size = Size(barWidth, h),
                            cornerRadius = corner
                        )
                    }
                }
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(ui: PlayerUi, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val duration = ui.durationMs
    val played = if (duration > 0L) (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: played
    val shownMs = (shown * duration).toLong()

    Column(modifier = modifier.fillMaxWidth()) {
        ThinSlider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0L,
            activeColor = Color.White,
            label = "Seek",
            valueText = "${studioClock(shownMs)} of ${if (duration > 0) studioClock(duration) else "unknown"}"
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(studioClock(shownMs), style = Modern.type.caption.copy(fontSize = 11.sp), color = White50)
            Text(studioRemaining(shownMs, duration), style = Modern.type.caption.copy(fontSize = 11.sp), color = White50)
        }
    }
}

/** A Material slider (48 dp touch, TalkBack adjustable) drawn as the mockup's 2 dp line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    activeColor: Color,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    steps: Int = 0,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = dragged || pressed

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        steps = steps,
        valueRange = valueRange,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = valueText
            },
        thumb = { Spacer(Modifier.size(0.dp)) },
        track = { state: SliderState ->
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .drawBehind {
                        val range = state.valueRange
                        val span = range.endInclusive - range.start
                        val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
                        val y = size.height / 2f
                        val stroke = 2.dp.toPx()
                        drawLine(White24, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                        drawLine(activeColor, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = stroke, cap = StrokeCap.Round)
                        // A small head while touching, so the finger sees where it is.
                        if (active) drawCircle(activeColor, radius = 6.dp.toPx(), center = Offset(size.width * fraction, y))
                    }
            )
        }
    )
}

@Composable
private fun Transport(ui: PlayerUi, actions: PlayerActions, tint: State<Color>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleButton(
            icon = Icons.Outlined.Shuffle,
            label = "Shuffle",
            state = if (ui.shuffle) "On" else "Off",
            on = ui.shuffle,
            tint = tint,
            onClick = actions::toggleShuffle
        )

        PlainButton(Icons.Filled.SkipPrevious, "Previous song", 56.dp, 36.dp, enabled = ui.hasQueue, onClick = actions::previous)

        val playLabel = if (ui.isPlaying) "Pause" else "Play"
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(8.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .drawBehind { drawRect(Color.White) }
                .clickable(enabled = ui.hasQueue, role = Role.Button, onClickLabel = playLabel, onClick = actions::playPause)
                .semantics { contentDescription = playLabel },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(30.dp)
            )
        }

        PlainButton(Icons.Filled.SkipNext, "Next song", 56.dp, 36.dp, enabled = ui.hasQueue, onClick = actions::next)

        val repeatOn = ui.repeatMode != Player.REPEAT_MODE_OFF
        ToggleButton(
            icon = if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            label = "Repeat",
            state = when (ui.repeatMode) {
                Player.REPEAT_MODE_ONE -> "One"
                Player.REPEAT_MODE_ALL -> "All"
                else -> "Off"
            },
            on = repeatOn,
            tint = tint,
            onClick = actions::cycleRepeat
        )
    }
}

/** Shuffle / repeat: tint and a 4 dp dot when on, white 64 % when off. */
@Composable
private fun ToggleButton(
    icon: ImageVector,
    label: String,
    state: String,
    on: Boolean,
    tint: State<Color>,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        // Reading the tint here recomposes this button only while the tint fades (400 ms).
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) tint.value else White64,
            modifier = Modifier.size(24.dp)
        )
        Spacer(
            modifier = Modifier
                .size(4.dp)
                .drawBehind { if (on) drawCircle(tint.value) }
        )
    }
}

@Composable
private fun PlainButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(iconSize))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeRow(volume: Float, onVolume: (Float) -> Unit, onOpenTone: () -> Unit, modifier: Modifier = Modifier) {
    val steps = 15
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.AutoMirrored.Outlined.VolumeDown, contentDescription = null, tint = White64, modifier = Modifier.size(18.dp))
        ThinSlider(
            value = volume.coerceIn(0f, 1f),
            onValueChange = onVolume,
            activeColor = Color.White.copy(alpha = 0.8f),
            label = "Volume",
            valueText = "${Math.round(volume * steps)} of $steps",
            steps = steps - 1,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = White64, modifier = Modifier.size(18.dp))
        PlainButton(
            icon = Icons.Outlined.Tune,
            label = "Equaliser, bass and treble",
            size = 48.dp,
            iconSize = 22.dp,
            enabled = true,
            onClick = onOpenTone,
            tint = Color.White.copy(alpha = 0.8f)
        )
    }
}

/** Bass and treble, -6..+6 dB, the same tone control as Classic's knobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToneSheet(ui: PlayerUi, actions: PlayerActions, tint: State<Color>, onDismiss: () -> Unit) {
    val colors = Modern.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceHigh,
        contentColor = Color.White,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = ModernDimens.sheetTop, topEnd = ModernDimens.sheetTop)
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bass and treble", style = Modern.type.sectionTitle.copy(fontSize = 17.sp), color = Color.White)
            if (!ui.toneAvailable) {
                Text(
                    "This phone did not allow the equaliser, so bass and treble cannot change.",
                    style = Modern.type.rowSubtitle,
                    color = colors.textSecondary
                )
            }
            ToneSlider("Bass", ui.bassDb, ui.toneAvailable, tint, actions::setBassDb)
            ToneSlider("Treble", ui.trebleDb, ui.toneAvailable, tint, actions::setTrebleDb)
        }
    }
}

@Composable
private fun ToneSlider(label: String, db: Int, enabled: Boolean, tint: State<Color>, onChange: (Int) -> Unit) {
    val text = if (db > 0) "+$db dB" else "$db dB"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Modern.type.rowTitle, color = Color.White, modifier = Modifier.width(64.dp))
        ThinSlider(
            value = db.toFloat(),
            onValueChange = { onChange(Math.round(it)) },
            activeColor = tint.value,
            label = label,
            valueText = text,
            enabled = enabled,
            steps = 11,
            valueRange = -6f..6f,
            modifier = Modifier.weight(1f)
        )
        Text(text, style = Modern.type.caption, color = Modern.colors.textSecondary, modifier = Modifier.width(52.dp).padding(start = 8.dp))
    }
}

/**
 * The Studio tint: the cover's dominant colour when [useCoverColour] and
 * the cover has one, else [skinTint]. Worked out once per cover, off the
 * main thread, from a 16×16 downscale.
 */
@Composable
fun rememberStudioTint(artwork: ImageBitmap?, useCoverColour: Boolean, skinTint: Color): Color {
    val cover by produceState<Color?>(initialValue = null, artwork, useCoverColour) {
        value = if (!useCoverColour || artwork == null) {
            null
        } else {
            withContext(Dispatchers.Default) { coverTint(artwork) }
        }
    }
    return cover ?: skinTint
}

private fun coverTint(artwork: ImageBitmap): Color? = try {
    var source = artwork.asAndroidBitmap()
    val copied = source.config == Bitmap.Config.HARDWARE
    if (copied) source = source.copy(Bitmap.Config.ARGB_8888, false)
    val small = Bitmap.createScaledBitmap(source, 16, 16, true)
    val pixels = IntArray(256)
    small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (small !== source) small.recycle()
    if (copied) source.recycle()
    dominantColor(pixels)?.let(::studioTintFromCover)?.let(::Color)
} catch (error: RuntimeException) {
    null
}
