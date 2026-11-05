package com.maukingdom.musicplayer.ui.v2.studio

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.playback.SleepTimerMath
import com.maukingdom.musicplayer.playback.SleepTimerState
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import kotlinx.coroutines.delay

/** The chip's text for [state] at [nowElapsedMs]. Pure. */
fun sleepChipText(state: SleepTimerState, nowElapsedMs: Long): String? = when (state) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfSong -> "End of song"
    is SleepTimerState.Countdown -> SleepTimerMath.formatRemaining(state.endsAtElapsedMs - nowElapsedMs)
}

/**
 * Moon + "23:41 left" while a sleep timer runs. Ticks once a second inside
 * this chip only, so nothing else in the player recomposes.
 */
@Composable
fun SleepTimerChip(state: SleepTimerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text by produceState(sleepChipText(state, SystemClock.elapsedRealtime()), state) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            value = sleepChipText(state, now)
            if (state !is SleepTimerState.Countdown) break
            // Wake at the next whole second of the countdown.
            val remaining = state.endsAtElapsedMs - now
            delay((remaining % 1_000L).let { if (it <= 0L) 1_000L else it })
        }
    }
    val label = text ?: return
    val shape = RoundedCornerShape(ModernDimens.chip)

    Box(
        modifier = modifier
            .heightIn(min = ModernDimens.touchEffective)
            .clickable(role = Role.Button, onClickLabel = "Change sleep timer", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Sleep timer, $label" },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .border(ModernDimens.line, Color.White.copy(alpha = 0.28f), shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = Modern.type.caption, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/** Sleep timer choices: Off · 15 · 30 · 45 · 60 min · End of current song. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(state: SleepTimerState, onSelect: (arg: Int) -> Unit, onDismiss: () -> Unit) {
    val colors = Modern.colors
    val options = buildList {
        add(SleepTimerMath.ARG_OFF to "Off")
        SleepTimerMath.choices.forEach { add(it to "$it minutes") }
        add(SleepTimerMath.ARG_END_OF_SONG to "End of current song")
    }
    val selected = when (state) {
        SleepTimerState.Off -> SleepTimerMath.ARG_OFF
        SleepTimerState.EndOfSong -> SleepTimerMath.ARG_END_OF_SONG
        is SleepTimerState.Countdown -> state.minutes
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceHigh,
        contentColor = colors.textPrimary,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = ModernDimens.sheetTop, topEnd = ModernDimens.sheetTop),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textSecondary.copy(alpha = 0.4f)) },
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) }
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp).selectableGroup()) {
            Text(
                text = "Sleep timer",
                style = Modern.type.sectionTitle.copy(fontSize = 17.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp),
                color = colors.textPrimary,
                modifier = Modifier
                    .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, bottom = 4.dp)
                    .semantics { heading() }
            )
            Text(
                text = "Music fades out over the last 10 seconds, then pauses.",
                style = Modern.type.rowSubtitle,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, bottom = 8.dp)
            )
            options.forEach { (arg, label) ->
                val isSelected = arg == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            onSelect(arg)
                            onDismiss()
                        }
                        .padding(horizontal = ModernDimens.screenPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = Modern.type.rowTitle,
                        color = if (isSelected) colors.accent else colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(ModernDimens.iconSmall))
                    }
                }
            }
        }
    }
}
