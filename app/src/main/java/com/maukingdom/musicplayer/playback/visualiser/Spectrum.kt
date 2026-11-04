package com.maukingdom.musicplayer.playback.visualiser

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The beat visualiser's signal path, pure Kotlin (no Android or Media3), so
 * it is unit tested: FFT with a Hann window, 40 log-spaced bands, attack/
 * release smoothing with a bass beat boost, and the timeline that holds
 * each analysed frame back until that audio is actually heard.
 */

const val VISUALISER_BANDS = 40
const val FFT_SIZE = 1024
const val BAND_MIN_HZ = 40.0
const val BAND_MAX_HZ = 16_000.0

/** One analysis every ~33 ms of audio. */
const val ANALYSIS_INTERVAL_SECONDS = 1.0 / 30.0

const val ATTACK_SECONDS = 0.030
const val RELEASE_SECONDS = 0.250

/** In-place radix-2 FFT of [FFT_SIZE] (or any power of two) real samples, with precomputed tables. */
class Fft(val size: Int = FFT_SIZE) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two." }
    }

    private val bits = Integer.numberOfTrailingZeros(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }

    /** Hann window; its sum is size / 2. */
    val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / (size - 1))).toFloat() }

    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /**
     * Windows [samples] (length [size], -1..1) and writes the amplitude of
     * bins 0..size/2 into [magnitudes] (length size/2 + 1), scaled so a
     * full-scale sine at a bin centre gives about 1.0. No allocation.
     */
    fun magnitudes(samples: FloatArray, magnitudes: FloatArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            re[j] = samples[i] * window[i]
            im[j] = 0f
        }

        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val c = cosTable[k * step]
                    val s = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }

        val scale = 4f / size // 2 for one-sided, 2 for the Hann window's mean of 0.5
        for (k in 0..size / 2) {
            magnitudes[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        }
    }
}

/**
 * [VISUALISER_BANDS] log-spaced bands from [BAND_MIN_HZ] to [BAND_MAX_HZ]
 * over FFT bins at [sampleRate]. Bands narrower than a bin (the low end at
 * 1024 points) take their nearest bin, so every band has at least one.
 */
class BandMap(
    val sampleRate: Int,
    val fftSize: Int = FFT_SIZE,
    val bands: Int = VISUALISER_BANDS,
    minHz: Double = BAND_MIN_HZ,
    maxHz: Double = BAND_MAX_HZ
) {
    /** First and last bin (inclusive) of each band. */
    val firstBin = IntArray(bands)
    val lastBin = IntArray(bands)

    /** Centre frequency of each band. */
    val centreHz = DoubleArray(bands)

    init {
        val binHz = sampleRate.toDouble() / fftSize
        val topBin = fftSize / 2
        val top = min(maxHz, sampleRate / 2.0)
        val ratio = top / minHz
        for (band in 0 until bands) {
            val low = minHz * ratio.pow(band.toDouble() / bands)
            val high = minHz * ratio.pow((band + 1).toDouble() / bands)
            centreHz[band] = sqrt(low * high)
            var first = (low / binHz).toInt().coerceIn(1, topBin)
            var last = ((high / binHz).toInt() - 1).coerceIn(1, topBin)
            if (last < first) {
                // Narrower than a bin: the bin nearest the centre.
                first = Math.round(centreHz[band] / binHz).toInt().coerceIn(1, topBin)
                last = first
            }
            firstBin[band] = first
            lastBin[band] = last
        }
    }

    /**
     * Band levels 0..1 from bin [magnitudes] into [out]: the peak bin of each
     * band in dB, tilted +4.5 dB per octave above 1 kHz (music falls off
     * there, so high bands would look dead), mapped from [floorDb]..[ceilingDb].
     */
    fun levels(magnitudes: FloatArray, out: FloatArray, floorDb: Float = -62f, ceilingDb: Float = -14f) {
        for (band in 0 until bands) {
            var peak = 0f
            for (bin in firstBin[band]..lastBin[band]) peak = max(peak, magnitudes[bin])
            val db = 20f * log10(max(peak, 1e-7f)) + (4.5 * ln(centreHz[band] / 1000.0) / ln(2.0)).toFloat()
            out[band] = ((db - floorDb) / (ceilingDb - floorDb)).coerceIn(0f, 1f)
        }
    }
}

/** Fraction to move toward a target in [dtSeconds] for a time constant [tauSeconds]. */
fun smoothingStep(dtSeconds: Double, tauSeconds: Double): Float =
    (1.0 - exp(-dtSeconds / tauSeconds)).toFloat().coerceIn(0f, 1f)

/**
 * Fast attack, slow release, plus a beat boost on the lowest bands: when
 * the bass jumps well above its recent average, a pulse lifts the first
 * bands and decays in ~120 ms. Not thread-safe; one per publisher.
 */
class BandSmoother(private val bands: Int = VISUALISER_BANDS) {
    val levels = FloatArray(bands)

    private var bassAverage = 0f
    private var beat = 0f
    private var sinceBeat = 1.0

    /** Moves [levels] toward [targets] (null = silence) over [dtSeconds]. */
    fun update(targets: FloatArray?, dtSeconds: Double) {
        val attack = smoothingStep(dtSeconds, ATTACK_SECONDS)
        val release = smoothingStep(dtSeconds, RELEASE_SECONDS)

        // Beat: lowest five bands against their ~0.45 s average.
        var bass = 0f
        if (targets != null) {
            val lowBands = min(5, bands)
            for (band in 0 until lowBands) bass += targets[band]
            bass /= lowBands
        }
        bassAverage += (bass - bassAverage) * smoothingStep(dtSeconds, 0.45)
        sinceBeat += dtSeconds
        if (targets != null && bass > bassAverage * 1.25f + 0.05f && sinceBeat > 0.18) {
            beat = 1f
            sinceBeat = 0.0
        }
        beat *= exp(-dtSeconds / 0.12).toFloat()

        for (band in 0 until bands) {
            val boost = if (band < 8) beat * 0.35f * (1f - band / 8f) else 0f
            val target = ((targets?.get(band) ?: 0f) + boost).coerceIn(0f, 1f)
            val current = levels[band]
            levels[band] = current + (target - current) * (if (target > current) attack else release)
        }
    }
}

/**
 * Keeps analysed frames until their audio is heard. Audio is analysed when
 * it is handed to the output, which runs ahead of the speaker by the output
 * buffer; the frame for "now" is the newest whose audio time (seconds since
 * the stream restarted) is at most the time spent playing since then, less
 * [outputLatencySeconds]. Pauses stop that clock; a flush (seek, new
 * stream) restarts it. Thread-safe.
 */
class VisualiserTimeline(
    private val outputLatencySeconds: Double = 0.06,
    private val capacity: Int = 96
) {
    private class Frame(val audioSeconds: Double, val levels: FloatArray)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private var playing = false
    private var started = false
    private var playedNanos = 0L
    private var playingSinceNanos = 0L

    fun onPlayingChanged(isPlaying: Boolean, nowNanos: Long) = synchronized(lock) {
        if (isPlaying == playing) return@synchronized
        if (playing && started) playedNanos += nowNanos - playingSinceNanos
        playing = isPlaying
        if (playing) playingSinceNanos = nowNanos
    }

    /** Seek or new stream: audio time restarts at 0; the clock starts with the first buffer. */
    fun onFlush() = synchronized(lock) {
        frames.clear()
        started = false
        playedNanos = 0L
    }

    /**
     * The first analysed buffer reached the output at [nowNanos], behind
     * [alreadyQueuedSeconds] of audio that is still to be heard before it.
     */
    fun onAudioStarted(nowNanos: Long, alreadyQueuedSeconds: Double = 0.0) = synchronized(lock) {
        if (started) return@synchronized
        started = true
        playedNanos = -(alreadyQueuedSeconds * 1e9).toLong()
        playingSinceNanos = nowNanos
    }

    val isPlaying: Boolean
        get() = synchronized(lock) { playing }

    fun push(audioSeconds: Double, levels: FloatArray) = synchronized(lock) {
        frames.addLast(Frame(audioSeconds, levels))
        while (frames.size > capacity) frames.removeFirst()
    }

    /** Seconds of the stream heard so far at [nowNanos]. */
    fun heardSeconds(nowNanos: Long): Double = synchronized(lock) {
        if (!started) return@synchronized 0.0
        val played = playedNanos + if (playing) nowNanos - playingSinceNanos else 0L
        played / 1e9 - outputLatencySeconds
    }

    /** The newest frame heard by [nowNanos], dropping older ones; null if none is due yet. */
    fun take(nowNanos: Long): FloatArray? {
        val heard = heardSeconds(nowNanos)
        return synchronized(lock) {
            var due: Frame? = null
            while (frames.isNotEmpty() && frames.first().audioSeconds <= heard) due = frames.removeFirst()
            due?.levels
        }
    }
}

/**
 * Collects mono samples from PCM buffers and, every [ANALYSIS_INTERVAL_SECONDS]
 * of audio, analyses the latest [FFT_SIZE] of them. Reads its input through
 * absolute gets, so it never moves or changes the caller's buffer.
 */
class MonoAnalyser(val sampleRate: Int, val channels: Int, private val onFrame: (audioSeconds: Double, levels: FloatArray) -> Unit) {
    private val fft = Fft()
    private val bands = BandMap(sampleRate)
    private val ring = FloatArray(FFT_SIZE)
    private val ordered = FloatArray(FFT_SIZE)
    private val magnitudes = FloatArray(FFT_SIZE / 2 + 1)
    private var ringPos = 0
    private var framesSeen = 0L
    private var untilNext = (sampleRate * ANALYSIS_INTERVAL_SECONDS).toInt()
    private val interval = untilNext

    /** Adds one mono sample (average of the frame's channels, -1..1). */
    fun addSample(mono: Float) {
        ring[ringPos] = mono
        ringPos = (ringPos + 1) % FFT_SIZE
        framesSeen++
        if (--untilNext <= 0) {
            untilNext = interval
            analyse()
        }
    }

    /** 16-bit little-endian interleaved PCM from [start] until [end] (bytes), read without moving the buffer. */
    fun addPcm16(buffer: java.nio.ByteBuffer, start: Int, end: Int) {
        val frameBytes = 2 * channels
        var i = start
        while (i + frameBytes <= end) {
            var sum = 0f
            for (c in 0 until channels) sum += buffer.getShort(i + 2 * c).toFloat()
            addSample(sum / (channels * 32768f))
            i += frameBytes
        }
    }

    /** 32-bit float interleaved PCM from [start] until [end] (bytes). */
    fun addPcmFloat(buffer: java.nio.ByteBuffer, start: Int, end: Int) {
        val frameBytes = 4 * channels
        var i = start
        while (i + frameBytes <= end) {
            var sum = 0f
            for (c in 0 until channels) sum += buffer.getFloat(i + 4 * c)
            addSample(sum / channels)
            i += frameBytes
        }
    }

    private fun analyse() {
        for (i in 0 until FFT_SIZE) ordered[i] = ring[(ringPos + i) % FFT_SIZE]
        fft.magnitudes(ordered, magnitudes)
        val levels = FloatArray(VISUALISER_BANDS)
        bands.levels(magnitudes, levels)
        // Time of the window's centre.
        onFrame((framesSeen - FFT_SIZE / 2).coerceAtLeast(0L).toDouble() / sampleRate, levels)
    }
}
