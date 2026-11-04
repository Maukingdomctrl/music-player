package com.maukingdom.musicplayer.playback.visualiser

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * A pass-through tap in the player's audio pipeline for the beat
 * visualiser. Every buffer is copied to the output unchanged, byte for
 * byte (see [passThrough]); the audio heard, the equaliser after it and
 * Android Auto are unaffected. Only while [VisualiserBus.analysing] is the
 * buffer also read (never written) for analysis; otherwise the copy is all
 * it does. Formats it cannot read (anything but 16-bit or float PCM) make
 * it inactive, so the pipeline skips it entirely.
 */
@UnstableApi
class SpectrumAudioProcessor : BaseAudioProcessor() {

    private var analyser: MonoAnalyser? = null

    /** True until analysis has seen its first buffer since a flush or a restart. */
    private var awaitingFirst = true

    /** Whether the stream was flushed since analysis last ran (then nothing is buffered ahead yet). */
    private var flushedSinceAnalysis = true

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        if (start == end) return

        if (VisualiserBus.analysing) {
            analyse(inputBuffer, start, end)
        } else if (analyser != null) {
            // Stopped: the next start re-anchors the timeline.
            analyser = null
            awaitingFirst = true
        }

        passThrough(inputBuffer, replaceOutputBuffer(end - start))
    }

    private fun analyse(buffer: ByteBuffer, start: Int, end: Int) {
        val format = inputAudioFormat
        val tap = analyser ?: MonoAnalyser(format.sampleRate, format.channelCount) { seconds, levels ->
            VisualiserBus.timeline.push(seconds, levels)
        }.also { analyser = it }

        if (awaitingFirst) {
            awaitingFirst = false
            // After a flush nothing is queued ahead of the speaker yet; started
            // mid-stream, the output already holds about one buffer (Media3's
            // default PCM buffer is at least 250 ms) that was never analysed.
            VisualiserBus.timeline.onFlush()
            VisualiserBus.timeline.onAudioStarted(
                nowNanos = System.nanoTime(),
                alreadyQueuedSeconds = if (flushedSinceAnalysis) 0.0 else MID_STREAM_QUEUED_SECONDS
            )
            flushedSinceAnalysis = false
        }

        if (format.encoding == C.ENCODING_PCM_FLOAT) {
            tap.addPcmFloat(buffer, start, end)
        } else {
            tap.addPcm16(buffer, start, end)
        }
    }

    override fun onFlush() {
        analyser = null
        awaitingFirst = true
        flushedSinceAnalysis = true
        VisualiserBus.timeline.onFlush()
    }

    override fun onReset() {
        analyser = null
        awaitingFirst = true
        flushedSinceAnalysis = true
    }

    companion object {
        /** Estimated audio already queued in the output when analysis starts mid-stream. */
        const val MID_STREAM_QUEUED_SECONDS = 0.25

        /**
         * Copies [input] (position..limit) into [output] unchanged and readies
         * [output] for reading; [input] ends fully consumed. The only thing
         * that touches the audio, and unit tested to be bit-identical.
         */
        fun passThrough(input: ByteBuffer, output: ByteBuffer) {
            output.put(input)
            output.flip()
        }
    }
}
