package com.maukingdom.musicplayer.ui.v2.cover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.modernTween
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A playlist cover that shows the playlist's own song pictures, styled by
 * the era in its name (see [eraStyleFor]):
 *
 * - no pictures: generated artwork, stable per playlist ([generateArt]);
 * - one: a slow Ken Burns; two to four: one at a time, crossfading, or a
 *   drifting 2×2 mosaic on 140–199 dp covers ([coverModeFor]).
 *
 * [animate] asks for motion; the cover still holds its static frame when
 * the screen's clock is stopped, it is covered, "Remove animations" is on,
 * or the screen's [MAX_ANIMATED_COVERS] slots are taken. Motion is read only
 * while drawing. New pictures crossfade in. Decorative: no content description.
 */
@Composable
fun LivingCover(
    playlistId: Long,
    playlistName: String,
    size: Dp,
    corner: Dp,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val clock = LocalCoverClock.current
    val environment = LocalCoverEnvironment.current
    val covered = LocalCoversCovered.current
    val candidates = LocalCoverCandidates.current[playlistId].orEmpty()
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // A still cover only ever shows its first picture.
    val pictures = rememberCoverPictures(candidates, sizePx, maxPictures = if (animate) MAX_COVER_PICTURES else 1)
    val style = remember(playlistName) { eraStyleFor(playlistName) }
    val accent = Modern.colors.accent

    // Ask for one of the screen's animation slots only while motion is possible.
    val wantsMotion = animate && clock != null && !covered && environment.appResumed && !environment.animationsOff
    val token = remember(clock) { clock?.newToken() ?: -1L }
    DisposableEffect(clock, wantsMotion) {
        if (wantsMotion) clock?.waiting?.add(token)
        onDispose { clock?.waiting?.remove(token) }
    }
    val hasSlot by remember(clock, token) {
        derivedStateOf { clock != null && token in grantedSlots(clock.waiting) }
    }
    val moving = shouldAnimate(animate, environment.animationsOff, environment.appResumed, covered, hasSlot)
    val fade = modernTween<Float>(ModernMotion.COLOR_MS)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Modern.colors.surfaceHigh)
    ) {
        AnimatedContent(
            targetState = pictures,
            contentKey = { list -> list.map { it.songId } },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "livingCover"
        ) { shown ->
            CoverCanvas(
                pictures = shown,
                seed = playlistId,
                style = style,
                accent = accent,
                sizeDp = size.value,
                clock = if (moving) clock else null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Draws one frame; reads [clock] (when not null) only inside the draw block. */
@Composable
private fun CoverCanvas(
    pictures: List<CoverPicture>,
    seed: Long,
    style: EraStyle,
    accent: Color,
    sizeDp: Float,
    clock: CoverClock?,
    modifier: Modifier = Modifier
) {
    val mode = coverModeFor(pictures.size, sizeDp)
    val art = remember(seed) { generateArt(seed) }
    val palette = remember(style, accent) { paletteFor(style, accent) }
    // Covers drift out of step with each other.
    val phase = (Math.floorMod(seed, 17L)) * 0.9f

    Spacer(
        modifier = modifier.drawWithCache {
            val styleBrushes = StyleBrushes.create(style, size, palette)
            val artBrushes = if (mode == CoverMode.GENERATED) ArtBrushes.create(art, size, palette) else null
            val sepia = if (style == EraStyle.SEPIA) ColorFilter.colorMatrix(CoverTextures.sepia) else null

            onDrawBehind {
                val t = if (clock != null) clock.seconds() + phase else 0f
                val shift = if (style == EraStyle.VHS && clock != null) vhsShiftAt(t, seed) * size.width else 0f

                translate(left = shift) {
                    when (mode) {
                        CoverMode.GENERATED -> drawGenerated(art, artBrushes!!, palette, t)
                        CoverMode.KEN_BURNS -> drawPicture(pictures[0].image, kenBurnsAt(t, seed), 1f, sepia)
                        CoverMode.CROSSFADE -> {
                            val frame = crossfadeAt(t, pictures.size)
                            drawPicture(pictures[frame.current].image, kenBurnsAt(t, seed + frame.current), 1f, sepia)
                            if (frame.fade > 0f) {
                                drawPicture(pictures[frame.next].image, kenBurnsAt(t, seed + frame.next), frame.fade, sepia)
                            }
                        }
                        CoverMode.MOSAIC -> drawMosaic(pictures, t, seed, sepia)
                    }
                }

                drawStyle(style, styleBrushes, palette, t, seed, hasPictures = mode != CoverMode.GENERATED)
            }
        }
    )
}

// ----------------------------------------------------------------- palette

/** Two background stops and [GENERATED_TONES] tones per era. */
private class CoverPalette(val top: Color, val bottom: Color, val tones: List<Color>)

private fun paletteFor(style: EraStyle, accent: Color): CoverPalette = when (style) {
    EraStyle.SEPIA -> CoverPalette(Color(0xFF3B2A1A), Color(0xFF140D07), listOf(Color(0xFFD9B98C), Color(0xFFA67C52), Color(0xFF7A5534), Color(0xFFF1DDB8)))
    EraStyle.VHS -> CoverPalette(Color(0xFF2A0F3D), Color(0xFF0B1630), listOf(Color(0xFFFF3FA4), Color(0xFF3FE0FF), Color(0xFF8F5BFF), Color(0xFFFFD1F0)))
    EraStyle.CD -> CoverPalette(Color(0xFF1B2A3A), Color(0xFF0A0F18), listOf(Color(0xFFCFE7FF), Color(0xFF8FB8DE), Color(0xFFE6D5FF), Color(0xFF9AF0E0)))
    EraStyle.MODERN -> CoverPalette(Color(0xFF101826), Color(0xFF07090F), listOf(Color(0xFF5B8CFF), Color(0xFFFF6B9A), Color(0xFF36D6B4), Color(0xFFF7C948)))
    EraStyle.MARATHI -> CoverPalette(Color(0xFF3A1A05), Color(0xFF0E2A12), listOf(Color(0xFFFF9933), Color(0xFF138808), Color(0xFFFFD27A), Color(0xFFF2F2F2)))
    EraStyle.GANPATI -> CoverPalette(Color(0xFF4A1A00), Color(0xFF1E0A00), listOf(Color(0xFFF4A300), Color(0xFFFF6F00), Color(0xFFFFD54F), Color(0xFFC62828)))
    EraStyle.NEUTRAL -> CoverPalette(
        lerp(Color(0xFF15151B), accent, 0.22f),
        Color(0xFF0B0B0F),
        listOf(accent, Color.White, lerp(accent, Color.White, 0.5f), lerp(accent, Color.Black, 0.4f))
    )
}

// --------------------------------------------------------------- pictures

/** Draws [image] centre-cropped to the cover, scaled and panned by [kb]. No allocation. */
private fun DrawScope.drawPicture(image: ImageBitmap, kb: KenBurns, alpha: Float, filter: ColorFilter?) {
    drawCropped(image, Offset.Zero, size, kb, alpha, filter)
}

private fun DrawScope.drawCropped(
    image: ImageBitmap,
    topLeft: Offset,
    cell: Size,
    kb: KenBurns,
    alpha: Float,
    filter: ColorFilter?
) {
    val side = minOf(image.width, image.height)
    val src = IntOffset((image.width - side) / 2, (image.height - side) / 2)
    val dstW = cell.width * kb.scale
    val dstH = cell.height * kb.scale
    val left = topLeft.x + (cell.width - dstW) / 2f + kb.panX * cell.width
    val top = topLeft.y + (cell.height - dstH) / 2f + kb.panY * cell.height
    drawImage(
        image = image,
        srcOffset = src,
        srcSize = IntSize(side, side),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = filter,
        filterQuality = FilterQuality.Low
    )
}

/** Four pictures in a 2×2 grid, the whole grid drifting slowly; each cell Ken Burns'd lightly. */
private fun DrawScope.drawMosaic(pictures: List<CoverPicture>, t: Float, seed: Long, filter: ColorFilter?) {
    val drift = kenBurnsAt(t * 0.6f, seed)
    val cell = Size(size.width / 2f, size.height / 2f)
    // Slight overscale so the drift never reveals an edge.
    withTransform({
        scale(1.06f, 1.06f, center)
        translate(drift.panX * size.width, drift.panY * size.height)
    }) {
        for (index in 0 until 4) {
            val topLeft = Offset((index % 2) * cell.width, (index / 2) * cell.height)
            clipRect(topLeft.x, topLeft.y, topLeft.x + cell.width, topLeft.y + cell.height) {
                drawCropped(pictures[index].image, topLeft, cell, kenBurnsAt(t, seed + index), 1f, filter)
            }
        }
    }
}

// -------------------------------------------------------------- generated

private class ArtBrushes(val background: Brush, val bands: List<Brush>) {
    companion object {
        fun create(art: GeneratedArt, size: Size, palette: CoverPalette): ArtBrushes {
            val diagonal = max(size.width, size.height) * 1.5f
            return ArtBrushes(
                background = Brush.verticalGradient(listOf(palette.top, palette.bottom)),
                bands = art.bandOffsets.mapIndexed { index, _ ->
                    val tone = palette.tones[(index + 1) % palette.tones.size]
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to tone.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        startX = -diagonal * 0.12f,
                        endX = diagonal * 0.12f
                    )
                }
            )
        }
    }
}

/** "Pressed Light": grooves, label and stage-light bands; only the highlights turn. */
private fun DrawScope.drawGenerated(art: GeneratedArt, brushes: ArtBrushes, palette: CoverPalette, t: Float) {
    drawRect(brushes.background)

    val diagonal = max(size.width, size.height) * 1.5f
    rotate(art.bandAngleDegrees, center) {
        art.bandOffsets.forEachIndexed { index, offset ->
            translate(left = size.width * offset) {
                drawRect(brushes.bands[index], topLeft = Offset(-diagonal, -diagonal), size = Size(diagonal * 2f, diagonal * 3f))
            }
        }
    }

    val c = Offset(art.centerX * size.width, art.centerY * size.height)
    val unit = size.minDimension
    rotate(degrees = t * art.rotationSpeedDegrees, pivot = c) {
        art.rings.forEachIndexed { index, ring ->
            val tone = palette.tones[ring.tone]
            val radius = ring.radius * unit
            drawCircle(tone.copy(alpha = ring.alpha * 0.45f), radius, c, style = Stroke(ring.width * unit))
            // A brighter arc on some grooves: the light catching the turning record.
            if (index % 3 == 0) {
                drawArc(
                    color = tone.copy(alpha = ring.alpha),
                    startAngle = index * 47f,
                    sweepAngle = 38f,
                    useCenter = false,
                    topLeft = Offset(c.x - radius, c.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(ring.width * unit * 1.6f)
                )
            }
        }
        drawCircle(palette.tones[art.labelTone], art.labelRadius * unit, c)
        drawCircle(palette.bottom, art.labelRadius * unit * 0.14f, c)
    }
}

// ------------------------------------------------------------------ styles

/** Brushes that depend only on the cover size; made once per size in drawWithCache. */
private class StyleBrushes(
    val vignette: Brush?,
    val tint: Brush?,
    val edge: Brush?,
    val sweep: Brush?,
    val blob: List<Brush>
) {
    companion object {
        fun create(style: EraStyle, size: Size, palette: CoverPalette): StyleBrushes {
            val radius = max(size.width, size.height)
            return when (style) {
                EraStyle.SEPIA -> StyleBrushes(
                    vignette = Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = radius * 0.75f
                    ),
                    tint = null, edge = null, sweep = null, blob = emptyList()
                )
                EraStyle.VHS -> StyleBrushes(
                    vignette = null,
                    tint = Brush.linearGradient(
                        listOf(Color(0x30FF2FA0), Color(0x3020E0FF)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    edge = Brush.linearGradient(
                        listOf(Color(0xFFFF3FA4), Color(0xFF3FE0FF)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    sweep = null, blob = emptyList()
                )
                EraStyle.CD -> {
                    val band = radius * 0.35f
                    StyleBrushes(
                        vignette = null, tint = null, edge = null,
                        sweep = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.32f),
                            1f to Color.Transparent,
                            start = Offset(-band, -band),
                            end = Offset(band, band)
                        ),
                        blob = emptyList()
                    )
                }
                EraStyle.MODERN -> StyleBrushes(
                    vignette = null, tint = null, edge = null, sweep = null,
                    blob = palette.tones.take(3).map { tone ->
                        Brush.radialGradient(
                            0f to tone.copy(alpha = 0.42f),
                            1f to Color.Transparent,
                            center = Offset.Zero,
                            radius = radius * 0.42f
                        )
                    }
                )
                EraStyle.MARATHI -> StyleBrushes(
                    vignette = null,
                    tint = Brush.linearGradient(
                        listOf(Color(0x47FF9933), Color(0x14FFFFFF), Color(0x40138808)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    edge = null, sweep = null, blob = emptyList()
                )
                EraStyle.GANPATI, EraStyle.NEUTRAL -> StyleBrushes(null, null, null, null, emptyList())
            }
        }
    }
}

private val Marigold = Color(0xFFF4A300)
private val PetalTones = listOf(Color(0xFFF4A300), Color(0xFFFF7A00), Color(0xFFFFC93C))

private fun DrawScope.drawStyle(
    style: EraStyle,
    brushes: StyleBrushes,
    palette: CoverPalette,
    t: Float,
    seed: Long,
    hasPictures: Boolean
) {
    when (style) {
        EraStyle.SEPIA -> {
            // Grain steps like projected film; the tile itself never changes.
            val (gx, gy) = grainOffsetAt(t)
            val tile = CoverTextures.grain.width.toFloat()
            translate(-gx * tile, -gy * tile) {
                drawRect(CoverTextures.grainBrush, size = Size(size.width + tile, size.height + tile))
            }
            brushes.vignette?.let { drawRect(it) }
        }

        EraStyle.VHS -> {
            brushes.tint?.let { drawRect(it, blendMode = BlendMode.Screen) }
            drawRect(CoverTextures.scanlineBrush)
            brushes.edge?.let { edge ->
                val corner = CornerRadius(size.minDimension * 0.04f)
                drawRoundRect(edge, cornerRadius = corner, alpha = 0.22f, style = Stroke(size.minDimension * 0.05f))
                drawRoundRect(edge, cornerRadius = corner, alpha = 0.7f, style = Stroke(size.minDimension * 0.012f))
            }
        }

        EraStyle.CD -> {
            cdSweepAt(t, seed)?.let { position ->
                brushes.sweep?.let { sweep ->
                    val dx = position * size.width
                    val dy = position * size.height
                    translate(dx, dy) {
                        drawRect(sweep, topLeft = Offset(-dx, -dy), size = size)
                    }
                }
            }
        }

        EraStyle.MODERN -> {
            brushes.blob.forEachIndexed { index, blob ->
                val (bx, by) = blobCenterAt(t, seed, index)
                translate(bx * size.width, by * size.height) {
                    drawCircle(blob, radius = size.maxDimension * 0.42f, center = Offset.Zero, blendMode = BlendMode.Screen)
                }
            }
        }

        EraStyle.MARATHI -> brushes.tint?.let { drawRect(it) }

        EraStyle.GANPATI -> {
            drawRect(Marigold.copy(alpha = if (hasPictures) 0.20f else 0.10f))
            val unit = size.minDimension
            for (petal in petalsAt(t, seed)) {
                withTransform({
                    translate(petal.x * size.width, petal.y * size.height)
                    rotate(petal.rotationDegrees, Offset.Zero)
                }) {
                    val w = petal.size * unit
                    drawOval(
                        color = PetalTones[petal.tone].copy(alpha = 0.9f),
                        topLeft = Offset(-w / 2f, -w / 4f),
                        size = Size(w, w / 2f)
                    )
                }
            }
        }

        EraStyle.NEUTRAL -> drawRect(palette.tones[0].copy(alpha = if (hasPictures) 0.10f else 0.06f))
    }
}
