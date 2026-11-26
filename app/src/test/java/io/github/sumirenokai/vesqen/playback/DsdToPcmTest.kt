package io.github.sumirenokai.vesqen.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #249: the DSD to PCM filters and converter. */
class DsdToPcmTest {
    @Test
    fun `output rates follow the setting`() {
        assertEquals(88_200, dsdOutputRate(DSD64, DsdOutputRateMode.UNIFORM))
        assertEquals(88_200, dsdOutputRate(DSD64 * 8, DsdOutputRateMode.UNIFORM))
        assertEquals(96_000, dsdOutputRate(3_072_000, DsdOutputRateMode.UNIFORM))
        assertEquals(88_200, dsdOutputRate(DSD64, DsdOutputRateMode.MULTIPLE))
        assertEquals(176_400, dsdOutputRate(DSD64 * 2, DsdOutputRateMode.MULTIPLE))
        assertEquals(176_400, dsdOutputRate(DSD64 * 4, DsdOutputRateMode.MULTIPLE))
        assertEquals(192_000, dsdOutputRate(3_072_000 * 2, DsdOutputRateMode.MULTIPLE))
    }

    /**
     * Each stage must pass the output's band and stop whatever its own decimation would fold onto
     * it; together they pass 20 kHz flat. Checked for every rate pair Vesqen converts.
     */
    @Test
    fun `filters keep 20 kHz flat and stop what decimation would fold onto the output band`() {
        for (dsdRate in SUPPORTED_RATES) for (mode in DsdOutputRateMode.entries) {
            val output = dsdOutputRate(dsdRate, mode)
            val design = DsdFilterDesign.forRates(dsdRate, output)
            val label = "$dsdRate Hz to $output Hz"
            val stages = listOf(design.firstStage to dsdRate) +
                design.halfStages.mapIndexed { index, taps -> taps.map(Float::toDouble).toDoubleArray() to dsdRate / 8 / (1 shl index) }

            var hz = 0.0
            while (hz <= 20_000) {
                val gain = stages.sumOf { (taps, rate) -> responseDb(taps, hz / rate) }
                assertTrue("$label: ${"%.4f".format(gain)} dB at $hz Hz", abs(gain) < 0.001)
                hz += 250
            }
            val firstOut = dsdRate / 8
            assertStops(label, design.firstStage, dsdRate, from = firstOut - output / 2.0, to = dsdRate / 2.0)
            design.halfStages.forEachIndexed { index, taps ->
                val rate = dsdRate / 8 / (1 shl index)
                val last = index == design.halfStages.lastIndex
                val from = if (last) output / 2.0 else rate / 2.0 - output / 2.0
                assertStops("$label, 2:1 stage $index", taps.map(Float::toDouble).toDoubleArray(), rate, from, rate / 2.0)
            }
        }
    }

    /**
     * The filters' delay is dropped at the start and drained at the end: a stream gives one frame
     * per [DsdToPcmConverter.decimation] samples, and a step in the input comes out at its own frame.
     */
    @Test
    fun `a step comes out at its own frame at every rate`() {
        for (dsdRate in SUPPORTED_RATES) for (mode in DsdOutputRateMode.entries) {
            val output = dsdOutputRate(dsdRate, mode)
            val converter = DsdToPcmConverter(dsdRate, channels = 1, outputRate = output, gainDb = 0f)
            val stepFrame = 128
            val stepByte = stepFrame * converter.decimation / 8
            val bytes = ByteArray(BYTES) { if (it < stepByte) 0x69 else -1 }
            val out = FloatArray(BYTES * 8 / converter.decimation + converter.latencyFrames + 1)
            var frames = converter.process(0, bytes, 0, bytes.size, false, out, 0)
            frames += converter.drain(0, out, frames)
            val label = "$dsdRate Hz to $output Hz"
            assertEquals(label, BYTES * 8 / converter.decimation, frames)
            val crossing = halfwayCrossing(out, frames, channels = 1)
            // The rounding left in the latency is under half a frame.
            assertTrue("$label: the step comes out at frame $crossing", abs(crossing - stepFrame) <= 0.5)
        }
    }

    @Test
    fun `all ones convert to full scale and the idle pattern to silence`() {
        // Away from the filters' ringing where the input starts and stops.
        val ones = converted(ByteArray(BYTES) { -1 })
        val idle = converted(ByteArray(BYTES) { 0x69 })
        for (value in ones.drop(WARM_UP).dropLast(WARM_UP)) assertEquals(1f, value, 1e-5f)
        for (value in idle) assertEquals(0f, value, 1e-5f)
    }

    @Test
    fun `gain is added in decibels and stops at 6 dB`() {
        val ones = ByteArray(BYTES) { -1 }
        fun middle(gainDb: Float) = converted(ones, gainDb).let { it[it.size / 2] }
        assertEquals(1.4125f, middle(3f), 1e-3f)
        assertEquals(1.9953f, middle(6f), 1e-3f)
        assertEquals(1.9953f, middle(9f), 1e-3f)
        assertEquals(1f, middle(-3f), 1e-5f)
    }

    /** DSD's 0 dB reference is 50 % modulation: a sine at that level converts to half of full scale. */
    @Test
    fun `a 1 kHz sine converts at its level with a clean audio band`() {
        val frames = 8_820 // 0.1 s, exactly 100 cycles of 1 kHz at 88.2 kHz
        // Signal on both sides of the window, which the filters' ringing at the ends stays out of.
        val dsd = modulate(samples = (WARM_UP + frames + WARM_UP) * 32) { n -> 0.5 * sin(2 * PI * 1_000 * n / DSD64) }
        val pcm = converted(dsd).drop(WARM_UP).take(frames)
        assertEquals(frames, pcm.size)

        fun bin(k: Int): Double {
            var re = 0.0
            var im = 0.0
            pcm.forEachIndexed { n, value ->
                re += value * cos(2 * PI * k * n / frames)
                im -= value * sin(2 * PI * k * n / frames)
            }
            return hypot(re, im) * 2 / frames
        }
        val level = bin(100)
        assertEquals("level", 0.5, level, 0.0025)
        // Every other 10 Hz bin up to 20 kHz is noise.
        val noise = (1..2_000).filter { it != 100 }.sumOf { bin(it).let { amplitude -> amplitude * amplitude } }
        val snr = 10 * log10(level * level / noise)
        assertTrue("SNR $snr dB", snr > 60)
    }

    @Test
    fun `DSF bytes with the earliest sample in the low bit convert like DFF bytes`() {
        val dsd = modulate(samples = BYTES * 8) { n -> 0.3 * sin(2 * PI * 3_000 * n / DSD64) }
        val reversed = ByteArray(dsd.size) { index ->
            (0 until 8).fold(0) { out, bit -> out or (((dsd[index].toInt() shr bit) and 1) shl (7 - bit)) }.toByte()
        }
        assertArrayEquals(converted(dsd).toFloatArray(), converted(reversed, lsbFirst = true).toFloatArray(), 0f)
    }

    @Test
    fun `channels are interleaved and each frame takes the rate ratio of samples`() {
        val converter = DsdToPcmConverter(DSD64, channels = 2, outputRate = 88_200, gainDb = 0f)
        assertEquals(32, converter.decimation)
        val out = FloatArray((BYTES * 8 / 32 + converter.latencyFrames) * 2)
        val left = converter.process(0, ByteArray(BYTES) { -1 }, 0, BYTES, false, out, 0)
        val right = converter.process(1, ByteArray(BYTES) { 0 }, 0, BYTES, false, out, 0)
        assertEquals(left, right)
        assertEquals(BYTES * 8 / 32, left + converter.drain(0, out, left))
        assertEquals(BYTES * 8 / 32, right + converter.drain(1, out, right))
        val middle = BYTES * 8 / 32 / 2
        assertEquals(1f, out[middle * 2], 1e-5f)
        assertEquals(-1f, out[middle * 2 + 1], 1e-5f)
    }

    @Test
    fun `a reset forgets the history`() {
        val converter = DsdToPcmConverter(DSD64, channels = 1, outputRate = 88_200, gainDb = 0f)
        val out = FloatArray(BYTES)
        converter.process(0, ByteArray(BYTES) { -1 }, 0, BYTES, false, out, 0)
        converter.reset()
        val frames = converter.process(0, ByteArray(BYTES) { 0x69 }, 0, BYTES, false, out, 0)
        // Straight after a reset the history is idle pattern, so even the first frames are silent.
        for (index in 0 until frames) assertEquals(0f, out[index], 1e-5f)
    }

    /** The whole stream, drained at the end as the extractor does. */
    private fun converted(bytes: ByteArray, gainDb: Float = 0f, lsbFirst: Boolean = false): List<Float> {
        val converter = DsdToPcmConverter(DSD64, channels = 1, outputRate = 88_200, gainDb = gainDb)
        val out = FloatArray(bytes.size * 8 / converter.decimation + converter.latencyFrames + 1)
        var frames = converter.process(0, bytes, 0, bytes.size, lsbFirst, out, 0)
        frames += converter.drain(0, out, frames)
        return out.take(frames)
    }

    private fun assertStops(label: String, taps: DoubleArray, rate: Int, from: Double, to: Double) {
        val points = 400
        for (step in 0..points) {
            val hz = from + (to - from) * step / points
            val db = responseDb(taps, hz / rate)
            assertTrue("$label: ${"%.1f".format(db)} dB at ${"%.0f".format(hz)} Hz", db < -120)
        }
    }

    internal companion object {
        const val DSD64 = 2_822_400
        const val BYTES = 32_768
        /** Output frames before the filters have settled, with plenty of margin. */
        const val WARM_UP = 256
        val SUPPORTED_RATES = listOf(DSD64, DSD64 * 2, DSD64 * 4, DSD64 * 8, 3_072_000, 3_072_000 * 2, 3_072_000 * 4, 3_072_000 * 8)

        /** Where channel 0 of interleaved [pcm] first reaches 0.5, interpolated between frames. */
        fun halfwayCrossing(pcm: FloatArray, frames: Int, channels: Int): Double {
            val after = (1 until frames).first { pcm[it * channels] >= 0.5f }
            val before = pcm[(after - 1) * channels]
            return after - 1 + (0.5 - before) / (pcm[after * channels] - before)
        }

        fun responseDb(taps: DoubleArray, frequency: Double): Double {
            var re = 0.0
            var im = 0.0
            taps.forEachIndexed { n, tap ->
                re += tap * cos(2 * PI * frequency * n)
                im -= tap * sin(2 * PI * frequency * n)
            }
            return 20 * log10(maxOf(hypot(re, im), 1e-20))
        }

        /**
         * A second-order sigma-delta modulator in error-feedback form, noise transfer (1 − z⁻¹)²,
         * writing the earliest sample in the highest bit as DFF does.
         */
        fun modulate(samples: Int, signal: (Int) -> Double): ByteArray {
            val bytes = ByteArray(samples / 8)
            var e1 = 0.0
            var e2 = 0.0
            for (n in 0 until samples) {
                val w = signal(n) - 2 * e1 + e2
                val y = if (w >= 0) 1.0 else -1.0
                e2 = e1
                e1 = y - w
                if (y > 0) bytes[n / 8] = (bytes[n / 8].toInt() or (0x80 ushr (n % 8))).toByte()
            }
            return bytes
        }
    }
}
