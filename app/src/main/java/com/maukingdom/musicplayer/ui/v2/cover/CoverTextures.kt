package com.maukingdom.musicplayer.ui.v2.cover

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Small repeating textures shared by every living cover, made once for the
 * process (a few KB) and never regenerated: covers only move them.
 */
internal object CoverTextures {

    private const val GRAIN_SIZE = 96

    /** Film grain: light and dark specks on transparent, seeded so it never changes. */
    val grain: ImageBitmap by lazy {
        val random = java.util.Random(1977L)
        val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
            val roll = random.nextInt(100)
            when {
                roll < 7 -> argb(alpha = 26 + random.nextInt(40), grey = 255)
                roll < 14 -> argb(alpha = 30 + random.nextInt(50), grey = 0)
                else -> 0
            }
        }
        Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Scanlines: one faint dark row every three pixels. */
    val scanlines: ImageBitmap by lazy {
        val pixels = intArrayOf(0, 0, argb(alpha = 46, grey = 0))
        Bitmap.createBitmap(pixels, 1, 3, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    val grainBrush: ShaderBrush by lazy { ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)) }

    val scanlineBrush: ShaderBrush by lazy { ShaderBrush(ImageShader(scanlines, TileMode.Repeated, TileMode.Repeated)) }

    /** Classic sepia matrix (RGB rows, then alpha). */
    val sepia: ColorMatrix by lazy {
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun argb(alpha: Int, grey: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (grey shl 16) or (grey shl 8) or grey
}
