package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class EmbeddedArtworkExtractorTest {
    private val picture = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte())

    @Test
    fun extractsId3ArtworkFromMp3AndChunkedWavAiff() {
        val id3 = id3WithPicture(picture)
        assertArrayEquals(picture, extract(id3))
        assertArrayEquals(picture, extract(id3V22WithPicture(picture)))
        assertArrayEquals(picture, extract(id3V23WithExtendedHeader(picture)))
        val statusFlaggedId3 = id3.copyOf().apply { this[18] = 0x20 }
        assertArrayEquals(picture, extract(statusFlaggedId3))
        val unsynchronisedPicture = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0x01, 0xff.toByte(), 0x00)
        assertArrayEquals(unsynchronisedPicture, extract(unsynchroniseTag(id3WithPicture(unsynchronisedPicture))))
        assertArrayEquals(picture, extract(riffWaveWithId3(id3)))
        assertArrayEquals(picture, extract(aiffWithId3(id3)))
    }

    @Test
    fun extractsMp4CoverAtomForM4aAlacAndAacContainers() {
        assertArrayEquals(picture, extract(mp4WithCover(picture)))
        val emptyMetadataBeforeCover = atom(
            "moov",
            atom("udta", atom("meta", ByteArray(4) + atom("ilst", ByteArray(0)))) +
                atom("udta", atom("meta", ByteArray(4) + atom("ilst", atom("covr", atom("data", ByteArray(8) + picture))))),
        )
        assertArrayEquals(picture, extract(atom("ftyp", "M4A ".toByteArray()) + emptyMetadataBeforeCover))
    }

    @Test
    fun extractsVorbisStyleMetadataPictureFromOggOpusComments() {
        assertArrayEquals(picture, extract(oggOpusWithPicture(picture)))
    }

    @Test
    fun malformedContainersFailClosed() {
        assertNull(extract(byteArrayOf()))
        assertNull(extract("not audio data".toByteArray()))
        val oversizedMp4 = atom("data", ByteArray(8)).copyOf().apply {
            this[0] = 0x7f
            this[1] = 0xff.toByte()
            this[2] = 0xff.toByte()
            this[3] = 0xff.toByte()
        }
        assertNull(extract(atom("moov", atom("covr", oversizedMp4))))
    }

    private fun extract(bytes: ByteArray): ByteArray? = extractBoundedEmbeddedArtwork(
        input = ByteArrayInputStream(bytes),
        declaredLength = bytes.size.toLong(),
    )

    private fun id3WithPicture(image: ByteArray): ByteArray {
        val apic = ByteArrayOutputStream().apply {
            write(0)
            write("image/jpeg".toByteArray())
            write(0)
            write(3)
            write(0)
            write(image)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("APIC".toByteArray())
            writeBigEndianInt(apic.size)
            write(byteArrayOf(0, 0))
            write(apic)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(3, 0, 0))
            writeSynchsafeInt(frame.size)
            write(frame)
        }.toByteArray()
    }

    private fun id3V22WithPicture(image: ByteArray): ByteArray {
        val pictureFrame = ByteArrayOutputStream().apply {
            write(0)
            write("JPG".toByteArray())
            write(3)
            write(0)
            write(image)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("PIC".toByteArray())
            write((pictureFrame.size ushr 16) and 0xff)
            write((pictureFrame.size ushr 8) and 0xff)
            write(pictureFrame.size and 0xff)
            write(pictureFrame)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(2, 0, 0))
            writeSynchsafeInt(frame.size)
            write(frame)
        }.toByteArray()
    }

    private fun id3V23WithExtendedHeader(image: ByteArray): ByteArray {
        val ordinaryTag = id3WithPicture(image)
        val frame = ordinaryTag.copyOfRange(10, ordinaryTag.size)
        val extendedHeader = ByteArrayOutputStream().apply {
            writeBigEndianInt(6)
            write(ByteArray(6))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(3, 0, 0x40))
            writeSynchsafeInt(extendedHeader.size + frame.size)
            write(extendedHeader)
            write(frame)
        }.toByteArray()
    }

    private fun unsynchroniseTag(tag: ByteArray): ByteArray {
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

    private fun riffWaveWithId3(id3: ByteArray): ByteArray {
        val chunk = chunk("ID3 ", id3, littleEndian = true)
        val payload = "WAVE".toByteArray() + chunk
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLittleEndianInt(payload.size)
            write(payload)
        }.toByteArray()
    }

    private fun aiffWithId3(id3: ByteArray): ByteArray {
        val chunk = chunk("ID3 ", id3, littleEndian = false)
        val payload = "AIFF".toByteArray() + chunk
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

    private fun mp4WithCover(image: ByteArray): ByteArray {
        val data = atom("data", ByteArray(8) + image)
        val hierarchy = atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst", atom("covr", data)))))
        return atom("ftyp", "M4A ".toByteArray()) + hierarchy
    }

    private fun atom(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeBigEndianInt(payload.size + 8)
        write(type.toByteArray())
        write(payload)
    }.toByteArray()

    private fun oggOpusWithPicture(image: ByteArray): ByteArray {
        val block = flacPictureBlock(image)
        val comment = "METADATA_BLOCK_PICTURE=".toByteArray() + Base64.getEncoder().encode(block)
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            writeLittleEndianInt(0)
            writeLittleEndianInt(1)
            writeLittleEndianInt(comment.size)
            write(comment)
        }.toByteArray()
        return oggPage(listOf("OpusHead".toByteArray(), tags))
    }

    private fun oggPage(packets: List<ByteArray>): ByteArray {
        require(packets.all { it.size < 255 })
        val header = ByteArray(27).apply {
            "OggS".toByteArray().copyInto(this)
            this[4] = 0
            this[5] = 2
            this[26] = packets.size.toByte()
        }
        return ByteArrayOutputStream().apply {
            write(header)
            packets.forEach { write(it.size) }
            packets.forEach(::write)
        }.toByteArray()
    }

    private fun flacPictureBlock(image: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val mime = "image/jpeg".toByteArray()
        writeBigEndianInt(3)
        writeBigEndianInt(mime.size)
        write(mime)
        writeBigEndianInt(0)
        writeBigEndianInt(720)
        writeBigEndianInt(720)
        writeBigEndianInt(24)
        writeBigEndianInt(0)
        writeBigEndianInt(image.size)
        write(image)
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
}
