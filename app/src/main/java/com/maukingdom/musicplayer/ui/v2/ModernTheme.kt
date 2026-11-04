package com.maukingdom.musicplayer.ui.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maukingdom.musicplayer.R
import com.maukingdom.musicplayer.ui.LocalSkinTokens

/**
 * Inter (SIL OFL 1.1), one variable font file with weights 400–800.
 * Licence: assets/licenses/OFL-Inter.txt.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val InterFamily = FontFamily(
    inter(400),
    inter(500),
    inter(600),
    inter(700),
    inter(800)
)

/** Modern colours. [accent] and [chipSelected] follow the player skin. */
@Immutable
data class ModernColors(
    val bg: Color = Color(0xFF0B0B0F),
    val surface: Color = Color(0xFF15151B),
    val surfaceHigh: Color = Color(0xFF1C1C22),
    val chip: Color = Color(0xFF232329),
    val chipSelected: Color = Color(0xFFFF8A3D),
    val chipSelectedText: Color = Color(0xFF1A0A00),
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xFFA7A7B0),
    val divider: Color = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    val scrim: Color = Color(0xFF000000).copy(alpha = 0.60f),
    val accent: Color = Color(0xFFFF8A3D)
)

/** Modern text styles, all in Inter. Colours are applied by the components. */
@Immutable
data class ModernType(
    val screenTitle: TextStyle = style(24, 800, letterSpacing = -0.4f),
    val sectionTitle: TextStyle = style(20, 800, letterSpacing = -0.3f),
    val rowTitle: TextStyle = style(15, 500),
    val playlistTitle: TextStyle = style(15, 600),
    val rowSubtitle: TextStyle = style(13, 400),
    val cardTitle: TextStyle = style(14, 600),
    val tileTitle: TextStyle = style(13, 700),
    val caption: TextStyle = style(12, 400),
    val chip: TextStyle = style(13, 500),
    val navLabel: TextStyle = style(11, 500),
    val navLabelSelected: TextStyle = style(11, 600),
    val miniTitle: TextStyle = style(13, 600),
    val miniSubtitle: TextStyle = style(12, 400),
    val sheetEyebrow: TextStyle = style(10, 600, letterSpacing = 1.2f),
    val sheetTitle: TextStyle = style(13, 700),
    val menuItem: TextStyle = style(15, 500)
)

private fun style(sizeSp: Int, weight: Int, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    letterSpacing = letterSpacing.sp
)

/** Spacing, radii and component sizes. */
object ModernDimens {
    // Spacing
    val screenPadding = 16.dp
    val sectionGap = 24.dp
    val itemGap = 12.dp
    val itemGapLarge = 14.dp

    // Radii
    val coverSmall = 4.dp
    val coverMedium = 6.dp
    val cardCover = 8.dp
    val chip = 16.dp
    val sheetTop = 16.dp
    val miniPlayer = 8.dp

    // Sizes
    val listRowHeight = 72.dp
    val listRowCover = 56.dp
    val songRowHeight = 60.dp
    val songRowCover = 44.dp
    val quickTileHeight = 56.dp
    val folderCardCover = 140.dp
    val miniPlayerHeight = 56.dp
    val miniPlayerCover = 40.dp
    val bottomNavHeight = 72.dp
    val icon = 24.dp
    val iconSmall = 22.dp

    // Touch targets: 44 dp visual, 48 dp effective.
    val touchVisual = 44.dp
    val touchEffective = 48.dp

    // Lines are never thicker than this.
    val line = 1.dp
}

val LocalModernColors = staticCompositionLocalOf { ModernColors() }
val LocalModernType = staticCompositionLocalOf { ModernType() }

/** Shorthand: `Modern.colors.bg`, `Modern.type.rowTitle`. */
object Modern {
    val colors: ModernColors
        @Composable get() = LocalModernColors.current

    val type: ModernType
        @Composable get() = LocalModernType.current
}

/**
 * Provides the Modern tokens. Use inside MusicPlayerTheme, so the skin's
 * accent is available. Also clamps the font scale with [clampFontScale]
 * and provides [LocalReduceMotion].
 */
@Composable
fun ModernTheme(content: @Composable () -> Unit) {
    val accent = LocalSkinTokens.current.accent
    val density = LocalDensity.current

    val colors = remember(accent) { ModernColors(accent = accent, chipSelected = accent) }
    val type = remember { ModernType() }
    val clamped = remember(density) {
        Density(density = density.density, fontScale = clampFontScale(density.fontScale))
    }
    val reduceMotion = rememberReduceMotion()

    CompositionLocalProvider(
        LocalModernColors provides colors,
        LocalModernType provides type,
        LocalDensity provides clamped,
        LocalReduceMotion provides reduceMotion,
        content = content
    )
}
