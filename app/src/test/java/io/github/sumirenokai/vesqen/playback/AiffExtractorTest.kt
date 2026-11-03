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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AiffExtractorTest {
    @Test fun `only AIFF and AIFF-C are recognized`() {
        assertTrue(AiffExtractor().sniff(input(aiff())))
        assertTrue(AiffExtractor().sniff(input(aifc("sowt"))))
        assertFalse(AiffExtractor().sniff(input("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray())))
        assertFalse(AiffExtractor().sniff(input("FORM\u0000\u0000\u0000\u0000XXXX".toByteArray())))
    }

    @Test fun `samples become little-endian whatever their width`() {
        for ((bits, encoding) in listOf(16 to C.ENCODING_PCM_16BIT, 24 to C.ENCODING_PCM_24BIT, 32 to C.ENCODING_PCM_32BIT)) {
            val result = extract(aiff(bits = bits))
            assertEquals(MimeTypes.AUDIO_RAW, result.format.sampleMimeType)
            assertEquals(encoding, result.format.pcmEncoding)
            assertEquals(44_100, result.format.sampleRate)
            assertEquals(2, result.format.channelCount)
            assertArrayEquals(littleEndian(bits), result.pcm)
        }
    }

    @Test fun `a sample width between whole bytes fills its bytes`() {
        val result = extract(aiff(bits = 20, stored = 24))
        assertEquals(C.ENCODING_PCM_24BIT, result.format.pcmEncoding)
        assertArrayEquals(littleEndian(24), result.pcm)
    }

    @Test fun `sowt is already little-endian and big-endian AIFF-C types are not`() {
        assertArrayEquals(bigEndian(16), extract(aifc("sowt", bits = 16)).pcm)
        for (type in listOf("NONE", "twos")) assertArrayEquals(littleEndian(16), extract(aifc(type, bits = 16)).pcm)
        assertArrayEquals(littleEndian(24), extract(aifc("in24", bits = 24)).pcm)
        assertArrayEquals(littleEndian(32), extract(aifc("in32", bits = 32)).pcm)
    }

    @Test fun `the sample rate comes from the extended float`() {
        for (rate in listOf(8_000, 44_100, 48_000, 88_200, 96_000, 192_000, 352_800, 768_000)) {
            assertEquals(rate, extract(aiff(rate = rate)).format.sampleRate)
        }
        // A rate from old Macs, stored as a fraction.
        val fractional = aiff(rateBytes = byteArrayOf(0x40, 0x0D, -0x53, -0x22, 0x2E, -0x75, -0x46, 0x2E, -0x75, -0x46))
        assertEquals(22_255, extract(fractional).format.sampleRate)
    }

    @Test fun `metadata and padding around the audio are skipped`() {
        val file = form(
            "AIFF",
            chunk("NAME", ByteArray(3) { 65 }),
            chunk("COMM", comm(bits = 16, rate = 44_100, frames = FRAMES)),
            chunk("ID3 ", ByteArray(7)),
            chunk("SSND", ssnd(bigEndian(16), offset = 5)),
            chunk("ANNO", ByteArray(4) { 66 }),
        )
        assertArrayEquals(littleEndian(16), extract(file).pcm)
    }

    @Test fun `SSND may come before COMM`() {
        val pcm = ByteArray(64 * 1024 * 8) { (it % 2 * 3).toByte() }
        val file = form("AIFF", chunk("SSND", ssnd(pcm)), chunk("COMM", comm(bits = 16, rate = 44_100, frames = pcm.size / 4)))
        val result = extract(file)
        assertEquals(pcm.size, result.pcm.size)
        // Over the audio to COMM, then back.
        assertEquals(2, result.seeks)
    }

    @Test fun `a long chunk is jumped over rather than read through`() {
        val file = form(
            "AIFF",
            chunk("JUNK", ByteArray(1024 * 1024)),
            chunk("COMM", comm(bits = 16, rate = 44_100, frames = FRAMES)),
            chunk("SSND", ssnd(bigEndian(16))),
        )
        val result = extract(file)
        assertEquals(1, result.seeks)
        assertArrayEquals(littleEndian(16), result.pcm)
    }

    @Test fun `small reads keep whole frames and the same audio`() {
        val whole = extract(aiff(bits = 24))
        val small = extract(aiff(bits = 24), maxRead = 1)
        assertArrayEquals(whole.pcm, small.pcm)
        assertTrue(small.samples.all { it.size % 6 == 0 })
    }

    @Test fun `samples are timed by their frames and stay within a tenth of a second`() {
        val frames = 44_100
        val result = extract(aiff(frames = frames, pcm = ByteArray(frames * 4)))
        assertEquals(1_000_000L, result.seekMap.durationUs)
        assertEquals(10, result.samples.size)
        assertEquals(listOf(0L, 100_000L, 200_000L), result.samples.take(3).map { it.timeUs })
        assertTrue(result.samples.all { it.flags == C.BUFFER_FLAG_KEY_FRAME })
        assertEquals(result.samples.maxOf { it.size }, result.format.maxInputSize)
    }

    @Test fun `a seek lands on a frame and plays on from there`() {
        val frames = 44_100
        val pcm = ByteArray(frames * 4) { (it / 4 % 251).toByte() }
        val file = aiff(frames = frames, pcm = pcm)
        val seekMap = extract(file).seekMap
        val points = seekMap.getSeekPoints(500_000)
        assertEquals(0L, (points.first.position - DATA_OFFSET) % 4)
        assertTrue(points.first.timeUs <= 500_000)
        assertTrue(points.second.timeUs >= 500_000)

        val extractor = AiffExtractor()
        val output = FakeOutput()
        extractor.init(output)
        extractor.drain(file, 0, maxRead = Int.MAX_VALUE)
        val before = output.track.pcm.size()
        val samplesBefore = output.track.samples.size
        extractor.seek(points.first.position, points.first.timeUs)
        extractor.drain(file, points.first.position, maxRead = Int.MAX_VALUE)
        assertEquals(points.first.timeUs, output.track.samples[samplesBefore].timeUs)
        assertArrayEquals(
            pcm.copyOfRange((points.first.position - DATA_OFFSET).toInt(), pcm.size).swapped(2),
            output.track.pcm.toByteArray().copyOfRange(before, output.track.pcm.size()),
        )
    }

    @Test fun `seek points stay inside the audio`() {
        val seekMap = extract(aiff()).seekMap
        assertEquals(DATA_OFFSET, seekMap.getSeekPoints(0).first.position)
        val lastFrame = DATA_OFFSET + (FRAMES - 1) * 4
        assertEquals(lastFrame, seekMap.getSeekPoints(10_000_000).first.position)
        assertEquals(seekMap.getSeekPoints(10_000_000).first, seekMap.getSeekPoints(10_000_000).second)
    }

    @Test fun `a seek to the start reads the header again`() {
        val file = aiff()
        val extractor = AiffExtractor()
        val output = FakeOutput()
        extractor.init(output)
        extractor.drain(file, 0, maxRead = Int.MAX_VALUE)
        val first = output.track.pcm.size()
        extractor.seek(0, 0)
        extractor.drain(file, 0, maxRead = Int.MAX_VALUE)
        assertEquals(first * 2, output.track.pcm.size())
    }

    @Test fun `audio stops at the shorter of what COMM says and what SSND holds`() {
        assertEquals(8, extract(aiff(frames = 2)).pcm.size)
        // Some writers never fill the count in.
        assertEquals(FRAMES * 4, extract(aiff(frames = 0)).pcm.size)
        assertEquals(FRAMES * 4, extract(aiff(frames = FRAMES + 1000)).pcm.size)
    }

    @Test fun `a file cut inside the audio plays what is there`() {
        val whole = aiff()
        val cut = extract(whole.copyOf(whole.size - 5))
        assertEquals(FRAMES * 4 - 8, cut.pcm.size)
        assertEquals(cut.pcm.size.toLong() / 4 * 1_000_000 / 44_100, cut.seekMap.durationUs)
    }

    @Test fun `a cut or empty header is a malformed file`() {
        val whole = aiff()
        val firstAudioByte = DATA_OFFSET.toInt()
        for (length in 12 until firstAudioByte) {
            val error = assertThrows("cut at $length", ParserException::class.java) { extract(whole.copyOf(length)) }
            assertTrue(error.contentIsMalformed)
        }
        val noAudio = form("AIFF", chunk("COMM", comm(bits = 16, rate = 44_100, frames = 0)), chunk("SSND", ssnd(ByteArray(0))))
        assertThrows(ParserException::class.java) { extract(noAudio) }
        val noComm = form("AIFF", chunk("SSND", ssnd(bigEndian(16))))
        assertThrows(ParserException::class.java) { extract(noComm) }
    }

    @Test fun `sizes and offsets from the file are not trusted`() {
        val shortComm = form("AIFF", chunk("COMM", ByteArray(4)), chunk("SSND", ssnd(bigEndian(16))))
        assertTrue(assertThrows(ParserException::class.java) { extract(shortComm) }.contentIsMalformed)
        val shortSsnd = form("AIFF", chunk("COMM", comm(16, 44_100, FRAMES)), chunk("SSND", ByteArray(4)))
        assertThrows(ParserException::class.java) { extract(shortSsnd) }
        val hugeOffset = form("AIFF", chunk("COMM", comm(16, 44_100, FRAMES)), chunk("SSND", ssnd(bigEndian(16), offset = 0).also { ByteBuffer.wrap(it).putInt(0, Int.MAX_VALUE) }))
        assertThrows(ParserException::class.java) { extract(hugeOffset) }
        val hugeChunk = form("AIFF", "JUNK".toByteArray() + byteArrayOf(-1, -1, -1, -1), chunk("COMM", comm(16, 44_100, FRAMES)))
        assertThrows(ParserException::class.java) { extract(hugeChunk) }
        val hugeAudio = form("AIFF", chunk("COMM", comm(16, 44_100, 0)), "SSND".toByteArray() + byteArrayOf(-1, -1, -1, -1) + ByteArray(8) + bigEndian(16))
        assertArrayEquals(littleEndian(16), extract(hugeAudio).pcm)
    }

    @Test fun `a file of endless small chunks stops`() {
        val chunks = ByteArrayOutputStream().apply { repeat(2000) { write(chunk("JUNK", ByteArray(0))) } }.toByteArray()
        assertThrows(ParserException::class.java) {
            extract(form("AIFF", chunks, chunk("COMM", comm(16, 44_100, FRAMES)), chunk("SSND", ssnd(bigEndian(16)))))
        }
    }

    @Test fun `audio Vesqen does not play is reported as unsupported`() {
        for (type in listOf("fl32", "FL32", "fl64", "alaw", "ulaw", "ima4", "ACE2")) {
            val error = assertThrows(type, ParserException::class.java) { extract(aifc(type)) }
            assertFalse(type, error.contentIsMalformed)
            assertEquals(type, C.DATA_TYPE_MEDIA, error.dataType)
        }
        val eightBit = form("AIFF", chunk("COMM", comm(8, 44_100, FRAMES)), chunk("SSND", ssnd(ByteArray(FRAMES * 2))))
        assertThrows(ParserException::class.java) { extract(eightBit) }
        val noChannels = form("AIFF", chunk("COMM", comm(16, 44_100, FRAMES, channels = 0)), chunk("SSND", ssnd(bigEndian(16))))
        assertThrows(ParserException::class.java) { extract(noChannels) }
        val tooManyChannels = form("AIFF", chunk("COMM", comm(16, 44_100, FRAMES, channels = 33)), chunk("SSND", ssnd(bigEndian(16))))
        assertThrows(ParserException::class.java) { extract(tooManyChannels) }
    }

    @Test fun `the factory offers AIFF first and keeps Media3's extractors`() {
        val factory = VesqenExtractorsFactory()
        val extractors = factory.createExtractors()
        assertTrue(extractors.first() is AiffExtractor)
        assertTrue(extractors.size > 1)
        assertTrue(extractors.drop(1).none { it is AiffExtractor })
        assertTrue(factory.createExtractors(android.net.Uri.parse("file:///a.aiff"), emptyMap()).first() is AiffExtractor)
    }

    private fun ByteArray.swapped(bytesPerSample: Int) = ByteArray(size).also { out ->
        for (i in indices step bytesPerSample) for (b in 0 until bytesPerSample) out[i + b] = this[i + bytesPerSample - 1 - b]
    }

    private fun extract(file: ByteArray, maxRead: Int = Int.MAX_VALUE): Result {
        val extractor = AiffExtractor()
        val output = FakeOutput()
        assertTrue(extractor.sniff(input(file)))
        extractor.init(output)
        val seeks = extractor.drain(file, 0, maxRead)
        return Result(checkNotNull(output.track.format), output.track.pcm.toByteArray(), output.track.samples, checkNotNull(output.seekMap), seeks)
    }

    private fun Extractor.drain(file: ByteArray, start: Long, maxRead: Int): Int {
        val holder = PositionHolder()
        var seeks = 0
        var input = input(file, start, maxRead)
        while (true) {
            when (read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    seeks++
                    input = input(file, holder.position, maxRead)
                }
                else -> return seeks
            }
        }
    }

    private fun input(file: ByteArray, position: Long = 0, maxRead: Int = Int.MAX_VALUE): DefaultExtractorInput {
        var next = position.toInt()
        val reader = DataReader { target, offset, length ->
            if (next >= file.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val count = minOf(length, maxRead, file.size - next)
                file.copyInto(target, offset, next, next + count)
                next += count
                count
            }
        }
        return DefaultExtractorInput(reader, position, file.size.toLong())
    }

    private class Result(val format: Format, val pcm: ByteArray, val samples: List<Sample>, val seekMap: SeekMap, val seeks: Int)
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
        val pcm = ByteArrayOutputStream()
        val samples = mutableListOf<Sample>()
        override fun format(format: Format) {
            this.format = format
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("The extractor swaps bytes itself")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pcm.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals(0, offset)
            samples += Sample(timeUs, flags, size)
        }
    }

    private companion object {
        const val FRAMES = 4

        /** Where the audio starts in [aiff]: FORM, COMM and SSND headers. */
        const val DATA_OFFSET = 12L + 8 + 18 + 8 + 8

        /** Stereo samples whose every byte differs, so a wrong order or position shows. */
        fun bigEndian(bits: Int): ByteArray {
            val bytes = bits / 8
            return ByteArray(FRAMES * 2 * bytes) { (it + 1).toByte() }
        }

        fun littleEndian(bits: Int): ByteArray {
            val bytes = bits / 8
            val source = bigEndian(bits)
            return ByteArray(source.size) { source[it / bytes * bytes + bytes - 1 - it % bytes] }
        }

        fun aiff(
            bits: Int = 16,
            stored: Int = bits,
            rate: Int = 44_100,
            frames: Int = FRAMES,
            pcm: ByteArray = bigEndian(stored),
            rateBytes: ByteArray? = null,
        ) = form("AIFF", chunk("COMM", comm(bits, rate, frames, rateBytes = rateBytes)), chunk("SSND", ssnd(pcm)))

        fun aifc(type: String, bits: Int = 16) = form(
            "AIFC",
            chunk("FVER", ByteArray(4)),
            chunk("COMM", comm(bits, 44_100, FRAMES) + type.toByteArray() + byteArrayOf(0, 0)),
            chunk("SSND", ssnd(bigEndian(bits))),
        )

        fun comm(bits: Int, rate: Int, frames: Int, channels: Int = 2, rateBytes: ByteArray? = null): ByteArray {
            val exponent = 31 - Integer.numberOfLeadingZeros(rate)
            return ByteBuffer.allocate(18).putShort(channels.toShort()).putInt(frames).putShort(bits.toShort()).apply {
                if (rateBytes != null) {
                    put(rateBytes)
                } else {
                    putShort((16383 + exponent).toShort()).putLong(rate.toLong() shl (63 - exponent))
                }
            }.array()
        }

        fun ssnd(pcm: ByteArray, offset: Int = 0) =
            ByteBuffer.allocate(8 + offset + pcm.size).putInt(offset).putInt(0).put(ByteArray(offset)).put(pcm).array()

        fun chunk(id: String, body: ByteArray) =
            id.toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + body + ByteArray(body.size % 2)

        fun form(type: String, vararg chunks: ByteArray): ByteArray {
            val body = type.toByteArray() + chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
            return "FORM".toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + body
        }
    }
}
