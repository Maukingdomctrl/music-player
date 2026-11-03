package com.maukingdom.musicplayer.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A visual skin for the player. [id] is the value stored in preferences and
 * must never change once released.
 */
enum class PlayerSkin(val id: String, val displayName: String) {
    NEON_WALKMAN("neon", "Neon Walkman"),
    GLASS_COCKPIT("glass", "Glass Cockpit"),
    INSTRUMENT("instrument", "Instrument"),
    CALM_BLUE("calm", "Calm Blue"),
    SUNLIGHT("sunlight", "Sunlight Contrast"),
    HEAD_UNIT("head_unit", "Classic Head Unit"),
    MINIMAL("minimal", "Minimal");

    companion object {
        /** Unknown or missing IDs fall back to [NEON_WALKMAN]. */
        fun fromId(id: String?): PlayerSkin {
            return entries.firstOrNull { it.id == id } ?: NEON_WALKMAN
        }
    }
}

data class SkinTokens(
    val background: Color,
    val backgroundTop: Color,
    val panel: Color,
    val panelRaised: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentOn: Color,
    val secondary: Color,
    /** Album-art corner radius; large values render as a circle. */
    val artCorner: Dp
)

val PlayerSkin.tokens: SkinTokens
    get() = when (this) {
        PlayerSkin.NEON_WALKMAN -> SkinTokens(
            background = Color(0xFF07060C),
            backgroundTop = Color(0xFF3A1650),
            panel = Color(0xFF1E1A26),
            panelRaised = Color(0xFF2A2533),
            text = Color(0xFFECEDEF),
            muted = Color(0xFFB8B3C4),
            accent = Color(0xFFFF8A3D),
            accentOn = Color(0xFF2A0E00),
            secondary = Color(0xFF5FFBE0),
            artCorner = 12.dp
        )

        PlayerSkin.GLASS_COCKPIT -> SkinTokens(
            background = Color(0xFF000000),
            backgroundTop = Color(0xFF2A1020),
            panel = Color(0xFF121212),
            panelRaised = Color(0xFF1E1E1E),
            text = Color(0xFFFFFFFF),
            muted = Color(0xFFBDBDBD),
            accent = Color(0xFFFFFFFF),
            accentOn = Color(0xFF000000),
            secondary = Color(0xFFFFB27A),
            artCorner = 28.dp
        )

        PlayerSkin.INSTRUMENT -> SkinTokens(
            background = Color(0xFF0B0C0E),
            backgroundTop = Color(0xFF1B1D21),
            panel = Color(0xFF15171A),
            panelRaised = Color(0xFF24272C),
            text = Color(0xFFF2F2F2),
            muted = Color(0xFFA7ABB2),
            accent = Color(0xFFE8352A),
            accentOn = Color(0xFFFFFFFF),
            secondary = Color(0xFFA7ABB2),
            artCorner = 110.dp
        )

        PlayerSkin.CALM_BLUE -> SkinTokens(
            background = Color(0xFF0B111C),
            backgroundTop = Color(0xFF121B2C),
            panel = Color(0xFF141E30),
            panelRaised = Color(0xFF1B2740),
            text = Color(0xFFEEF2F8),
            muted = Color(0xFF9FB0C8),
            accent = Color(0xFF4C8DFF),
            accentOn = Color(0xFF0B111C),
            secondary = Color(0xFF9FB0C8),
            artCorner = 18.dp
        )

        PlayerSkin.SUNLIGHT -> SkinTokens(
            background = Color(0xFF000000),
            backgroundTop = Color(0xFF000000),
            panel = Color(0xFF000000),
            panelRaised = Color(0xFF111111),
            text = Color(0xFFFFFFFF),
            muted = Color(0xFFFFFFFF),
            accent = Color(0xFFFFD400),
            accentOn = Color(0xFF000000),
            secondary = Color(0xFFFFFFFF),
            artCorner = 10.dp
        )

        PlayerSkin.HEAD_UNIT -> SkinTokens(
            background = Color(0xFF08090B),
            backgroundTop = Color(0xFF1A1C20),
            panel = Color(0xFF24262B),
            panelRaised = Color(0xFF3A3D43),
            text = Color(0xFFD9DCE1),
            muted = Color(0xFF9EA3AB),
            accent = Color(0xFF9DFF6A),
            accentOn = Color(0xFF050B04),
            secondary = Color(0xFFD9DCE1),
            artCorner = 4.dp
        )

        PlayerSkin.MINIMAL -> SkinTokens(
            background = Color(0xFF1C1C1E),
            backgroundTop = Color(0xFF1C1C1E),
            panel = Color(0xFF252527),
            panelRaised = Color(0xFF2E2E31),
            text = Color(0xFFF5F5F5),
            muted = Color(0xFFA9A9AE),
            accent = Color(0xFFFF7A1A),
            accentOn = Color(0xFF1C1C1E),
            secondary = Color(0xFFF5F5F5),
            artCorner = 4.dp
        )
    }

/** Compact name for tight places such as the menu's trailing value. */
val PlayerSkin.shortName: String
    get() = when (this) {
        PlayerSkin.NEON_WALKMAN -> "Neon"
        PlayerSkin.GLASS_COCKPIT -> "Glass"
        PlayerSkin.INSTRUMENT -> "Instrument"
        PlayerSkin.CALM_BLUE -> "Calm Blue"
        PlayerSkin.SUNLIGHT -> "Sunlight"
        PlayerSkin.HEAD_UNIT -> "Head Unit"
        PlayerSkin.MINIMAL -> "Minimal"
    }

val LocalSkinTokens = staticCompositionLocalOf { PlayerSkin.NEON_WALKMAN.tokens }

/** The active skin, for skin-specific decoration beyond the tokens. */
val LocalPlayerSkin = staticCompositionLocalOf { PlayerSkin.NEON_WALKMAN }
