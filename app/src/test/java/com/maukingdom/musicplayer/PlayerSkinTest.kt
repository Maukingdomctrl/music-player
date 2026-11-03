package com.maukingdom.musicplayer

import androidx.compose.ui.graphics.Color
import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.tokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSkinTest {

    @Test
    fun fromIdRoundTripsEverySkin() {
        assertEquals(7, PlayerSkin.entries.size)

        for (skin in PlayerSkin.entries) {
            assertEquals(skin, PlayerSkin.fromId(skin.id))
        }
    }

    @Test
    fun nullOrUnknownIdFallsBackToNeonWalkman() {
        assertEquals(PlayerSkin.NEON_WALKMAN, PlayerSkin.fromId(null))
        assertEquals(PlayerSkin.NEON_WALKMAN, PlayerSkin.fromId(""))
        assertEquals(PlayerSkin.NEON_WALKMAN, PlayerSkin.fromId("retired-skin"))
        assertEquals(PlayerSkin.NEON_WALKMAN, PlayerSkin.fromId("NEON"))
    }

    @Test
    fun idsAreUnique() {
        val ids = PlayerSkin.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everySkinHasTokens() {
        for (skin in PlayerSkin.entries) {
            assertNotNull("Missing tokens for $skin", skin.tokens)
        }
    }

    @Test
    fun textMeetsWcagContrastOnBackground() {
        for (skin in PlayerSkin.entries) {
            val tokens = skin.tokens
            val ratio = contrastRatio(tokens.text, tokens.background)

            assertTrue(
                "${skin.displayName}: text/background contrast is $ratio, needs >= 4.5",
                ratio >= 4.5
            )
        }
    }

    /** WCAG 2.x contrast ratio between two opaque colours. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
