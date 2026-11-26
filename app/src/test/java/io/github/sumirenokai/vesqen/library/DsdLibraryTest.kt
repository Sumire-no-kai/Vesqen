package io.github.sumirenokai.vesqen.library

import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.SourceLyrics
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #249: what the catalog reads from DSF and DFF files, which Android itself cannot open. */
class DsdLibraryTest {
    @Test
    fun `folder scans keep DSF and DFF as tracks and name their format DSD`() {
        for ((mime, name) in listOf("application/octet-stream" to "album.DSF", null to "album.dff", "" to "a.dsf")) {
            assertTrue(name, isSupportedAudioDocument(mime, name))
            assertNull(name, knownUnsupportedAudioFormat(mime, name))
        }
        assertEquals("DSD", m1FormatFor("application/octet-stream", "album.dsf")?.displayName)
        assertEquals(DSD_CODEC_LABEL, codecLabel("audio/x-dff", "album.dff"))
        assertEquals(DSD_CODEC_LABEL, codecLabel("", "album.DSF"))
    }

    @Test
    fun `a DSD rate is named by its multiple of either base rate`() {
        assertEquals("DSD64", dsdRateLabel(2_822_400))
        assertEquals("DSD256", dsdRateLabel(11_289_600))
        assertEquals("DSD64", dsdRateLabel(3_072_000))
        assertEquals("DSD512", dsdRateLabel(24_576_000))
        assertEquals("DSD", dsdRateLabel(null))
        assertEquals("DSD", dsdRateLabel(0))
    }

    @Test
    fun `the source header gives the DSD rate, one bit and the duration`() {
        // 0.5 s of DSD64 in both containers.
        val expected = SourceAudioHeader(sampleRate = 2_822_400, channels = 2, bitDepth = 1, durationMs = 500)
        assertEquals(expected, readSourceAudioHeader(dsf(tag()).inputStream()))
        assertEquals(expected, readSourceAudioHeader(dff(tag()).inputStream()))
        assertEquals(expected.copy(sampleRate = 5_644_800), readSourceAudioHeader(dsf(tag(), rate = 5_644_800).inputStream()))
    }

    @Test
    fun `the ID3 tag of either container fills the catalog's own tags`() {
        val expected = BasicTrackTags(
            title = "晨光", artist = "Ensemble", album = "Morning", albumArtist = "Various",
            trackNumber = 3, discNumber = 1, year = 2019, genre = "Jazz",
        )
        for (file in listOf(dsf(tag()), dff(tag()))) {
            assertEquals(expected, extractDsdBasicTags(file.inputStream(), file.size.toLong()))
        }
    }

    @Test
    fun `track details name the container and keep the totals`() {
        val dsf = dsf(tag())
        val dff = dff(tag())
        val dsfTags = extractBoundedTrackTags(dsf.inputStream(), dsf.size.toLong())
        assertEquals("DSF", dsfTags.container)
        assertEquals(12, dsfTags.trackTotal)
        assertEquals(2, dsfTags.discTotal)
        assertEquals("DSDIFF", extractBoundedTrackTags(dff.inputStream(), dff.size.toLong()).container)
    }

    @Test
    fun `artwork and lyrics come from the DSD file's ID3 tag`() {
        for (file in listOf(dsf(tag()), dff(tag()))) {
            assertArrayEquals(PICTURE, extractBoundedEmbeddedArtwork(file.inputStream(), file.size.toLong()))
            assertEquals(
                SourceLyrics.Readable(Lyrics.Plain(listOf("Morning light"))),
                extractBoundedEmbeddedLyrics(file.inputStream(), file.size.toLong()),
            )
        }
    }

    @Test
    fun `a DSD file without a usable tag reads as untagged`() {
        val untagged = listOf(dsf(tag = null), dff(tag = null))
        // A DSF whose pointer runs past the end of the file.
        val pastTheEnd = dsf(tag()).let { file -> file.copyOf(file.size - tag().size) }
        for (file in untagged + listOf(pastTheEnd)) {
            assertEquals(BasicTrackTags(), extractDsdBasicTags(file.inputStream(), file.size.toLong()))
            assertNull(extractBoundedEmbeddedArtwork(file.inputStream(), file.size.toLong()))
            assertEquals(SourceLyrics.Absent, extractBoundedEmbeddedLyrics(file.inputStream(), file.size.toLong()))
        }
        assertEquals("DSF", extractBoundedTrackTags(untagged[0].inputStream(), untagged[0].size.toLong()).container)
    }

    /** A chunk claiming more than the file is refused before any of it is skipped. */
    @Test
    fun `a DFF chunk larger than the file is refused without skipping it`() {
        val plain = dff(tag = null)
        val propEnd = 16 + 16 + 12 + ByteBuffer.wrap(plain, 16 + 16 + 4, 8).long.toInt()
        val oversized = ByteArray(12).also { header ->
            "COMT".toByteArray().copyInto(header)
            ByteBuffer.wrap(header, 4, 8).putLong(plain.size * 4L)
        }
        val broken = plain.copyOf(propEnd) + oversized + plain.copyOfRange(propEnd, plain.size)
        val skips = mutableListOf<Long>()
        val source = object : DsdHeaderSource {
            private val file = InputStreamDsdHeaderSource(broken.inputStream())
            override fun read(count: Int) = file.read(count)
            override fun skip(count: Long) = file.skip(count).also { skips += count }
        }
        assertEquals(DsdHeaderResult.Unreadable, readDsdHeader(source, broken.size.toLong()))
        assertTrue("skipped $skips", skips.none { it > broken.size })
    }

    /** With the file's length the catalog sees what playback plays: the whole blocks of a cut file. */
    @Test
    fun `the header of a cut file gives the duration it plays`() {
        val whole = dsf(tag = null)
        val cut = whole.copyOf(92 + 4_096 * 2 * 10 + 100)
        assertEquals(500L, readSourceAudioHeader(cut.inputStream())?.durationMs)
        val tenBlocks = 4_096L * 8 * 10 * 1_000 / 2_822_400
        assertEquals(tenBlocks, readSourceAudioHeader(cut.inputStream(), cut.size.toLong())?.durationMs)
    }

    /** A tag with its cover before the audio is larger than any header read; the header is still found. */
    @Test
    fun `a DFF with its tag before the audio still gives its header and tags`() {
        val cover = PICTURE + ByteArray(200_000)
        val file = dff(tag(picture = cover), tagFirst = true)
        assertEquals(
            SourceAudioHeader(sampleRate = 2_822_400, channels = 2, bitDepth = 1, durationMs = 500),
            readSourceAudioHeader(file.inputStream()),
        )
        assertEquals("晨光", extractDsdBasicTags(file.inputStream(), file.size.toLong()).title)
        assertArrayEquals(cover, extractBoundedEmbeddedArtwork(file.inputStream(), file.size.toLong()))
    }

    @Test
    fun `a DFF tag chunk is found whatever the case of its ID`() {
        val file = dff(tag(), tagId = "id3 ")
        assertEquals("晨光", extractDsdBasicTags(file.inputStream(), file.size.toLong()).title)
    }

    /** ID3v2.3 lets a genre name follow its ID3v1 number, as in "(17)Rock"; ID3v2.4 writes the number bare. */
    @Test
    fun `an ID3v1 genre number before the name is dropped`() {
        for ((genre, expected) in listOf("(17)Rock" to "Rock", "(13)" to null, "17" to null, "Jazz" to "Jazz", "(RX)" to "(RX)")) {
            val file = dsf(id3(frame("TCON", latin(genre))))
            assertEquals(genre, expected, extractDsdBasicTags(file.inputStream(), file.size.toLong()).genre)
        }
    }

    /** The ID3 decoder trims values and drops blank ones, so the catalog keeps its file-name fallback. */
    @Test
    fun `blank tag values count as missing`() {
        val blank = ByteArrayOutputStream().apply {
            write(frame("TIT2", latin("  ")))
            write(frame("TPE1", byteArrayOf(1, 0xff.toByte(), 0xfe.toByte())))
            write(frame("TALB", latin(" Morning ")))
        }.toByteArray()
        val file = dsf(id3(blank))
        assertEquals(BasicTrackTags(album = "Morning"), extractDsdBasicTags(file.inputStream(), file.size.toLong()))
    }

    private companion object {
        val PICTURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte())

        /** Half a second of stereo DSD at [rate], all channels idle. */
        fun samples(rate: Int) = rate / 2L

        /** An ID3v2.3 tag with the catalog's tags, a cover and plain lyrics. */
        fun tag(picture: ByteArray = PICTURE): ByteArray {
            val frames = ByteArrayOutputStream().apply {
                write(frame("TIT2", byteArrayOf(1) + utf16("晨光")))
                write(frame("TPE1", latin("Ensemble")))
                write(frame("TALB", latin("Morning")))
                write(frame("TPE2", latin("Various")))
                write(frame("TRCK", latin("3/12")))
                write(frame("TPOS", latin("1/2")))
                write(frame("TYER", latin("2019")))
                write(frame("TCON", latin("Jazz")))
                write(frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + picture))
                write(frame("USLT", byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + "Morning light".toByteArray()))
            }.toByteArray()
            return id3(frames)
        }

        fun id3(frames: ByteArray): ByteArray {
            val size = frames.size
            val header = byteArrayOf(
                'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
                ((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(),
                ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte(),
            )
            return header + frames
        }

        fun latin(text: String) = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)

        fun utf16(text: String) = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)

        fun frame(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.US_ASCII))
            for (shift in intArrayOf(24, 16, 8, 0)) write((payload.size shr shift) and 0xff)
            write(0)
            write(0)
            write(payload)
        }.toByteArray()

        /** A DSF whose header points to [tag] after the audio, or holds no pointer without one. */
        fun dsf(tag: ByteArray?, rate: Int = 2_822_400): ByteArray {
            val samples = samples(rate)
            val block = 4_096L
            val blocks = ((samples / 8) + block - 1) / block
            val data = ByteArray((blocks * block * 2).toInt()) { 0x69 }
            val dataStart = 92L
            val metadata = if (tag == null) 0L else dataStart + data.size
            val total = dataStart + data.size + (tag?.size ?: 0)
            val header = Le().ascii("DSD ").u64(28).u64(total).u64(metadata)
                .ascii("fmt ").u64(52).u32(1).u32(0).u32(2).u32(2).u32(rate.toLong()).u32(1).u64(samples).u32(block).u32(0)
                .ascii("data").u64(12L + data.size)
                .bytes()
            return header + data + (tag ?: ByteArray(0))
        }

        /** A DFF with [tag] in an "ID3 " chunk after the audio as common tools write it, or before it. */
        fun dff(tag: ByteArray?, rate: Int = 2_822_400, tagFirst: Boolean = false, tagId: String = "ID3 "): ByteArray {
            val prop = Be().ascii("SND ")
                .chunk("FS  ", Be().u32(rate.toLong()).bytes())
                .chunk("CHNL", Be().u16(2).ascii("SLFT").ascii("SRGT").bytes())
                .chunk("CMPR", Be().ascii("DSD ").u8(0).u8(0).bytes())
                .bytes()
            val form = Be().ascii("DSD ")
                .chunk("FVER", Be().u32(0x01050000).bytes())
                .chunk("PROP", prop)
                .also { if (tag != null && tagFirst) it.chunk(tagId, tag) }
                .chunk("DSD ", ByteArray((samples(rate) / 8 * 2).toInt()) { 0x69 })
                .also { if (tag != null && !tagFirst) it.chunk(tagId, tag) }
                .bytes()
            return Be().ascii("FRM8").u64(form.size.toLong()).raw(form).bytes()
        }
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
