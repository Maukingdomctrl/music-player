package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.player.RetroDialMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroDialMathTest {

    private val eps = 1e-4f

    @Test
    fun fractionMapsToArcFrom135To405() {
        assertEquals(135f, RetroDialMath.fractionToAngle(0f), eps)
        assertEquals(270f, RetroDialMath.fractionToAngle(0.5f), eps) // top
        assertEquals(405f, RetroDialMath.fractionToAngle(1f), eps)

        // Out-of-range fractions clamp.
        assertEquals(135f, RetroDialMath.fractionToAngle(-0.2f), eps)
        assertEquals(405f, RetroDialMath.fractionToAngle(1.7f), eps)
    }

    @Test
    fun angleMapsBackToFraction() {
        assertEquals(0f, RetroDialMath.angleToFraction(135f), eps)
        assertEquals(0.5f, RetroDialMath.angleToFraction(270f), eps)
        assertEquals(1f, RetroDialMath.angleToFraction(45f), eps)
        assertEquals(1f, RetroDialMath.angleToFraction(405f), eps)
        assertEquals(1f / 6f, RetroDialMath.angleToFraction(180f), eps)
        assertEquals(5f / 6f, RetroDialMath.angleToFraction(0f), eps)
        assertEquals(5f / 6f, RetroDialMath.angleToFraction(-360f), eps)

        for (f in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)) {
            assertEquals(
                f,
                RetroDialMath.angleToFraction(RetroDialMath.fractionToAngle(f)),
                eps
            )
        }
    }

    @Test
    fun bottomGapClampsToNearerEnd() {
        assertTrue(RetroDialMath.isInGap(90f))
        assertFalse(RetroDialMath.isInGap(45f))
        assertFalse(RetroDialMath.isInGap(135f))

        // Right half of the gap (45°..90°) is the max end, left half the min end.
        assertEquals(1f, RetroDialMath.angleToFraction(50f), eps)
        assertEquals(1f, RetroDialMath.angleToFraction(90f), eps)
        assertEquals(0f, RetroDialMath.angleToFraction(91f), eps)
        assertEquals(0f, RetroDialMath.angleToFraction(130f), eps)
    }

    @Test
    fun snapsToDeviceSteps() {
        assertEquals(0, RetroDialMath.snap(0f, 15))
        assertEquals(15, RetroDialMath.snap(1f, 15))
        assertEquals(7, RetroDialMath.snap(0.47f, 15))
        assertEquals(8, RetroDialMath.snap(0.51f, 15))
        assertEquals(0, RetroDialMath.snap(-1f, 15))
        assertEquals(25, RetroDialMath.snap(3f, 25))
        assertEquals(0, RetroDialMath.snap(0.5f, 0))

        // Every step survives the ViewModel's "truncate fraction × max" setter.
        for (steps in listOf(1, 7, 15, 25, 30)) {
            for (step in 0..steps) {
                val request = RetroDialMath.requestFraction(step, steps)
                assertTrue(request in 0f..1f)
                assertEquals(step, (request * steps).toInt())
            }
        }
    }

    @Test
    fun dragFromNearMaxPastTheEndStaysAtMax() {
        var value = 0.9f

        // Clockwise past the end: 405° (=45°), into the gap, out the far side.
        for (angle in listOf(45f, 60f, 90f, 120f, 134f, 136f, 150f)) {
            value = RetroDialMath.dragFraction(value, angle)
            assertEquals("at $angle°", 1f, value, eps)
        }

        // Turning back moves down normally again.
        value = RetroDialMath.dragFraction(value, 20f)
        assertEquals(RetroDialMath.angleToFraction(20f), value, eps)
        assertTrue(value < 1f)
    }

    @Test
    fun dragFromNearMinPastTheStartStaysAtMin() {
        var value = 0.1f

        // Anticlockwise past the start: 135°, into the gap, out the far side.
        for (angle in listOf(135f, 120f, 90f, 60f, 44f, 30f)) {
            value = RetroDialMath.dragFraction(value, angle)
            assertEquals("at $angle°", 0f, value, eps)
        }
    }

    @Test
    fun normalDragFollowsTheFinger() {
        assertEquals(0.5f, RetroDialMath.dragFraction(0.45f, 270f), eps)
        assertEquals(0.6f, RetroDialMath.dragFraction(0.5f, RetroDialMath.fractionToAngle(0.6f)), eps)
    }

    @Test
    fun angleOfVector() {
        assertEquals(0f, RetroDialMath.angleOf(1f, 0f), eps)
        assertEquals(90f, RetroDialMath.angleOf(0f, 1f), eps) // down
        assertEquals(180f, RetroDialMath.angleOf(-1f, 0f), eps)
        assertEquals(270f, RetroDialMath.angleOf(0f, -1f), eps) // up
        assertEquals(135f, RetroDialMath.angleOf(-1f, 1f), eps) // bottom-left
    }
}
