package io.github.sumirenokai.vesqen.playback

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How DSD is turned into PCM (#249, owner 2026-11-26). The defaults convert the file as it is. */
internal data class DsdConversion(
    val rateMode: DsdOutputRateMode = DsdOutputRateMode.UNIFORM,
    /** Added gain, 0 to +6 dB. DSD's 0 dB reference converts to −6 dBFS, so +3 dB never clips. */
    val gainDb: Float = 0f,
)

internal enum class DsdOutputRateMode {
    /** 88.2 kHz from any DSD rate, 96 kHz from the 48 kHz family. */
    UNIFORM,

    /** DSD64 to 88.2 kHz, DSD128 and above to 176.4 kHz (192 kHz for the 48 kHz family). */
    MULTIPLE,
}

internal const val DSD_MAX_GAIN_DB = 6f

/** The PCM rate a [dsdRate] stream converts to. */
internal fun dsdOutputRate(dsdRate: Int, mode: DsdOutputRateMode): Int {
    val base = if (dsdRate % 3_072_000 == 0) 96_000 else 88_200
    return when (mode) {
        DsdOutputRateMode.UNIFORM -> base
        DsdOutputRateMode.MULTIPLE -> minOf(dsdRate / 32, base * 2)
    }
}

/**
 * Converts 1-bit DSD into 32-bit float PCM by FIR decimation (#249). The first stage decimates by 8
 * and looks whole bytes up in tables; each further stage decimates by 2. Every filter is a
 * linear-phase lowpass with a Kaiser window, designed for 130 dB of stopband attenuation:
 *
 * - the earlier stages keep the output's whole band and stop everything that their decimation would
 *   fold back onto it;
 * - the last stage passes up to 0.227 of the output rate (20 kHz at 88.2 kHz) flat and stops at the
 *   output's Nyquist frequency, which removes the modulator's ultrasonic noise before it aliases.
 *
 * A bit of 1 is +1 and a bit of 0 is −1, so a full-scale DSD stream converts to ±1.0 before
 * [gainDb]. The filters' delay is taken out: each channel drops [latencyFrames] frames at its start
 * and [drain] adds them at its end, so output frame n is the input's frame n and two files cut
 * from one recording join without a gap. Feed every channel the same number of bytes per round;
 * each then writes the same number of frames. One instance per stream, not thread-safe.
 */
internal class DsdToPcmConverter(
    dsdRate: Int,
    private val channels: Int,
    val outputRate: Int,
    gainDb: Float,
) {
    private val design = DsdFilterDesign.forRates(dsdRate, outputRate)
    private val gain = 10f.pow(gainDb.coerceIn(0f, DSD_MAX_GAIN_DB) / 20f)
    private val states = Array(channels) { ChannelState(design) }

    init {
        require(channels > 0)
    }

    /** DSD samples per output frame. */
    val decimation: Int get() = design.decimation

    /** Output frames the filters lag their input by. */
    val latencyFrames: Int get() = design.latencyFrames

    /** Drops the filters' history, as after a seek; the next frame is the next input's own. */
    fun reset() = states.forEach(ChannelState::reset)

    /**
     * Feeds [count] bytes of [channel], each with its earliest sample in the highest bit unless
     * [lsbFirst], and writes that channel's output into [out] at frame [outFrame] onwards, interleaved
     * across [channels]. Returns how many frames were written.
     */
    fun process(channel: Int, bytes: ByteArray, offset: Int, count: Int, lsbFirst: Boolean, out: FloatArray, outFrame: Int): Int {
        val state = states[channel]
        var frame = outFrame
        for (index in offset until offset + count) {
            val raw = bytes[index].toInt() and 0xFF
            if (!state.push(if (lsbFirst) BIT_REVERSED[raw] else raw)) continue
            state.produced++
            if (state.skip > 0) {
                state.skip--
                continue
            }
            out[frame * channels + channel] = state.output * gain
            state.written++
            frame++
        }
        return frame - outFrame
    }

    /**
     * Ends [channel]'s input: idle input pushes out the frames still inside the filters, as many
     * as were dropped at the start. Returns how many frames were written into [out] at [outFrame].
     */
    fun drain(channel: Int, out: FloatArray, outFrame: Int): Int {
        val state = states[channel]
        var frame = outFrame
        while (state.written < state.produced) {
            if (!state.push(SILENCE)) continue
            if (state.skip > 0) {
                state.skip--
                continue
            }
            out[frame * channels + channel] = state.output * gain
            state.written++
            frame++
        }
        return frame - outFrame
    }

    private class ChannelState(private val design: DsdFilterDesign) {
        private val bytes = IntArray(BYTE_TAPS) { SILENCE }
        private var bytePos = 0
        private val halves = Array(design.halfStages.size) { HalfStage(design.halfStages[it]) }

        /** The frame the last [push] completed. */
        var output = 0f
            private set

        /** Frames still to drop for the filters' delay. */
        var skip = design.latencyFrames

        /** Frames the real input has completed, and how many of them were written out. */
        var produced = 0L
        var written = 0L

        fun reset() {
            bytes.fill(SILENCE)
            bytePos = 0
            halves.forEach(HalfStage::reset)
            skip = design.latencyFrames
            produced = 0
            written = 0
        }

        /** Feeds one byte, earliest sample in the highest bit; true when it completes a frame. */
        fun push(byte: Int): Boolean {
            val table = design.byteTable
            bytePos = (bytePos + 1) and (BYTE_TAPS - 1)
            bytes[bytePos] = byte
            var sum = 0f
            var k = 0
            var pos = bytePos
            while (k < BYTE_TAPS) {
                sum += table[(k shl 8) or bytes[pos]]
                pos = (pos - 1) and (BYTE_TAPS - 1)
                k++
            }
            var stage = 0
            while (stage < halves.size) {
                val half = halves[stage]
                if (!half.push(sum)) return false
                sum = half.output
                stage++
            }
            output = sum
            return true
        }
    }

    /** A 2:1 decimating FIR over a doubled ring buffer, so each output reads one contiguous window. */
    private class HalfStage(private val taps: FloatArray) {
        private val length = taps.size
        private val buffer = FloatArray(length * 2)
        private var pos = 0
        private var odd = false

        /** The output the last [push] completed. */
        var output = 0f
            private set

        fun reset() {
            buffer.fill(0f)
            pos = 0
            odd = false
        }

        /** True when [sample] completes an output. */
        fun push(sample: Float): Boolean {
            buffer[pos] = sample
            buffer[pos + length] = sample
            pos++
            if (pos == length) pos = 0
            odd = !odd
            if (odd) return false
            // The window ends with the newest sample, at pos - 1 + length.
            var sum = 0f
            var index = 0
            while (index < length) {
                sum += taps[index] * buffer[pos + index]
                index++
            }
            output = sum
            return true
        }
    }

    internal companion object {
        /** The first stage's FIR spans 16 bytes, 128 DSD samples. */
        const val BYTE_TAPS = 16

        /** DSD idle pattern: as many ones as zeros, so it converts to silence. */
        private const val SILENCE = 0x69

        private val BIT_REVERSED = IntArray(256) { value ->
            (0 until 8).fold(0) { reversed, bit -> reversed or (((value shr bit) and 1) shl (7 - bit)) }
        }
    }
}

/** The filters for one rate pair, shared by every stream that uses it. */
internal class DsdFilterDesign private constructor(dsdRate: Int, outputRate: Int) {
    val decimation = dsdRate / outputRate

    /** The first stage's 128 taps, newest sample first; [byteTable] holds them summed by byte. */
    val firstStage: DoubleArray

    /** First stage, 8:1: the summed taps for each of 16 byte positions and 256 byte values. */
    val byteTable: FloatArray

    /** The 2:1 stages after it, the last one last. */
    val halfStages: List<FloatArray>

    /**
     * Output frames between an input frame and the output frame that is centred on it. Each
     * linear-phase stage delays by half its length, and a frame comes out with its newest sample,
     * [decimation] − 1 samples after the frame's own. Rounded; the rest is under a frame.
     */
    val latencyFrames: Int

    init {
        require(dsdRate % outputRate == 0 && decimation >= 16 && Integer.bitCount(decimation) == 1) {
            "DSD $dsdRate Hz cannot be decimated to $outputRate Hz"
        }
        val firstTaps = DsdToPcmConverter.BYTE_TAPS * 8
        // Half the 8:1 stage's output rate as the cutoff; the transition then ends far below the
        // first frequency that decimation folds onto the output band (checked in tests).
        val first = kaiserLowpass(firstTaps, cutoff = 1.0 / 16, beta = KAISER_BETA)
        firstStage = first
        byteTable = FloatArray(DsdToPcmConverter.BYTE_TAPS * 256) { slot ->
            val position = slot shr 8
            val value = slot and 0xFF
            // Bit 7 is the earliest sample in the byte, so the furthest back in the window.
            (0 until 8).fold(0.0) { sum, bit ->
                val tap = first[position * 8 + bit]
                sum + if ((value shr bit) and 1 == 1) tap else -tap
            }.toFloat()
        }
        val stages = mutableListOf<FloatArray>()
        var rate = dsdRate / 8
        while (rate > outputRate) {
            val outRate = rate / 2
            val last = outRate == outputRate
            val pass = if (last) outputRate * LAST_STAGE_PASS else outputRate / 2.0
            val stop = if (last) outputRate / 2.0 else rate / 2.0 - outputRate / 2.0
            stages += kaiserLowpass(
                taps = tapsFor(transition = (stop - pass) / rate),
                cutoff = (pass + stop) / 2 / rate,
                beta = KAISER_BETA,
            ).map(Double::toFloat).toFloatArray()
            rate = outRate
        }
        halfStages = stages
        var delay = (firstTaps - 1) / 2.0
        stages.forEachIndexed { index, taps -> delay += (taps.size - 1) / 2.0 * 8 * (1 shl index) }
        latencyFrames = Math.round((delay - (decimation - 1)) / decimation).toInt()
    }

    companion object {
        /** 130 dB of stopband attenuation, by Kaiser's formula. */
        private const val ATTENUATION_DB = 130.0
        private const val KAISER_BETA = 0.1102 * (ATTENUATION_DB - 8.7)

        /** The last stage passes 20 kHz at 88.2 kHz, and the same share of a higher rate. */
        internal const val LAST_STAGE_PASS = 0.227

        private val cache = HashMap<Pair<Int, Int>, DsdFilterDesign>()

        @Synchronized
        fun forRates(dsdRate: Int, outputRate: Int): DsdFilterDesign =
            cache.getOrPut(dsdRate to outputRate) { DsdFilterDesign(dsdRate, outputRate) }

        /** Kaiser's length estimate, rounded up to an odd count so the delay is a whole sample. */
        private fun tapsFor(transition: Double): Int {
            val taps = ceil((ATTENUATION_DB - 7.95) / (14.36 * transition)).toInt() + 1
            return if (taps % 2 == 0) taps + 1 else taps
        }
    }
}

/** A windowed-sinc lowpass with unit gain at 0 Hz. [cutoff] is a fraction of the sample rate. */
internal fun kaiserLowpass(taps: Int, cutoff: Double, beta: Double): DoubleArray {
    val middle = (taps - 1) / 2.0
    val window = besselI0(beta)
    val raw = DoubleArray(taps) { n ->
        val x = n - middle
        val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
        val r = if (taps == 1) 0.0 else (n - middle) / middle
        sinc * besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / window
    }
    val sum = raw.sum()
    return DoubleArray(taps) { raw[it] / sum }
}

/** The modified Bessel function of the first kind, order 0, by its power series. */
private fun besselI0(x: Double): Double {
    var sum = 1.0
    var term = 1.0
    var k = 1
    val half = x / 2
    while (term > sum * 1e-16) {
        term *= (half / k) * (half / k)
        sum += term
        k++
    }
    return sum
}
