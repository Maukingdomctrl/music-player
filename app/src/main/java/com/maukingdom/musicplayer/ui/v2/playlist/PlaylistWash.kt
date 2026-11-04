package com.maukingdom.musicplayer.ui.v2.playlist

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.averageColor
import com.maukingdom.musicplayer.ui.v2.components.playlistCoverUri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode size for colour sampling: enough for an average, cheap to decode. */
private const val SAMPLE_PX = 24

/**
 * Wash colours already worked out, as ARGB ints. A playlist cover is a tile
 * drawn from the playlist name and the skin, so those are part of the key.
 * A few ints per playlist; lives for the process.
 */
private val washCache = ConcurrentHashMap<String, Int>()

/**
 * The top colour of the Playlist wash for [playlistId]'s cover, or null
 * until it is known (callers fall back to the accent mix). Loaded once per
 * key with Coil at 24 px (software bitmap) and averaged on
 * Dispatchers.Default; nothing runs per frame.
 */
@Composable
fun rememberPlaylistWash(playlistId: Long, playlistName: String): Color? {
    val context = LocalContext.current
    val colors = Modern.colors
    val bg = colors.bg.toArgb()
    val key = "$playlistId|$playlistName|${colors.accent.toArgb()}"

    return produceState(initialValue = washCache[key]?.let(::Color), key) {
        // The name is part of the key; wait until the playlist has loaded.
        if (playlistName.isBlank()) return@produceState
        washCache[key]?.let {
            value = Color(it)
            return@produceState
        }

        val request = ImageRequest.Builder(context)
            .data(playlistCoverUri(playlistId))
            .size(SAMPLE_PX)
            .allowHardware(false)
            .build()

        val wash = try {
            val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
            drawable?.let {
                withContext(Dispatchers.Default) {
                    val bitmap = (it as? BitmapDrawable)?.bitmap ?: it.toBitmap(SAMPLE_PX, SAMPLE_PX)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    averageColor(pixels)?.let { average -> playlistWashColor(average, bg) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }

        if (wash != null) {
            washCache[key] = wash
            value = Color(wash)
        }
    }.value
}
