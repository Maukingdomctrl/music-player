package com.maukingdom.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.R

/**
 * Retro neon palette. The original property names are kept so existing
 * screens compile unchanged; "Amber" is now the primary orange and "Green"
 * the neon cyan accent.
 */
object StereoColors {
    val Fascia = Color(0xFF07060C)
    val Panel = Color(0xFF1F1B27)
    val PanelRaised = Color(0xFF2B2733)
    val Display = Color(0xFF03100E)
    val DisplayBorder = Color(0xFF3A3445)
    val Amber = Color(0xFFFF8A3D)
    val AmberDim = Color(0xFFC9420A)
    val Green = Color(0xFF5FFBE0)
    val Text = Color(0xFFECEDEF)
    val MutedText = Color(0xFFB8B3C4)
    val Error = Color(0xFFFF6B8A)
    val Black = Color(0xFF07060C)

    val NeonPink = Color(0xFFFF3EA5)
    val NeonPurple = Color(0xFFB86CFF)
    val NeonOrange = Color(0xFFFF7A1A)
    val Graphite = Color(0xFF1C1823)
    val GraphiteLight = Color(0xFF2E2838)
    val GraphiteDark = Color(0xFF141119)
    val BgTop = Color(0xFF3A1650)
    val BgMid = Color(0xFF1A0B2A)
}

/** Share Tech Mono (SIL OFL 1.1) for display-style text. */
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono))

/** Michroma (SIL OFL 1.1), used only for the "MyCar Music" wordmark. */
val BrandFont = FontFamily(Font(R.font.michroma))

private fun skinColorScheme(tokens: SkinTokens) = darkColorScheme(
    primary = tokens.accent,
    onPrimary = tokens.accentOn,
    secondary = tokens.secondary,
    onSecondary = tokens.background,
    tertiary = StereoColors.NeonPink,
    onTertiary = StereoColors.Black,
    background = tokens.background,
    onBackground = tokens.text,
    surface = tokens.panel,
    onSurface = tokens.text,
    surfaceVariant = tokens.panelRaised,
    onSurfaceVariant = tokens.muted,
    outline = StereoColors.DisplayBorder,
    error = StereoColors.Error,
    onError = StereoColors.Black
)

private val StereoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = ShareTechMono,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        letterSpacing = 1.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ShareTechMono,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        letterSpacing = 0.7.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.6.sp
    )
)

/**
 * Provides the skin's tokens and a matching Material colour scheme. Screens
 * not yet migrated keep using [StereoColors] directly.
 */
@Composable
fun MusicPlayerTheme(
    skin: PlayerSkin = PlayerSkin.NEON_WALKMAN,
    content: @Composable () -> Unit
) {
    val tokens = skin.tokens
    val colorScheme = remember(skin) { skinColorScheme(tokens) }

    CompositionLocalProvider(
        LocalPlayerSkin provides skin,
        LocalSkinTokens provides tokens
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StereoTypography,
            content = content
        )
    }
}
