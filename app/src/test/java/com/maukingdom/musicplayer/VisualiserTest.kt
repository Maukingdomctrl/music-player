package com.maukingdom.musicplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.maukingdom.musicplayer.playback.visualiser.ATTACK_SECONDS
import com.maukingdom.musicplayer.playback.visualiser.BandMap
import com.maukingdom.musicplayer.playback.visualiser.BandSmoother
import com.maukingdom.musicplayer.playback.visualiser.FFT_SIZE
import com.maukingdom.musicplayer.playback.visualiser.Fft
import com.maukingdom.musicplayer.playback.visualiser.MonoAnalyser
import com.maukingdom.musicplayer.playback.visualiser.RELEASE_SECONDS
import com.maukingdom.musicplayer.playback.visualiser.SpectrumAudioProcessor
import com.maukingdom.musicplayer.playback.visualiser.VISUALISER_BANDS
import com.maukingdom.musicplayer.playback.visualiser.VisualiserBus
import com.maukingdom.musicplayer.playback.visualiser.VisualiserTimeline
import com.maukingdom.musicplayer.playback.visualiser.smoothingStep
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualiserTest {

    private fun sine(hz: Double, rate: Int, n: Int, amplitude: Float = 1f) =
        FloatArray(n) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    @Test
    fun fftPeakIsAtTheSineFrequency() {
        val rate = 44_100
        val fft = Fft()
        val mags = FloatArray(FFT_SIZE / 2 + 1)
        val binHz = rate.toDouble() / FFT_SIZE
        val bin = 93 // ~4 kHz, at a bin centre
        fft.magnitudes(sine(bin * binHz, rate, FFT_SIZE), mags)
        val peak = mags.indices.maxByOrNull { mags[it] }!!
        assertEquals(bin, peak)
        // Full-scale sine at a bin centre reads about 1.0.
        assertEquals(1.0f, mags[bin], 0.05f)
    }

    @Test
    fun bandsAreLogSpacedAndCoverEveryBand() {
        val map = BandMap(44_100)
        assertEquals(VISUALISER_BANDS, map.centreHz.size)
        for (b in 0 until VISUALISER_BANDS) {
            assertTrue("band $b has a bin", map.lastBin[b] >= map.firstBin[b])
            assertTrue(map.firstBin[b] >= 1 && map.lastBin[b] <= FFT_SIZE / 2)
            if (b > 0) {
                // Constant ratio between neighbouring centres (log spacing).
                val ratio = map.centreHz[b] / map.centreHz[b - 1]
                assertEquals(Math.pow(400.0, 1.0 / 40), ratio, 1e-9)
                assertTrue(map.firstBin[b] >= map.firstBin[b - 1])
            }
        }
        assertTrue(map.centreHz.first() in 40.0..50.0)
        assertTrue(map.centreHz.last() in 14_000.0..16_000.0)
        // 48 kHz audio maps too.
        val map48 = BandMap(48_000)
        assertTrue(map48.lastBin.last() <= FFT_SIZE / 2)
    }

    @Test
    fun aToneLightsItsOwnBand() {
        val rate = 44_100
        val fft = Fft()
        val map = BandMap(rate)
        val mags = FloatArray(FFT_SIZE / 2 + 1)
        val out = FloatArray(VISUALISER_BANDS)
        fft.magnitudes(sine(1_000.0, rate, FFT_SIZE, 0.5f), mags)
        map.levels(mags, out)
        val loudest = out.indices.maxByOrNull { out[it] }!!
        assertTrue("1 kHz band: centre ${map.centreHz[loudest]}", map.centreHz[loudest] in 850.0..1_200.0)
        // Silence is all zeros.
        java.util.Arrays.fill(mags, 0f)
        map.levels(mags, out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun smoothingIsFastUpAndSlowDown() {
        // One time constant reaches 63 %.
        assertEquals(0.632f, smoothingStep(ATTACK_SECONDS, ATTACK_SECONDS), 0.001f)
        val smoother = BandSmoother()
        val loud = FloatArray(VISUALISER_BANDS) { 0.8f }
        // 33 ms up: most of the way (attack 30 ms); band 20 has no beat boost.
        smoother.update(loud, 0.033)
        assertTrue(smoother.levels[20] > 0.5f)
        repeat(10) { smoother.update(loud, 0.033) }
        assertEquals(0.8f, smoother.levels[20], 0.01f)
        // 33 ms down: only a little (release 250 ms).
        smoother.update(null, 0.033)
        assertTrue(smoother.levels[20] in 0.65f..0.75f)
        // After ~1 s of silence the bars are near the floor.
        repeat(30) { smoother.update(null, 0.033) }
        assertTrue(smoother.levels.all { it < 0.03f })
        assertTrue(RELEASE_SECONDS > ATTACK_SECONDS * 5)
    }

    @Test
    fun aBassHitBoostsTheLowestBands() {
        val smoother = BandSmoother()
        val quiet = FloatArray(VISUALISER_BANDS) { 0.1f }
        repeat(30) { smoother.update(quiet, 0.033) }
        val before = smoother.levels[0]
        val hit = quiet.copyOf().also { for (i in 0 until 5) it[i] = 0.7f }
        smoother.update(hit, 0.033)
        // The lowest band overshoots the raw jump; band 20 is untouched.
        assertTrue(smoother.levels[0] > before + 0.3f)
        assertEquals(0.1f, smoother.levels[20], 0.01f)
    }

    @Test
    fun timelineWaitsUntilAudioIsHeardAndStopsWhenPaused() {
        val timeline = VisualiserTimeline(outputLatencySeconds = 0.0)
        val second = 1_000_000_000L
        timeline.onPlayingChanged(true, 0L)
        timeline.onAudioStarted(nowNanos = 0L, alreadyQueuedSeconds = 0.25)
        val a = FloatArray(1) { 0.1f }
        val b = FloatArray(1) { 0.2f }
        timeline.push(0.1, a)
        timeline.push(0.5, b)
        // 0.25 s still queued ahead: nothing heard at 0.3 s.
        assertNull(timeline.take(second * 3 / 10))
        assertEquals(a, timeline.take(second * 4 / 10))
        // Paused at 0.5 s: the clock stops, so b (0.5 s of audio) is not yet due.
        timeline.onPlayingChanged(false, second / 2)
        assertNull(timeline.take(second * 5))
        timeline.onPlayingChanged(true, second * 5)
        assertEquals(b, timeline.take(second * 5 + second / 2))
        // A flush clears everything.
        timeline.push(9.0, a)
        timeline.onFlush()
        assertNull(timeline.take(second * 100))
    }

    @Test
    fun passThroughIsBitIdentical() {
        val bytes = ByteArray(4096) { (it * 37 + 11).toByte() }
        val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
        val output = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        SpectrumAudioProcessor.passThrough(input, output)
        assertEquals(0, input.remaining())
        val copied = ByteArray(output.remaining()).also { output.get(it) }
        assertArrayEquals(bytes, copied)
    }

    @Test
    fun analysisNeverTouchesTheBuffer() {
        val pcm = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4096) pcm.putShort((sin(i * 0.05) * 20_000).toInt().toShort())
        pcm.flip()
        val before = ByteArray(pcm.remaining()).also { pcm.duplicate().get(it) }
        val position = pcm.position()
        var frames = 0
        val analyser = MonoAnalyser(44_100, 2) { _, _ -> frames++ }
        analyser.addPcm16(pcm, pcm.position(), pcm.limit())
        assertEquals(position, pcm.position())
        val after = ByteArray(pcm.remaining()).also { pcm.duplicate().get(it) }
        assertArrayEquals(before, after)
        assertTrue(frames >= 1)
    }

    @Test
    fun processorOutputEqualsInputWhileAnalysing() {
        val processor = SpectrumAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertTrue(processor.isActive)

        VisualiserBus.request()
        try {
            val bytes = ByteArray(16_384).also { java.util.Random(7).nextBytes(it) }
            val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            input.flip()
            processor.queueInput(input)
            val out = processor.output
            val copied = ByteArray(out.remaining()).also { out.get(it) }
            assertArrayEquals(bytes, copied)
        } finally {
            VisualiserBus.release()
        }

        // Formats it cannot read: inactive, so Media3 skips it entirely.
        val other = SpectrumAudioProcessor()
        other.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        assertTrue(!other.isActive)
    }

    @Test
    fun analysisCostEstimate() {
        // 10 s of stereo 44.1 kHz 16-bit audio through the analysis path.
        val rate = 44_100
        val pcm = ByteBuffer.allocate(rate * 10 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * 10) {
            val v = (sin(i * 0.031) * 12_000).toInt().toShort()
            pcm.putShort(v).putShort(v)
        }
        pcm.flip()
        var frames = 0
        val analyser = MonoAnalyser(rate, 2) { _, _ -> frames++ }
        analyser.addPcm16(pcm, 0, pcm.limit()) // warm-up
        val start = System.nanoTime()
        analyser.addPcm16(pcm, 0, pcm.limit())
        val ms = (System.nanoTime() - start) / 1e6
        println("Visualiser analysis: 10 s of audio in %.1f ms on this JVM (%d frames)".format(ms, frames))
        assertTrue(abs(frames - 600) <= 2) // 30 per second, twice
        assertTrue("10 s of audio should take well under a second", ms < 1_000)
    }
}
