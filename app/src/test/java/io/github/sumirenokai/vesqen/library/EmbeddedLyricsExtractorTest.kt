package io.github.sumirenokai.vesqen.library

import io.github.sumirenokai.vesqen.lyrics.LyricLine
import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.MAX_LYRICS_TEXT_BYTES
import io.github.sumirenokai.vesqen.lyrics.SourceLyrics
import io.github.sumirenokai.vesqen.lyrics.UnreadableReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class EmbeddedLyricsExtractorTest {
    @Test
    fun usltIsReadInEveryId3TextEncoding() {
        assertEquals(syncedLyrics, extract(id3(3, frame3("USLT", uslt(0, LRC.toByteArray(Charsets.ISO_8859_1))))))
        assertEquals(syncedLyrics, extract(id3(3, frame3("USLT", uslt(1, utf16Le(LRC), descriptor = utf16Le("A"))))))
        assertEquals(syncedLyrics, extract(id3(3, frame3("USLT", uslt(2, LRC.toByteArray(Charsets.UTF_16BE), descriptor = "A".toByteArray(Charsets.UTF_16BE))))))
        assertEquals(syncedLyrics, extract(id3(4, frame4("USLT", uslt(3, LRC.toByteArray(Charsets.UTF_8), descriptor = "desc".toByteArray())))))
    }

    @Test
    fun latinLabelledUsltTriesChineseAndJapaneseBeforeWesternEuropean() {
        val chinese = "晚风吹过安静的街道，我们沿着河岸慢慢走"
        val western = "Le café d\u2019été"

        assertEquals(readable(chinese), extract(id3(3, frame3("USLT", uslt(0, chinese.toByteArray(Charset.forName("GBK")))))))
        assertEquals(readable(western), extract(id3(3, frame3("USLT", uslt(0, western.toByteArray(Charset.forName("windows-1252")))))))
    }

    @Test
    fun utf16DescriptorEndingInAsciiDoesNotShiftTheText() {
        // "A" in UTF-16LE is 41 00; a one-byte terminator scan would stop inside it.
        val frame = uslt(1, utf16Le("Text"), descriptor = utf16Le("A"))

        assertEquals(readable("Text"), extract(id3(3, frame3("USLT", frame))))
    }

    @Test
    fun id3v22UltAndUnsynchronisedTagsAreRead() {
        assertEquals(syncedLyrics, extract(id3v22(frame2("ULT", uslt(0, LRC.toByteArray())))))
        val withFf = "ÿà bientôt"

        assertEquals(readable(withFf), extract(unsynchronise(id3(3, frame3("USLT", uslt(0, withFf.toByteArray(Charsets.ISO_8859_1)))))))
    }

    @Test
    fun id3v24UnsynchronisationIsUndoneFrameByFrame() {
        // 0xFF bytes in a picture and in the UTF-16 mark grow both frames; v2.4 counts the grown size.
        val picture = unsynchronised(byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0, 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val lyrics = unsynchronised(uslt(1, utf16Le(LRC), descriptor = utf16Le("A")))
        val title = frame4("TIT2", byteArrayOf(3) + "Title".toByteArray())

        assertEquals(syncedLyrics, extract(id3Tag(4, frame4("APIC", picture, 0x02) + frame4("USLT", lyrics, 0x02) + title, flags = 0x80)))
        // Only the frame says it is unsynchronised, with and without a leading data length.
        assertEquals(syncedLyrics, extract(id3Tag(4, frame4("USLT", lyrics, 0x02) + title)))
        val dataLength = ByteArrayOutputStream().apply { writeSynchsafeInt(uslt(1, utf16Le(LRC), descriptor = utf16Le("A")).size) }.toByteArray()
        assertEquals(syncedLyrics, extract(id3Tag(4, frame4("USLT", dataLength + lyrics, 0x03) + title)))
    }

    @Test
    fun flacAfterAnId3TagWithoutLyricsIsStillRead() {
        val flac = "fLaC".toByteArray() + flacBlock(4, vorbisComments("LYRICS=$LRC"), last = true) + ByteArray(16)
        val title = frame3("TIT2", byteArrayOf(3) + "Title".toByteArray())

        assertEquals(syncedLyrics, extract(id3(3, title) + flac))
        val footer = ByteArrayOutputStream().apply {
            write("3DI".toByteArray())
            write(byteArrayOf(4, 0, 0x10))
            writeSynchsafeInt(frame4("TIT2", byteArrayOf(3) + "Title".toByteArray()).size)
        }.toByteArray()
        assertEquals(syncedLyrics, extract(id3Tag(4, frame4("TIT2", byteArrayOf(3) + "Title".toByteArray()), flags = 0x10) + footer + flac))
        // An empty or undecodable USLT does not hide the FLAC comments.
        assertEquals(syncedLyrics, extract(id3(3, frame3("USLT", uslt(3, ByteArray(0)))) + flac))
        assertEquals(syncedLyrics, extract(id3(3, frame3("USLT", uslt(1, LRC.toByteArray(Charsets.UTF_16LE)))) + flac))
        // Readable lyrics in the ID3 tag are used as they are; the FLAC stream after it is not read.
        assertEquals(readable("From the ID3 tag"), extract(id3(3, frame3("USLT", uslt(3, "From the ID3 tag".toByteArray()))) + flac))
    }

    @Test
    fun picturesAndOtherFramesAreSkippedAndSyncedLyricsArePreferred() {
        val picture = frame3("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + ByteArray(4_000))
        val title = frame3("TIT2", byteArrayOf(3) + "Title".toByteArray())
        val plain = frame3("USLT", uslt(3, "Plain words".toByteArray()))
        val synced = frame3("USLT", uslt(3, LRC.toByteArray()))
        val secondPlain = frame3("USLT", uslt(3, "Other words".toByteArray()))

        assertEquals(syncedLyrics, extract(id3(3, picture, title, plain, synced)))
        assertEquals(readable("Plain words"), extract(id3(3, picture, plain, secondPlain)))
    }

    @Test
    fun wavAndAiffId3ChunksAreRead() {
        val tag = id3(3, frame3("USLT", uslt(3, LRC.toByteArray())))

        assertEquals(syncedLyrics, extract(riff(chunk("fmt ", ByteArray(16), littleEndian = true) + chunk("ID3 ", tag, littleEndian = true))))
        assertEquals(syncedLyrics, extract(aiff(chunk("COMM", ByteArray(18), littleEndian = false) + chunk("ID3 ", tag, littleEndian = false))))
    }

    @Test
    fun flacCommentKeysMatchInAnyCase() {
        val comments = vorbisComments("Lyrics=$LRC", "TITLE=Song")
        val flac = "fLaC".toByteArray() +
            flacBlock(0, ByteArray(34), last = false) +
            flacBlock(6, ByteArray(64), last = false) +
            flacBlock(4, comments, last = true) +
            ByteArray(32)

        assertEquals(syncedLyrics, extract(flac))
        val unsynced = "fLaC".toByteArray() + flacBlock(4, vorbisComments("unsyncedlyrics=Plain words"), last = true)
        assertEquals(readable("Plain words"), extract(unsynced))
    }

    @Test
    fun oggVorbisAndOpusCommentsAreReadAcrossPages() {
        val vorbisHeader = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
        val vorbisComment = byteArrayOf(3) + "vorbis".toByteArray() + vorbisComments("LYRICS=$LRC") + byteArrayOf(1)

        assertEquals(syncedLyrics, extract(ogg(listOf(vorbisHeader, vorbisComment))))

        // A large picture comment before the lyrics spans several pages of the same packet.
        val opusTags = "OpusTags".toByteArray() +
            vorbisComments("METADATA_BLOCK_PICTURE=" + "A".repeat(70_000), "LYRICS=$LRC")
        assertEquals(syncedLyrics, extract(ogg(listOf("OpusHead".toByteArray() + ByteArray(11), opusTags), segmentsPerPage = 7)))
    }

    @Test
    fun mp4LyricsAtomIsReadAsUtf8OrUtf16() {
        assertEquals(syncedLyrics, extract(mp4(lyricsData(type = 1, LRC.toByteArray(Charsets.UTF_8)))))
        assertEquals(syncedLyrics, extract(mp4(lyricsData(type = 2, LRC.toByteArray(Charsets.UTF_16BE)))))
        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract(mp4(lyricsData(type = 13, LRC.toByteArray()))))
        val audioBeforeMetadata = mp4(lyricsData(type = 1, LRC.toByteArray()), beforeMoov = atom("mdat", ByteArray(5_000)))
        assertEquals(syncedLyrics, extract(audioBeforeMetadata))
    }

    @Test
    fun oversizedOrUndecodableLyricsAreUnreadable() {
        val oversized = "a".repeat(MAX_LYRICS_TEXT_BYTES + 10).toByteArray()

        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract(id3(3, frame3("USLT", uslt(3, oversized)))))
        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract("fLaC".toByteArray() + flacBlock(4, vorbisComments("LYRICS=" + String(oversized)), last = true)))
        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract(mp4(lyricsData(type = 1, oversized))))
        // Encoding 1 without its byte order mark, and a compressed frame.
        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract(id3(3, frame3("USLT", uslt(1, LRC.toByteArray(Charsets.UTF_16LE), descriptor = ByteArray(0))))))
        assertEquals(SourceLyrics.Unreadable(UnreadableReason.Encoding), extract(id3(3, frame3("USLT", uslt(3, LRC.toByteArray()), formatFlags = 0x80))))
        // A readable frame after an unreadable one still wins.
        assertEquals(
            syncedLyrics,
            extract(id3(3, frame3("USLT", uslt(3, byteArrayOf(0xC3.toByte(), 0x28))), frame3("USLT", uslt(3, LRC.toByteArray())))),
        )
    }

    @Test
    fun missingLyricsAndMalformedContainersAreAbsent() {
        assertEquals(SourceLyrics.Absent, extract(byteArrayOf()))
        assertEquals(SourceLyrics.Absent, extract("not audio data".toByteArray()))
        assertEquals(SourceLyrics.Absent, extract(id3(3, frame3("TIT2", byteArrayOf(3) + "Title".toByteArray()))))
        assertEquals(SourceLyrics.Absent, extract(id3(3, frame3("USLT", uslt(3, "[ti:Title only]".toByteArray())))))
        assertEquals(SourceLyrics.Absent, extract("fLaC".toByteArray() + flacBlock(4, vorbisComments("TITLE=Song"), last = true)))
        val truncated = id3(3, frame3("USLT", uslt(3, LRC.toByteArray())))
        assertEquals(SourceLyrics.Absent, extract(truncated.copyOf(truncated.size - 5)))
    }

    private fun extract(bytes: ByteArray): SourceLyrics =
        extractBoundedEmbeddedLyrics(ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun readable(plainText: String) = SourceLyrics.Readable(Lyrics.Plain(listOf(plainText)))

    private fun uslt(encoding: Int, text: ByteArray, descriptor: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().apply {
            write(encoding)
            write("eng".toByteArray())
            write(descriptor)
            write(if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0))
            write(text)
        }.toByteArray()

    private fun utf16Le(text: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun frame3(id: String, payload: ByteArray, formatFlags: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        writeBigEndianInt(payload.size)
        write(0)
        write(formatFlags)
        write(payload)
    }.toByteArray()

    private fun frame4(id: String, payload: ByteArray, formatFlags: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        writeSynchsafeInt(payload.size)
        write(byteArrayOf(0, formatFlags.toByte()))
        write(payload)
    }.toByteArray()

    /** ID3 unsynchronisation of one frame's bytes: a zero after every 0xFF that a 0x00 or 0xE0+ follows. */
    private fun unsynchronised(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        bytes.forEachIndexed { index, byte ->
            write(byte.toInt())
            val next = bytes.getOrNull(index + 1)?.toInt()?.and(0xff)
            if ((byte.toInt() and 0xff) == 0xff && (next == null || next == 0 || next >= 0xe0)) write(0)
        }
    }.toByteArray()

    private fun frame2(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        write((payload.size ushr 16) and 0xff)
        write((payload.size ushr 8) and 0xff)
        write(payload.size and 0xff)
        write(payload)
    }.toByteArray()

    private fun id3(majorVersion: Int, vararg frames: ByteArray): ByteArray = id3Tag(majorVersion, frames.reduce { joined, frame -> joined + frame })

    private fun id3v22(vararg frames: ByteArray): ByteArray = id3Tag(2, frames.reduce { joined, frame -> joined + frame })

    private fun id3Tag(majorVersion: Int, body: ByteArray, flags: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write("ID3".toByteArray())
        write(byteArrayOf(majorVersion.toByte(), 0, flags.toByte()))
        writeSynchsafeInt(body.size)
        write(body)
    }.toByteArray()

    private fun unsynchronise(tag: ByteArray): ByteArray {
        val payload = tag.copyOfRange(10, tag.size)
        val encodedPayload = ByteArrayOutputStream().apply {
            payload.forEachIndexed { index, byte ->
                write(byte.toInt())
                val next = payload.getOrNull(index + 1)?.toInt()?.and(0xff)
                if ((byte.toInt() and 0xff) == 0xff && (next == 0 || next != null && next >= 0xe0)) write(0)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(tag, 0, 5)
            write(0x80)
            writeSynchsafeInt(encodedPayload.size)
            write(encodedPayload)
        }.toByteArray()
    }

    private fun riff(chunks: ByteArray): ByteArray {
        val payload = "WAVE".toByteArray() + chunks
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLittleEndianInt(payload.size)
            write(payload)
        }.toByteArray()
    }

    private fun aiff(chunks: ByteArray): ByteArray {
        val payload = "AIFF".toByteArray() + chunks
        return ByteArrayOutputStream().apply {
            write("FORM".toByteArray())
            writeBigEndianInt(payload.size)
            write(payload)
        }.toByteArray()
    }

    private fun chunk(type: String, payload: ByteArray, littleEndian: Boolean): ByteArray =
        ByteArrayOutputStream().apply {
            write(type.toByteArray())
            if (littleEndian) writeLittleEndianInt(payload.size) else writeBigEndianInt(payload.size)
            write(payload)
            if ((payload.size and 1) != 0) write(0)
        }.toByteArray()

    private fun flacBlock(type: Int, payload: ByteArray, last: Boolean): ByteArray = ByteArrayOutputStream().apply {
        write(type or if (last) 0x80 else 0)
        write((payload.size ushr 16) and 0xff)
        write((payload.size ushr 8) and 0xff)
        write(payload.size and 0xff)
        write(payload)
    }.toByteArray()

    private fun vorbisComments(vararg comments: String): ByteArray = ByteArrayOutputStream().apply {
        val vendor = "Vesqen test".toByteArray()
        writeLittleEndianInt(vendor.size)
        write(vendor)
        writeLittleEndianInt(comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(Charsets.UTF_8)
            writeLittleEndianInt(bytes.size)
            write(bytes)
        }
    }.toByteArray()

    /** Lays packets out in 255-byte lacing segments, [segmentsPerPage] segments to a page. */
    private fun ogg(packets: List<ByteArray>, segmentsPerPage: Int = 255): ByteArray {
        val segments = packets.flatMap { packet ->
            val pieces = (0..packet.size / 255).map { index ->
                packet.copyOfRange(index * 255, minOf(packet.size, (index + 1) * 255))
            }
            pieces
        }
        return ByteArrayOutputStream().apply {
            segments.chunked(segmentsPerPage).forEach { page ->
                val header = ByteArray(27)
                "OggS".toByteArray().copyInto(header)
                header[26] = page.size.toByte()
                write(header)
                page.forEach { write(it.size) }
                page.forEach(::write)
            }
        }.toByteArray()
    }

    private fun lyricsData(type: Int, text: ByteArray): ByteArray =
        atom("data", ByteArrayOutputStream().apply { writeBigEndianInt(type) }.toByteArray() + ByteArray(4) + text)

    private fun mp4(data: ByteArray, beforeMoov: ByteArray = ByteArray(0)): ByteArray {
        val lyrics = atom("\u00A9lyr", data)
        val hierarchy = atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst", atom("\u00A9nam", ByteArray(12)) + lyrics))))
        return atom("ftyp", "M4A ".toByteArray()) + beforeMoov + hierarchy
    }

    private fun atom(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeBigEndianInt(payload.size + 8)
        write(type.toByteArray(Charsets.ISO_8859_1))
        write(payload)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeSynchsafeInt(value: Int) {
        write((value ushr 21) and 0x7f)
        write((value ushr 14) and 0x7f)
        write((value ushr 7) and 0x7f)
        write(value and 0x7f)
    }

    private fun ByteArrayOutputStream.writeBigEndianInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private companion object {
        const val LRC = "[00:01.00]Morning light\n[00:04.50]Over the hill"
        val syncedLyrics = SourceLyrics.Readable(
            Lyrics.Synced(listOf(LyricLine(1_000, "Morning light"), LyricLine(4_500, "Over the hill"))),
        )
    }
}
