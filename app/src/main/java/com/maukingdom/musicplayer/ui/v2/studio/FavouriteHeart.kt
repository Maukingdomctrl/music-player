package com.maukingdom.musicplayer.ui.v2.studio

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.modernTween

/** TalkBack label for the heart: what a tap will do. */
fun favouriteHeartLabel(isFavourite: Boolean): String =
    if (isFavourite) "Remove from Favourites" else "Add to Favourites"

/**
 * The Studio heart: outline, or filled in the player's one hue. 48 dp, a
 * short haptic tick, and a small pop when it fills (none with animations off).
 */
@Composable
fun FavouriteHeart(
    isFavourite: Boolean,
    tint: State<Color>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val label = favouriteHeartLabel(isFavourite)

    Box(
        modifier = modifier
            .size(ModernDimens.touchEffective)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isFavourite) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                )
                onToggle()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val popIn = modernTween<Float>(ModernMotion.CROSSFADE_MS)
        val out = modernTween<Float>(ModernMotion.PRESS_MS)
        AnimatedContent(
            targetState = isFavourite,
            transitionSpec = {
                (scaleIn(popIn, initialScale = 0.6f) + fadeIn(popIn)).togetherWith(fadeOut(out))
            },
            label = "favouriteHeart"
        ) { filled ->
            Icon(
                imageVector = if (filled) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                // Read here so only the icon follows the tint fade.
                tint = if (filled) tint.value else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
