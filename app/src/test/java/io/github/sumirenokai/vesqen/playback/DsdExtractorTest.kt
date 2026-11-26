package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import io.github.sumirenokai.vesqen.library.DsdContainer
import io.github.sumirenokai.vesqen.library.DsdHeaderResult
import io.github.sumirenokai.vesqen.library.DsdStreamInfo
import io.github.sumirenokai.vesqen.library.DsdUnsupported
import io.github.sumirenokai.vesqen.library.parseDsdHeader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #249: DSF and uncompressed DFF are read and converted to float PCM. */
@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DsdExtractorTest {
    @Test
    fun `DSF and DFF are recognised and other files are not`() {
        assertTrue(DsdExtractor().sniff(input(dsf())))
        assertTrue(DsdExtractor().sniff(input(dff())))
        assertFalse(DsdExtractor().sniff(input("RIFF\u0000\u0000\u0000\u0000WAVE\u0000\u0000\u0000\u0000".toByteArray())))
        assertFalse(DsdExtractor().sniff(input("FRM8\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000AIFF".toByteArray())))
    }

    @Test
    fun `a DSF becomes float PCM at 88_2 kHz with its duration and channels`() {
        val result = extract(dsf())
        assertEquals(MimeTypes.AUDIO_RAW, result.format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_FLOAT, result.format.pcmEncoding)
        assertEquals(88_200, result.format.sampleRate)
        assertEquals(2, result.format.channelCount)
        assertEquals(DSF_MIME_TYPE, result.format.containerMimeType)
        assertEquals(SAMPLES * 1_000_000L / DSD64, result.seekMap.durationUs)
        assertEquals((SAMPLES / 32 * 2).toInt(), result.pcm.size)
        // Left is all ones and right all zeros, so away from the filters' ringing at either end they
        // read +1 and −1.
        assertArrayEquals(floatArrayOf(1f, -1f), result.pcm.middleFrame(2), 1e-5f)
    }

    @Test
    fun `the same stream in DFF converts to the same PCM`() {
        val samples = SAMPLES.toInt()
        val left = DsdToPcmTest.modulate(samples) { n -> 0.4 * kotlin.math.sin(2 * Math.PI * 1_000 * n / DSD64) }
        val right = DsdToPcmTest.modulate(samples) { n -> 0.2 * kotlin.math.sin(2 * Math.PI * 5_000 * n / DSD64) }
        val fromDsf = extract(dsf(left, right))
        val fromDff = extract(dff(left, right))
        assertEquals(DFF_MIME_TYPE, fromDff.format.containerMimeType)
        assertEquals(fromDsf.seekMap.durationUs, fromDff.seekMap.durationUs)
        assertArrayEquals(fromDsf.pcm, fromDff.pcm, 0f)
    }

    @Test
    fun `padding after the last DSF sample is not converted`() {
        // 4,000 bytes of samples in a 4,096-byte block: the zero padding would pull the end to −1.
        // The same bytes in a DFF have no padding at all.
        val ones = ByteArray(4_000) { -1 }
        val result = extract(dsf(ones, ones, samples = 4_000L * 8))
        assertEquals(4_000 * 8 / 32 * 2, result.pcm.size)
        assertArrayEquals(extract(dff(ones, ones)).pcm, result.pcm, 0f)
    }

    @Test
    fun `small reads give the same PCM`() {
        assertArrayEquals(extract(dsf()).pcm, extract(dsf(), maxRead = 333).pcm, 0f)
        assertArrayEquals(extract(dff()).pcm, extract(dff(), maxRead = 333).pcm, 0f)
    }

    @Test
    fun `samples are timed by their frames`() {
        val result = extract(dsf())
        var frames = 0L
        for (sample in result.samples) {
            assertEquals(frames * 1_000_000 / 88_200, sample.timeUs)
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, sample.flags)
            frames += sample.size / 8
        }
    }

    @Test
    fun `a seek starts on a DSF block before the time and plays on from there`() {
        val file = dsf()
        val extractor = DsdExtractor()
        val output = FakeOutput()
        extractor.init(output)
        extractor.drain(file, 0)
        val seekMap = checkNotNull(output.seekMap)
        val target = 20_000L
        val point = seekMap.getSeekPoints(target).first
        // A block is 32,768 samples, 11.6 ms at DSD64; the point is at least the warm-up before.
        assertEquals(DSF_DATA_START, (point.position - DSF_DATA_START) % (4_096 * 2) + DSF_DATA_START)
        assertTrue(point.timeUs <= target - 2_000)
        assertTrue(point.timeUs > target - 2_000 - 11_610)

        output.track.reset()
        // Media3 passes the point's position with the requested time, not the point's own.
        extractor.seek(point.position, target)
        extractor.drain(file, point.position)
        assertEquals(point.timeUs, output.track.samples.first().timeUs)
        val framesAfter = (SAMPLES - (point.position - DSF_DATA_START) / 2 * 8) / 32
        assertEquals(framesAfter * 2, output.track.pcm.size().toLong() / 4)
    }

    @Test
    fun `a DFF seek is timed by the point it starts from`() {
        val file = dff()
        val extractor = DsdExtractor()
        val output = FakeOutput()
        extractor.init(output)
        extractor.drain(file, 0)
        val target = 20_000L
        val point = checkNotNull(output.seekMap).getSeekPoints(target).first
        assertTrue(point.timeUs in (target - 2_100)..(target - 2_000))
        output.track.reset()
        extractor.seek(point.position, target)
        extractor.drain(file, point.position)
        assertEquals(point.timeUs, output.track.samples.first().timeUs)
    }

    /**
     * After a read error ExoPlayer retries the same extractor from wherever the input stopped,
     * which can be inside a DSF block or a DFF frame. The retry must convert the same PCM.
     */
    @Test
    fun `a read that fails part way is retried from the start of the samples it was reading`() {
        // Longer than the header read, so the failure lands in a sample read and not in the header.
        val files = listOf(dsf(left = ones(12 * 4_096 - 100), right = zeros(12 * 4_096 - 100)), dff(left = ones(40_000), right = zeros(40_000)))
        for (file in files) {
            val clean = extract(file).pcm
            val extractor = DsdExtractor()
            val output = FakeOutput()
            extractor.init(output)
            extractor.drainFailingOnceAt(file, failAt = 70_001)
            val pcm = output.track.pcm.toByteArray()
            val floats = FloatArray(pcm.size / 4)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            assertArrayEquals(clean, floats, 0f)
        }
    }

    @Test
    fun `seek points stay inside the audio`() {
        for (file in listOf(dsf(), dff())) {
            val seekMap = extract(file).seekMap
            for (timeUs in listOf(0L, 1_000L, seekMap.durationUs, seekMap.durationUs * 2)) {
                val point = seekMap.getSeekPoints(timeUs).first
                assertTrue(point.timeUs in 0..seekMap.durationUs)
                assertTrue(point.position < file.size)
            }
        }
    }

    @Test
    fun `a seek to the start reads the header again`() {
        val file = dsf()
        val extractor = DsdExtractor()
        val output = FakeOutput()
        extractor.init(output)
        extractor.drain(file, 0)
        val first = output.track.pcm.toByteArray()
        output.track.reset()
        extractor.seek(0, 0)
        extractor.drain(file, 0)
        assertArrayEquals(first, output.track.pcm.toByteArray())
    }

    @Test
    fun `the conversion setting picks the rate and the gain`() {
        val dsd128 = dsf(rate = DSD64 * 2)
        val uniform = extract(dsd128)
        val multiple = extract(dsd128, conversion = DsdConversion(DsdOutputRateMode.MULTIPLE, gainDb = 6f))
        assertEquals(88_200, uniform.format.sampleRate)
        assertEquals(176_400, multiple.format.sampleRate)
        assertEquals(1.9953f, multiple.pcm.middleFrame(2)[0], 1e-3f)
    }

    @Test
    fun `compressed DST and unusual rates or channels are unsupported, not damaged`() {
        val dst = dff(compression = "DST ")
        assertEquals(DsdHeaderResult.Unsupported(DsdUnsupported.COMPRESSED), parseDsdHeader(dst, dst.size.toLong()))
        assertUnsupported(dst)
        assertUnsupported(dsf(rate = 1_000_000))
        assertUnsupported(dsf(channels = 8))
    }

    /**
     * Two tracks cut from one recording and played back to back: each starts on its first sample
     * and ends on its last, so only frames whose filters would need both files differ from playing
     * the recording whole. Media3 gives each file its own extractor, so those cannot be exact.
     */
    @Test
    fun `two halves of one recording join without a gap`() {
        val left = DsdToPcmTest.modulate(SAMPLES.toInt()) { n -> 0.5 * kotlin.math.sin(2 * kotlin.math.PI * 200 * n / DSD64) }
        val right = DsdToPcmTest.modulate(SAMPLES.toInt()) { n -> -0.4 * kotlin.math.sin(2 * kotlin.math.PI * 300 * n / DSD64) }
        val split = 4_096 * 2
        val builders = listOf<(ByteArray, ByteArray) -> ByteArray>({ l, r -> dsf(l, r) }, { l, r -> dff(l, r) })
        for (build in builders) {
            val whole = extract(build(left, right)).pcm
            val first = extract(build(left.copyOf(split), right.copyOf(split))).pcm
            val second = extract(build(left.copyOfRange(split, left.size), right.copyOfRange(split, right.size))).pcm
            assertEquals(whole.size, first.size + second.size)
            val joined = first + second
            val join = first.size / 2
            var errorEnergy = 0.0
            for (frame in 0 until whole.size / 2) for (channel in 0..1) {
                val error = joined[frame * 2 + channel] - whole[frame * 2 + channel]
                // Beyond the filters' reach of the join both decodes read the same bytes.
                if (kotlin.math.abs(frame - join) > JOIN_REACH) assertEquals("frame $frame", 0f, error, 0f) else errorEnergy += error * error
            }
            // A gap of the filters' delay at the join would leave about 20 frames of full error.
            assertTrue("error energy at the join $errorEnergy", errorEnergy < 0.25)
        }
    }

    /** The filters' delay is taken out: a change in the signal comes out at its own time. */
    @Test
    fun `a track's PCM is not delayed by the filters`() {
        // Silence for two blocks, then full scale on the left channel.
        val step = ByteArray(12_000) { if (it < 8_192) 0x69 else -1 }
        for (file in listOf(dsf(left = step, right = idle()), dff(left = step, right = idle()))) {
            val pcm = extract(file).pcm
            val crossing = DsdToPcmTest.halfwayCrossing(pcm, pcm.size / 2, channels = 2)
            assertTrue("the step comes out at frame $crossing", kotlin.math.abs(crossing - 2_048) <= 0.5)
        }
    }

    /** The DSDIFF specification lets chunks of any size come before the audio; unknown ones are skipped. */
    @Test
    fun `a DFF plays whatever chunks come before its audio`() {
        val plain = extract(dff()).pcm
        val cover = ByteArray(200_000) { (it * 7).toByte() }
        val tagged = dff(before = listOf("ID3 " to cover, "COMT" to ByteArray(3)))
        assertArrayEquals(plain, extract(tagged).pcm, 0f)
        assertArrayEquals(plain, extract(tagged, maxRead = 1_000).pcm, 0f)
        val info = (parseDsdHeader(tagged, tagged.size.toLong()) as DsdHeaderResult.Stream).info
        assertEquals(SAMPLES, info.samplesPerChannel)
    }

    /** A chunk that claims more than the whole file is damage, whichever reader meets it. */
    @Test
    fun `a DFF chunk larger than the file is a damaged file`() {
        val chunk = Be().ascii("COMT").u64(Long.MAX_VALUE).bytes()
        val plain = dff()
        // The bad chunk goes after PROP, where the extra chunks go; the form size is left as it was.
        val propEnd = 16 + 16 + 12 + plain.copyOfRange(16 + 16 + 4, 16 + 16 + 12).let { size -> (0 until 8).fold(0L) { n, i -> (n shl 8) or (size[i].toLong() and 255) } }.toInt()
        val broken = plain.copyOf(propEnd) + chunk + plain.copyOfRange(propEnd, plain.size)
        assertEquals(DsdHeaderResult.Unreadable, parseDsdHeader(broken, broken.size.toLong()))
        val error = assertThrows(ParserException::class.java) { extract(broken) }
        assertTrue(error.contentIsMalformed)
    }

    /** The order of a DFF's channel IDs is the order of its bytes; Android's order is front, centre, LFE, surround. */
    @Test
    fun `DFF channels are put in Android's order, and layouts it cannot name are refused`() {
        val centreFirst = extract(dffOf(listOf("C   " to ones(), "MLFT" to zeros(), "MRGT" to idle())))
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f), centreFirst.pcm.middleFrame(3), 1e-5f)
        val surroundFirst = extract(dffOf(listOf("LS  " to ones(), "RS  " to ones(), "MLFT" to zeros(), "MRGT" to zeros())))
        assertArrayEquals(floatArrayOf(-1f, -1f, 1f, 1f), surroundFirst.pcm.middleFrame(4), 1e-5f)
        // Numbered channels say nothing of where they play: stereo keeps the file's order.
        val numbered = extract(dffOf(listOf("C000" to ones(), "C001" to zeros())))
        assertArrayEquals(floatArrayOf(1f, -1f), numbered.pcm.middleFrame(2), 1e-5f)
        for (layout in listOf(listOf("MLFT", "MRGT", "C   ", "LFE "), listOf("MLFT", "MRGT", "C001"), listOf("MLFT", "MLFT"))) {
            val file = dffOf(layout.map { it to zeros() })
            assertEquals(layout.toString(), DsdHeaderResult.Unsupported(DsdUnsupported.LAYOUT), parseDsdHeader(file, file.size.toLong()))
        }
    }

    /** Sony's type 5 is front left, front right, centre and LFE, which Android's four channels are not. */
    @Test
    fun `a DSF with centre and LFE in four channels is refused`() {
        val quad = dsf(channels = 4, channelType = 4)
        assertTrue(parseDsdHeader(quad, quad.size.toLong()) is DsdHeaderResult.Stream)
        val withLfe = dsf(channels = 4, channelType = 5)
        assertEquals(DsdHeaderResult.Unsupported(DsdUnsupported.LAYOUT), parseDsdHeader(withLfe, withLfe.size.toLong()))
    }

    @Test
    fun `a header read that fails part way starts again from the beginning`() {
        for (file in listOf(dsf(), dff(before = listOf("COMT" to ByteArray(10_000))))) {
            val clean = extract(file).pcm
            val extractor = DsdExtractor()
            val output = FakeOutput()
            extractor.init(output)
            extractor.drainFailingOnceAt(file, failAt = 50)
            val pcm = output.track.pcm.toByteArray()
            val floats = FloatArray(pcm.size / 4)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            assertArrayEquals(clean, floats, 0f)
        }
    }

    @Test
    fun `a stream of unknown length converts the same`() {
        for (file in listOf(dsf(), dff())) {
            val extractor = DsdExtractor()
            val output = FakeOutput()
            extractor.init(output)
            extractor.drain(file, 0, knownLength = false)
            assertEquals(extract(file).pcm.size * 4, output.track.pcm.size())
        }
    }

    /** After the warm-up the filters hold only samples from after the seek point. */
    @Test
    fun `the PCM after a seek is the PCM of an uninterrupted decode`() {
        val signal = DsdToPcmTest.modulate(SAMPLES.toInt()) { n -> 0.4 * kotlin.math.sin(n * 0.002) }
        val other = DsdToPcmTest.modulate(SAMPLES.toInt()) { n -> 0.3 * kotlin.math.cos(n * 0.0013) }
        for (file in listOf(dsf(left = signal, right = other), dff(left = signal, right = other))) {
            val whole = extract(file).pcm
            val extractor = DsdExtractor()
            val output = FakeOutput()
            extractor.init(output)
            extractor.drain(file, 0)
            val point = checkNotNull(output.seekMap).getSeekPoints(20_000).first
            output.track.reset()
            extractor.seek(point.position, 20_000)
            extractor.drain(file, point.position)
            val pcm = output.track.pcm.toByteArray()
            val after = FloatArray(pcm.size / 4)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(after)
            // Frames from 1 ms after the seek point on, compared with the same frames of the whole decode.
            val skip = 88 * 2
            val offset = whole.size - after.size
            for (index in skip until after.size) assertEquals("sample $index", whole[offset + index], after[index], 0f)
        }
    }

    @Test
    fun `a duration far past what a long holds in microseconds is still right`() {
        val samples = 10_000_000_000_000L
        val info = DsdStreamInfo(DsdContainer.DSF, DSD64, 2, samples, 92, 4_096, true)
        assertEquals(java.math.BigInteger.valueOf(samples).multiply(java.math.BigInteger.valueOf(1_000_000)).divide(java.math.BigInteger.valueOf(DSD64.toLong())).toLong(), info.durationUs)
    }

    @Test
    fun `a file cut short plays the whole blocks it still holds, as AIFF does`() {
        // DSF: one whole block group of the three, and part of the next.
        val dsf = dsf().let { it.copyOf(DSF_DATA_START.toInt() + 4_096 * 2 + 1_000) }
        val cutDsf = extract(dsf)
        assertEquals(4_096L * 8 * 1_000_000 / DSD64, cutDsf.seekMap.durationUs)
        assertEquals(4_096 * 8 / 32 * 2, cutDsf.pcm.size)
        // DFF: whole frames, one byte per channel.
        val full = dff()
        val cutDff = extract(full.copyOf(full.size - 1_001))
        assertEquals((SAMPLES / 8 - 501) * 8 * 1_000_000 / DSD64, cutDff.seekMap.durationUs)
        assertEquals((SAMPLES / 8 - 501).toInt() * 8 / 32 * 2, cutDff.pcm.size)
    }

    @Test
    fun `a cut or inconsistent header is a damaged file`() {
        val file = dsf()
        for (broken in listOf(file.copyOf(60), file.copyOf(DSF_DATA_START.toInt() + 100), dff().copyOf(40))) {
            val error = assertThrows(ParserException::class.java) { extract(broken) }
            assertTrue(error.contentIsMalformed)
        }
    }

    @Test
    fun `the header reports what the library needs`() {
        val info = (parseDsdHeader(dsf(), null) as DsdHeaderResult.Stream).info
        assertEquals(DsdContainer.DSF, info.container)
        assertEquals(DSF_DATA_START, info.dataStart)
        assertTrue(info.lsbFirst)
        val dff = (parseDsdHeader(dff(rate = DSD64 * 2), null) as DsdHeaderResult.Stream).info
        assertEquals(DsdContainer.DFF, dff.container)
        assertEquals(DSD64 * 2, dff.sampleRate)
        assertEquals(3_072_000, (parseDsdHeader(dsf(rate = 3_072_000), null) as DsdHeaderResult.Stream).info.sampleRate)
    }

    private fun assertUnsupported(file: ByteArray) {
        val error = assertThrows(ParserException::class.java) { extract(file) }
        assertFalse(error.contentIsMalformed)
    }

    private fun extract(file: ByteArray, maxRead: Int = Int.MAX_VALUE, conversion: DsdConversion = DsdConversion()): Result {
        val extractor = DsdExtractor { conversion }
        val output = FakeOutput()
        assertTrue(extractor.sniff(input(file)))
        extractor.init(output)
        extractor.drain(file, 0, maxRead)
        val pcm = output.track.pcm.toByteArray()
        val floats = FloatArray(pcm.size / 4)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return Result(checkNotNull(output.track.format), floats, output.track.samples.toList(), checkNotNull(output.seekMap))
    }

    private fun Extractor.drain(file: ByteArray, start: Long, maxRead: Int = Int.MAX_VALUE, knownLength: Boolean = true) {
        val holder = PositionHolder()
        var input = input(file, start, maxRead, knownLength = knownLength)
        while (true) {
            when (read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = input(file, holder.position, maxRead, knownLength = knownLength)
                else -> return
            }
        }
    }

    /**
     * Like a progressive load: a read error keeps the extractor and reopens where the input stopped.
     * Only the injected failure is retried; a parse error ends the load, as it does in Media3.
     */
    private fun Extractor.drainFailingOnceAt(file: ByteArray, failAt: Int) {
        val holder = PositionHolder()
        var failed = false
        var input = input(file, 0, failAt = failAt) { failed = true }
        var opens = 1
        while (true) {
            assertTrue("reopened $opens times", opens < 100)
            val result = try {
                read(input, holder)
            } catch (_: InjectedReadFailure) {
                input = input(file, input.position)
                opens++
                continue
            }
            when (result) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    input = input(file, holder.position)
                    opens++
                }
                else -> break
            }
        }
        assertTrue("the read never failed", failed)
    }

    private class InjectedReadFailure : IOException("read failed")

    private fun input(
        file: ByteArray,
        position: Long = 0,
        maxRead: Int = Int.MAX_VALUE,
        failAt: Int = Int.MAX_VALUE,
        knownLength: Boolean = true,
        onFailure: () -> Unit = {},
    ): DefaultExtractorInput {
        var next = position.toInt()
        val reader = DataReader { target, offset, length ->
            if (next >= failAt) {
                onFailure()
                throw InjectedReadFailure()
            }
            if (next >= file.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val count = minOf(length, maxRead, file.size - next, failAt - next)
                file.copyInto(target, offset, next, next + count)
                next += count
                count
            }
        }
        return DefaultExtractorInput(reader, position, if (knownLength) file.size.toLong() else C.LENGTH_UNSET.toLong())
    }

    /** The frame halfway through interleaved [channels]-channel PCM. */
    private fun FloatArray.middleFrame(channels: Int): FloatArray {
        val start = size / channels / 2 * channels
        return copyOfRange(start, start + channels)
    }

    private class Result(val format: Format, val pcm: FloatArray, val samples: List<Sample>, val seekMap: SeekMap)
    private class Sample(val timeUs: Long, val flags: Int, val size: Int)

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class FakeTrack : TrackOutput {
        var format: Format? = null
        var pcm = ByteArrayOutputStream()
        val samples = mutableListOf<Sample>()

        fun reset() {
            pcm = ByteArrayOutputStream()
            samples.clear()
        }

        override fun format(format: Format) {
            this.format = format
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("The extractor writes converted PCM itself")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pcm.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += Sample(timeUs, flags, size)
        }
    }

    internal companion object {
        const val DSD64 = 2_822_400
        /** Three DSF blocks less a little: 12,000 bytes per channel. */
        const val SAMPLES = 12_000L * 8
        const val DSF_DATA_START = 92L

        /** Output frames on either side of a join that the filters reach across: their total span, halved, with a frame to spare. */
        const val JOIN_REACH = 24

        /** Channel bytes with the earliest sample in the highest bit, as [DsdToPcmTest.modulate] writes. */
        fun ones(bytes: Int = (SAMPLES / 8).toInt()) = ByteArray(bytes) { -1 }
        fun zeros(bytes: Int = (SAMPLES / 8).toInt()) = ByteArray(bytes)
        fun idle(bytes: Int = (SAMPLES / 8).toInt()) = ByteArray(bytes) { 0x69 }

        fun dsf(
            left: ByteArray = ones(),
            right: ByteArray = zeros(),
            samples: Long = left.size * 8L,
            rate: Int = DSD64,
            channels: Int = 2,
            channelType: Int = 2,
        ): ByteArray {
            val block = 4_096
            val perChannel = left.size
            val blocks = (perChannel + block - 1) / block
            val data = ByteArrayOutputStream()
            val sources = List(channels) { if (it % 2 == 0) left else right }
            for (index in 0 until blocks) for (source in sources) {
                val chunk = ByteArray(block)
                val from = index * block
                for (i in 0 until minOf(block, perChannel - from)) chunk[i] = reverseBits(source[from + i])
                data.write(chunk)
            }
            val body = data.toByteArray()
            val out = Le()
            out.ascii("DSD ").u64(28).u64(DSF_DATA_START + body.size).u64(0)
            out.ascii("fmt ").u64(52).u32(1).u32(0).u32(channelType.toLong()).u32(channels.toLong()).u32(rate.toLong()).u32(1)
                .u64(samples).u32(block.toLong()).u32(0)
            out.ascii("data").u64(12L + body.size)
            return out.bytes() + body
        }

        fun dff(
            left: ByteArray = ones(),
            right: ByteArray = zeros(),
            rate: Int = DSD64,
            compression: String = "DSD ",
            before: List<Pair<String, ByteArray>> = emptyList(),
        ): ByteArray = dffOf(listOf("SLFT" to left, "SRGT" to right), rate, compression, before)

        /** A DFF with these channels, in this order, and [before] as chunks between PROP and the audio. */
        fun dffOf(
            channels: List<Pair<String, ByteArray>>,
            rate: Int = DSD64,
            compression: String = "DSD ",
            before: List<Pair<String, ByteArray>> = emptyList(),
        ): ByteArray {
            val frames = channels.first().second.size
            val interleaved = ByteArray(frames * channels.size) { index -> channels[index % channels.size].second[index / channels.size] }
            val name = "not compressed".toByteArray()
            val cmprBody = Be().ascii(compression).u8(name.size).raw(name).also { if ((5 + name.size) % 2 == 1) it.u8(0) }.bytes()
            val chnl = Be().u16(channels.size).also { body -> channels.forEach { body.ascii(it.first) } }.bytes()
            val prop = Be().ascii("SND ")
                .chunk("FS  ", Be().u32(rate.toLong()).bytes())
                .chunk("CHNL", chnl)
                .chunk("CMPR", cmprBody)
                .bytes()
            val dataId = if (compression == "DSD ") "DSD " else "DST "
            val form = Be().ascii("DSD ")
                .chunk("FVER", Be().u32(0x01050000).bytes())
                .chunk("PROP", prop)
                .also { out -> before.forEach { (id, body) -> out.chunk(id, body) } }
                .chunk(dataId, interleaved)
                .bytes()
            return Be().ascii("FRM8").u64(form.size.toLong()).raw(form).bytes()
        }

        fun reverseBits(byte: Byte): Byte =
            (0 until 8).fold(0) { out, bit -> out or (((byte.toInt() shr bit) and 1) shl (7 - bit)) }.toByte()
    }

    private class Le {
        private val out = ByteArrayOutputStream()
        fun ascii(text: String) = apply { out.write(text.toByteArray(Charsets.US_ASCII)) }
        fun u32(value: Long) = apply { for (i in 0 until 4) out.write(((value shr (8 * i)) and 255).toInt()) }
        fun u64(value: Long) = apply { for (i in 0 until 8) out.write(((value shr (8 * i)) and 255).toInt()) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Be {
        private val out = ByteArrayOutputStream()
        fun ascii(text: String) = apply { out.write(text.toByteArray(Charsets.US_ASCII)) }
        fun u8(value: Int) = apply { out.write(value) }
        fun u16(value: Int) = apply { out.write(value shr 8); out.write(value and 255) }
        fun u32(value: Long) = apply { for (i in 3 downTo 0) out.write(((value shr (8 * i)) and 255).toInt()) }
        fun u64(value: Long) = apply { for (i in 7 downTo 0) out.write(((value shr (8 * i)) and 255).toInt()) }
        fun raw(bytes: ByteArray) = apply { out.write(bytes) }
        fun chunk(id: String, body: ByteArray) = apply {
            ascii(id).u64(body.size.toLong()).raw(body)
            if (body.size % 2 == 1) out.write(0)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
