package com.maukingdom.musicplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Small in-memory cache of decoded covers, shared by every list. */
private object ArtworkMemoryCache {

    private const val MAX_BYTES = 8 * 1024 * 1024

    val bitmaps = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    /** At most three decodes at once, so a fast scroll does not flood IO. */
    val decodes = Semaphore(3)
}

/**
 * Loads the image at [uri] (for example an ArtworkPaths URI) on
 * Dispatchers.IO, downsampled so its longer side is at most [sizePx].
 * Returns null while loading and on any error.
 *
 * [revision] is part of the cache key: pass something that changes when the
 * image behind the same URI changes, such as a playlist's name and skin.
 */
@Composable
fun rememberArtwork(uri: String, sizePx: Int, revision: Any? = null): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val key = "$uri|$sizePx|$revision"

    val bitmap by produceState(ArtworkMemoryCache.bitmaps.get(key), key) {
        value = ArtworkMemoryCache.bitmaps.get(key)
        if (value != null) return@produceState

        value = ArtworkMemoryCache.decodes.withPermit {
            withContext(Dispatchers.IO) { decodeArtwork(context, uri, sizePx) }
        }?.also { ArtworkMemoryCache.bitmaps.put(key, it) }
    }

    return bitmap
}

/** Blocking. Null on any error. */
private fun decodeArtwork(context: Context, uri: String, sizePx: Int): ImageBitmap? {
    return try {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val target = sizePx.coerceAtLeast(1)

        // A bounds-only decode always returns null, so only the stream
        // itself being null means the image could not be opened.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(parsed) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target &&
            bounds.outHeight / (sample * 2) >= target
        ) {
            sample *= 2
        }

        val decoded = resolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val fitted = if (longest > target) {
            val scale = target.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        fitted.prepareToDraw()
        fitted.asImageBitmap()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/**
 * A rounded cover. Shows a quiet gradient with a music note until the image
 * has loaded, or if it cannot be loaded. Decorative: no content description.
 */
@Composable
fun ArtworkImage(
    uri: String,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    revision: Any? = null
) {
    val tokens = LocalSkinTokens.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = rememberArtwork(uri, sizePx, revision)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(tokens.panelRaised, tokens.panel))),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = tokens.muted.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}
