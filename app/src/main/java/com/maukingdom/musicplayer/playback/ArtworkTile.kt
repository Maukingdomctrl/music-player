package com.maukingdom.musicplayer.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import com.maukingdom.musicplayer.ui.PlayerSkin
import com.maukingdom.musicplayer.ui.tokens

/**
 * Draws a 512 x 512 placeholder cover in a skin's colours: a background
 * gradient, an accent ring and line, the title in up to three lines, an
 * optional caption, and a small "MyCar Music" mark. Uses only system
 * typefaces, so no bundled fonts are needed.
 */
object ArtworkTile {

    const val SIZE = 512

    private const val MARGIN = 48f

    fun render(title: String, caption: String?, skin: PlayerSkin): Bitmap {
        val tokens = skin.tokens
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = SIZE.toFloat()
        val textWidth = (size - MARGIN * 2).toInt()

        // Background gradient.
        val background = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                size,
                tokens.backgroundTop.toArgb(),
                tokens.background.toArgb(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size, size, background)

        // Accent ring, top right.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            color = tokens.accent.toArgb()
        }
        canvas.drawCircle(size - 110f, 110f, 58f, ring)

        // Accent line above the title.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tokens.accent.toArgb()
        }
        canvas.drawRoundRect(RectF(MARGIN, 196f, MARGIN + 120f, 204f), 4f, 4f, line)

        // Title, up to three lines.
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tokens.text.toArgb()
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }

        val cleanTitle = title.trim().ifEmpty { "Unknown title" }
        val titleLayout = StaticLayout.Builder
            .obtain(cleanTitle, 0, cleanTitle.length, titlePaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        canvas.save()
        canvas.translate(MARGIN, 224f)
        titleLayout.draw(canvas)
        canvas.restore()

        // Optional caption (artist, or "PLAYLIST").
        val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tokens.muted.toArgb()
            textSize = 26f
            typeface = Typeface.DEFAULT
        }

        caption?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val fitted = TextUtils.ellipsize(
                text,
                captionPaint,
                textWidth.toFloat(),
                TextUtils.TruncateAt.END
            )
            val captionTop = 224f + titleLayout.height + 18f
            canvas.drawText(fitted, 0, fitted.length, MARGIN, captionTop - captionPaint.ascent(), captionPaint)
        }

        // Small brand mark.
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tokens.muted.toArgb()
            textSize = 22f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.1f
        }
        canvas.drawText("MyCar Music", MARGIN, size - 40f, brandPaint)

        return bitmap
    }
}
