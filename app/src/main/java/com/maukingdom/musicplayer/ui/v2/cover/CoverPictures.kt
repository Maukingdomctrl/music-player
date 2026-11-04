package com.maukingdom.musicplayer.ui.v2.cover

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.maukingdom.musicplayer.playback.ArtworkPaths
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A loaded cover picture and the song it came from. */
@Immutable
data class CoverPicture(val songId: String, val image: ImageBitmap)

/**
 * Songs known to have (true) or not have (false) a real picture. A few
 * booleans for the process; the decoded bitmaps stay in Coil's memory cache.
 */
private val hasRealArt = ConcurrentHashMap<String, Boolean>()

private fun memoryKey(songId: String, sizePx: Int) = MemoryCache.Key("living:$songId:$sizePx")

/**
 * Whether ArtworkProvider holds a real picture for [songId]. The provider
 * draws a text tile for songs without art (it never fails), and saves real
 * art as cache/artwork/<sha256(songId)>.jpg. Checked only after the provider
 * has served the song, read-only, so the provider itself is unchanged.
 */
private fun providerHasRealArt(context: Context, songId: String): Boolean =
    File(File(context.cacheDir, "artwork"), ArtworkPaths.sha256Hex(songId) + ".jpg").isFile

/**
 * Up to [maxPictures] real pictures from [candidates], each loaded
 * once with Coil at [sizePx] (memory-cached under its own key). Songs that
 * only have the provider's text tile are skipped. Pictures already in
 * memory show on the first frame, so scrolling back never flashes.
 */
@Composable
fun rememberCoverPictures(
    candidates: List<String>,
    sizePx: Int,
    maxPictures: Int = MAX_COVER_PICTURES
): List<CoverPicture> {
    val context = LocalContext.current.applicationContext
    val loader = context.imageLoader

    // Synchronous peek: what is already decoded and known to be real.
    val cached = remember(candidates, sizePx, maxPictures) {
        candidates.asSequence()
            .filter { hasRealArt[it] == true }
            .mapNotNull { id ->
                loader.memoryCache?.get(memoryKey(id, sizePx))?.bitmap?.let { CoverPicture(id, it.asImageBitmap()) }
            }
            .take(maxPictures)
            .toList()
    }

    return produceState(initialValue = cached, candidates, sizePx, maxPictures) {
        val found = mutableListOf<CoverPicture>()
        for (songId in candidates) {
            if (found.size == maxPictures) break
            if (hasRealArt[songId] == false) continue

            val request = ImageRequest.Builder(context)
                .data(songCoverUri(songId))
                .size(sizePx)
                .memoryCacheKey(memoryKey(songId, sizePx))
                .build()

            val bitmap = try {
                val drawable = (loader.execute(request) as? SuccessResult)?.drawable
                (drawable as? BitmapDrawable)?.bitmap
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            } ?: continue

            val real = hasRealArt[songId] ?: withContext(Dispatchers.IO) {
                providerHasRealArt(context, songId)
            }.also { hasRealArt[songId] = it }

            if (real) found += CoverPicture(songId, bitmap.asImageBitmap())
        }

        // Same songs as before: keep the current list, so nothing crossfades.
        if (found.map { it.songId } != value.map { it.songId }) value = found
    }.value
}
