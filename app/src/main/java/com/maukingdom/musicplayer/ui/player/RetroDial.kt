package com.maukingdom.musicplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure dial geometry, unit-tested on the JVM.
 *
 * Angles are in degrees, Compose style: 0° points right and angles grow
 * clockwise (y points down). The arc starts at [START_ANGLE] (bottom-left),
 * sweeps [SWEEP] degrees over the top and ends at 405° = 45° (bottom-right),
 * leaving a 90° gap centred at the bottom.
 */
object RetroDialMath {

    const val START_ANGLE = 135f
    const val SWEEP = 270f

    /** 0 → 135°, 1 → 405° (drawn as 45°). */
    fun fractionToAngle(fraction: Float): Float =
        START_ANGLE + SWEEP * fraction.coerceIn(0f, 1f)

    /** Degrees past [START_ANGLE], in [0, 360). */
    private fun offsetFromStart(angle: Float): Float {
        val offset = (angle - START_ANGLE) % 360f
        return if (offset < 0f) offset + 360f else offset
    }

    /** True if [angle] lies in the bottom gap, strictly between the ends. */
    fun isInGap(angle: Float): Boolean = offsetFromStart(angle) > SWEEP

    /**
     * Maps any angle to a fraction. Angles in the bottom gap clamp to the
     * nearer end: the right half of the gap is 1, the left half is 0.
     */
    fun angleToFraction(angle: Float): Float {
        val offset = offsetFromStart(angle)

        return when {
            offset <= SWEEP -> offset / SWEEP
            offset <= SWEEP + (360f - SWEEP) / 2f -> 1f
            else -> 0f
        }
    }

    /**
     * Next fraction while dragging from [previous] to [angle]. The value
     * never wraps across the gap: in the gap, or after a jump of more than
     * half the range between two touch events (which can only mean the
     * finger crossed the gap), it stays at the end it was nearest to.
     */
    fun dragFraction(previous: Float, angle: Float): Float {
        val nearEnd = if (previous >= 0.5f) 1f else 0f

        if (isInGap(angle)) return nearEnd

        val next = angleToFraction(angle)
        return if (abs(next - previous) > 0.5f) nearEnd else next
    }

    /** The nearest of [steps] + 1 positions (0..steps). */
    fun snap(fraction: Float, steps: Int): Int {
        if (steps <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * steps).roundToInt().coerceIn(0, steps)
    }

    /**
     * A fraction that a "truncate fraction × steps" setter turns back into
     * exactly [step]: the middle of the step, capped at 1. This avoids
     * float error such as (7 / 15f) × 15 = 6.9999 → 6.
     */
    fun requestFraction(step: Int, steps: Int): Float {
        if (steps <= 0) return 0f
        return ((step.coerceIn(0, steps) + 0.5f) / steps).coerceAtMost(1f)
    }

    /** Angle of the vector ([dx], [dy]) from the dial centre, in [0, 360). */
    fun angleOf(dx: Float, dy: Float): Float {
        val degrees = (atan2(dy, dx) * 180.0 / PI).toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }
}

private val DialTrack = Color(0xFF2E2838)
private val KnobTop = Color(0xFF2A2533)
private val KnobBottom = Color(0xFF131017)
private val KnobEdge = Color(0xFF0B090E)

/**
 * A 270° rotary dial with a dark knob, a dim track and a glowing value arc.
 *
 * When [onStepChange] is non-null and [enabled], dragging around the ring
 * moves the value in [steps] steps with a light haptic tick per step, and
 * accessibility services can set it as a slider. [center] is drawn on top of
 * the knob (for example a play/pause button); touches that start within the
 * inner 30% of the radius are left to it.
 */
@Composable
fun RetroDial(
    value: Float,
    arcColor: Color,
    size: Dp,
    steps: Int,
    contentDescription: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStepChange: ((Int) -> Unit)? = null,
    center: @Composable BoxScope.() -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val currentValue by rememberUpdatedState(value)
    val currentOnStep by rememberUpdatedState(onStepChange)
    val interactive = enabled && onStepChange != null

    // Snapped fraction shown while a drag is in progress.
    var dragDisplay by remember { mutableStateOf<Float?>(null) }
    val shown = (dragDisplay ?: value).coerceIn(0f, 1f)

    val gestures = if (interactive) {
        Modifier.pointerInput(steps) {
            var raw = 0f
            var lastStep = 0
            var active = false

            fun angleAt(position: Offset): Float =
                RetroDialMath.angleOf(
                    position.x - this.size.width / 2f,
                    position.y - this.size.height / 2f
                )

            fun emit() {
                val step = RetroDialMath.snap(raw, steps)
                dragDisplay = step.toFloat() / steps

                if (step != lastStep) {
                    lastStep = step
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnStep?.invoke(step)
                }
            }

            detectDragGestures(
                onDragStart = { start ->
                    val dx = start.x - this.size.width / 2f
                    val dy = start.y - this.size.height / 2f
                    val radius = this.size.width / 2f
                    active = dx * dx + dy * dy > (radius * 0.3f) * (radius * 0.3f)

                    if (active) {
                        lastStep = RetroDialMath.snap(currentValue, steps)
                        raw = RetroDialMath.angleToFraction(angleAt(start))
                        emit()
                    }
                },
                onDrag = { change, _ ->
                    if (active) {
                        change.consume()
                        raw = RetroDialMath.dragFraction(raw, angleAt(change.position))
                        emit()
                    }
                },
                onDragEnd = {
                    active = false
                    dragDisplay = null
                },
                onDragCancel = {
                    active = false
                    dragDisplay = null
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription

                if (onStepChange != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = shown,
                        range = 0f..1f,
                        steps = (steps - 1).coerceAtLeast(0)
                    )
                }

                if (interactive) {
                    setProgress { target ->
                        currentOnStep?.invoke(RetroDialMath.snap(target, steps))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(gestures),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.05f
            val glowStroke = stroke * 2.6f
            val inset = glowStroke / 2f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val arcTopLeft = Offset(inset, inset)

            drawArc(
                color = DialTrack,
                startAngle = RetroDialMath.START_ANGLE,
                sweepAngle = RetroDialMath.SWEEP,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val sweep = RetroDialMath.SWEEP * shown

            if (sweep > 0f) {
                drawArc(
                    color = arcColor.copy(alpha = 0.28f),
                    startAngle = RetroDialMath.START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = glowStroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = arcColor,
                    startAngle = RetroDialMath.START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            val knobRadius = this.size.minDimension / 2f - glowStroke * 1.6f
            val c = this.center

            drawCircle(color = KnobEdge, radius = knobRadius + stroke * 0.4f, center = c)
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(KnobTop, KnobBottom),
                    startY = c.y - knobRadius,
                    endY = c.y + knobRadius
                ),
                radius = knobRadius,
                center = c
            )

            val radians = RetroDialMath.fractionToAngle(shown) * PI.toFloat() / 180f
            val direction = Offset(cos(radians), sin(radians))

            drawLine(
                color = arcColor.copy(alpha = 0.35f),
                start = c + direction * (knobRadius * 0.66f),
                end = c + direction * (knobRadius * 0.9f),
                strokeWidth = stroke * 2.2f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arcColor,
                start = c + direction * (knobRadius * 0.66f),
                end = c + direction * (knobRadius * 0.9f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }

        center()
    }
}
