package com.maukingdom.musicplayer.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.maukingdom.musicplayer.playback.ArtworkPaths
import com.maukingdom.musicplayer.ui.v2.Modern

/**
 * A rounded cover loaded with Coil from an ArtworkProvider URI (see
 * [songCoverUri] / [playlistCoverUri]). Decoded at the displayed size, with
 * a 150 ms crossfade; while loading, and on error, a plain surfaceHigh
 * block is shown. Decorative: no content description.
 */
@Composable
fun CoverImage(
    uri: String,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val block = Modern.colors.surfaceHigh
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)

    val request = remember(uri, sizePx) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(sizePx)
            .crossfade(150)
            .build()
    }
    val placeholder = remember(block) { ColorPainter(block) }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(block)
    )
}

fun songCoverUri(songId: String): String = ArtworkPaths.songUri(songId)

fun playlistCoverUri(playlistId: Long): String = ArtworkPaths.playlistUri(playlistId)
