package com.maukingdom.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StereoColors {
    val Fascia = Color(0xFF101010)
    val Panel = Color(0xFF242321)
    val PanelRaised = Color(0xFF34312D)
    val Display = Color(0xFF1B120A)
    val DisplayBorder = Color(0xFF68401B)
    val Amber = Color(0xFFFFA12D)
    val AmberDim = Color(0xFFB86C1D)
    val Green = Color(0xFF8FBC61)
    val Text = Color(0xFFF0E7D8)
    val MutedText = Color(0xFFB5AA99)
    val Error = Color(0xFFFF8A65)
    val Black = Color(0xFF080808)
}

private val StereoColorScheme = darkColorScheme(
    primary = StereoColors.Amber,
    onPrimary = StereoColors.Black,
    secondary = StereoColors.Green,
    onSecondary = StereoColors.Black,
    background = StereoColors.Fascia,
    onBackground = StereoColors.Text,
    surface = StereoColors.Panel,
    onSurface = StereoColors.Text,
    surfaceVariant = StereoColors.PanelRaised,
    onSurfaceVariant = StereoColors.MutedText,
    error = StereoColors.Error,
    onError = StereoColors.Black
)

private val StereoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 1.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
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

@Composable
fun MusicPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StereoColorScheme,
        typography = StereoTypography,
        content = content
    )
}
