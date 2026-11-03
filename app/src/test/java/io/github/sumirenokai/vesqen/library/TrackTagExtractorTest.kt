package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TrackTagExtractorTest {
    private fun extract(bytes: ByteArray) = extractBoundedTrackTags(bytes.inputStream(), bytes.size.toLong())

    private fun utf8Frame(text: String) = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)

    private fun frame3(id: String, payload: ByteArray) = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(0, 0, (payload.size shr 8).toByte(), payload.size.toByte(), 0, 0))
        write(payload)
    }.toByteArray()

    private fun frame4(id: String, payload: ByteArray) = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(0, 0, (payload.size shr 7 and 0x7f).toByte(), (payload.size and 0x7f).toByte(), 0, 0))
        write(payload)
    }.toByteArray()

    private fun frame2(id: String, payload: ByteArray) = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(0, (payload.size shr 8).toByte(), payload.size.toByte()))
        write(payload)
    }.toByteArray()

    private fun id3(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0), ByteArray::plus)
        val size = body.size
        val header = "ID3".toByteArray() + byteArrayOf(
            version.toByte(), 0, 0,
            (size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte(),
        )
        return header + body
    }

    private fun littleEndian(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun bigEndian(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun comments(vendor: String, vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray()
        out.write(littleEndian(vendorBytes.size))
        out.write(vendorBytes)
        out.write(littleEndian(entries.size))
        entries.forEach { entry ->
            val bytes = entry.toByteArray()
            out.write(littleEndian(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flacBlock(type: Int, last: Boolean, payload: ByteArray) =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (payload.size shr 16).toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    private fun flacPicture(type: Int, mime: String) =
        bigEndian(type) + bigEndian(mime.length) + mime.toByteArray() + ByteArray(32) + ByteArray(2048)

    private fun oggPage(sequence: Int, packet: ByteArray, firstPage: Boolean = false): ByteArray {
        val segments = ArrayList<Byte>()
        var left = packet.size
        while (left >= 255) {
            segments += 255.toByte()
            left -= 255
        }
        segments += left.toByte()
        return "OggS".toByteArray() + byteArrayOf(0, if (firstPage) 2 else 0) + ByteArray(8) + ByteArray(4) +
            littleEndian(sequence) + ByteArray(4) + byteArrayOf(segments.size.toByte()) + segments.toByteArray() + packet
    }

    private fun atom(type: String, payload: ByteArray) = bigEndian(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun dataAtom(type: Int, value: ByteArray) = atom("data", bigEndian(type) + ByteArray(4) + value)

    private fun freeform(name: String, value: String) = atom(
        "----",
        atom("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) +
            atom("name", ByteArray(4) + name.toByteArray()) +
            dataAtom(1, value.toByteArray()),
    )

    private fun mp4(vararg items: ByteArray): ByteArray {
        val ilst = atom("ilst", items.fold(ByteArray(0), ByteArray::plus))
        return atom("ftyp", "M4A ".toByteArray() + ByteArray(4)) +
            atom("mdat", ByteArray(4096)) +
            atom("moov", atom("udta", atom("meta", ByteArray(4) + ilst)))
    }

    @Test
    fun id3v23TextFramesAndReplayGainAreRead() {
        val tags = extract(
            id3(
                3,
                frame3("TCOM", utf8Frame("坂本龍一")),
                frame3("TEXT", byteArrayOf(0) + "Lyric Writer".toByteArray(Charsets.ISO_8859_1)),
                frame3("TSSE", utf8Frame("LAME 3.100")),
                frame3("TSRC", utf8Frame("USRC17607839")),
                frame3("TRCK", utf8Frame("3/12")),
                frame3("TPOS", utf8Frame("1/2")),
                frame3("TXXX", byteArrayOf(3) + "REPLAYGAIN_TRACK_GAIN".toByteArray() + 0 + "-7.12 dB".toByteArray()),
                frame3("TXXX", byteArrayOf(3) + "replaygain_track_peak".toByteArray() + 0 + "0.98".toByteArray()),
                frame3("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + ByteArray(4096)),
            ) + byteArrayOf(0xff.toByte(), 0xfb.toByte()) + ByteArray(64),
        )
        assertEquals("MPEG Audio", tags.container)
        assertEquals("坂本龍一", tags.composer)
        assertEquals("Lyric Writer", tags.lyricist)
        assertEquals("LAME 3.100", tags.encoder)
        assertEquals("USRC17607839", tags.isrc)
        assertEquals(12, tags.trackTotal)
        assertEquals(2, tags.discTotal)
        assertEquals("-7.12 dB", tags.replayGainTrackGain)
        assertEquals("0.98", tags.replayGainTrackPeak)
        assertEquals("image/jpeg", tags.coverMimeType)
        assertEquals(true, tags.coverIsFront)
    }

    @Test
    fun utf16AndSeveralValuesAreRead() {
        val utf16 = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "作曲者".toByteArray(Charsets.UTF_16LE)
        assertEquals("作曲者", extract(id3(3, frame3("TCOM", utf16))).composer)
        val two = byteArrayOf(3) + "Bach".toByteArray() + 0 + "Handel".toByteArray()
        assertEquals("Bach, Handel", extract(id3(4, frame4("TCOM", two))).composer)
    }

    @Test
    fun id3v22FramesAreRead() {
        val tags = extract(
            id3(
                2,
                frame2("TCM", utf8Frame("Composer")),
                frame2("TRK", utf8Frame("1/9")),
                frame2("PIC", byteArrayOf(0) + "PNG".toByteArray() + byteArrayOf(0, 0) + ByteArray(64)),
            ),
        )
        assertEquals("Composer", tags.composer)
        assertEquals(9, tags.trackTotal)
        assertEquals("image/png", tags.coverMimeType)
        assertEquals(false, tags.coverIsFront)
    }

    @Test
    fun aFrontCoverWinsOverAnEarlierPicture() {
        val tags = extract(
            id3(
                3,
                frame3("APIC", byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 4, 0) + ByteArray(64)),
                frame3("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + ByteArray(64)),
            ),
        )
        assertEquals("image/jpeg", tags.coverMimeType)
        assertEquals(true, tags.coverIsFront)
    }

    @Test
    fun flacCommentsVendorAndPictureAreRead() {
        val tags = extract(
            "fLaC".toByteArray() +
                flacBlock(0, false, ByteArray(34)) +
                flacBlock(4, false, comments("reference libFLAC 1.4.3 20230623", "composer=Bach", "LYRICIST=Picander", "ISRC=ABC", "TRACKTOTAL=10", "DISCTOTAL=2", "REPLAYGAIN_ALBUM_GAIN=-5.00 dB", "REPLAYGAIN_ALBUM_PEAK=0.9")) +
                flacBlock(6, true, flacPicture(3, "image/png")) +
                ByteArray(128),
        )
        assertEquals("FLAC", tags.container)
        assertEquals("Bach", tags.composer)
        assertEquals("Picander", tags.lyricist)
        assertEquals("reference libFLAC 1.4.3 20230623", tags.encoder)
        assertEquals("ABC", tags.isrc)
        assertEquals(10, tags.trackTotal)
        assertEquals(2, tags.discTotal)
        assertEquals("-5.00 dB", tags.replayGainAlbumGain)
        assertEquals("image/png", tags.coverMimeType)
    }

    @Test
    fun anEncoderCommentWinsOverTheVendor() {
        val tags = extract("fLaC".toByteArray() + flacBlock(4, true, comments("libFLAC", "ENCODER=dBpoweramp")))
        assertEquals("dBpoweramp", tags.encoder)
    }

    @Test
    fun flacBehindAnId3TagIsStillFlac() {
        val tags = extract(id3(3, frame3("TCOM", utf8Frame("From ID3"))) + "fLaC".toByteArray() + flacBlock(4, true, comments("libFLAC", "LYRICIST=From comments")))
        assertEquals("FLAC", tags.container)
        assertEquals("From ID3", tags.composer)
        assertEquals("From comments", tags.lyricist)
    }

    @Test
    fun theAudioAfterAnId3TagNamesTheContainer() {
        val tag = id3(3, frame3("TIT2", utf8Frame("Title")))
        val audio = ByteArray(32)
        fun after(first: Int, second: Int) =
            extract(tag + byteArrayOf(first.toByte(), second.toByte()) + audio).container

        assertEquals("ADTS", after(0xff, 0xf1))
        assertEquals("MPEG Audio", after(0xff, 0xfb))
        assertEquals("What follows is not known audio", null, after(0x12, 0x34))
    }

    @Test
    fun repeatedCommentCreditsAreAllKept() {
        val tags = extract(
            "fLaC".toByteArray() + flacBlock(
                4,
                true,
                comments(
                    "vendor",
                    "COMPOSER=Bach", "composer=Handel", "COMPOSER=Bach",
                    "LYRICIST=Picander", "LYRICIST=Henrici",
                    "ISRC=AAAAA0000001", "ISRC=BBBBB0000002",
                ),
            ) + ByteArray(128),
        )
        assertEquals("Bach, Handel", tags.composer)
        assertEquals("Picander, Henrici", tags.lyricist)
        assertEquals("A track has one code, so the first stays", "AAAAA0000001", tags.isrc)
    }

    @Test
    fun repeatedCreditsStayWithinTheTextLimit() {
        val entries = List(2_000) { "COMPOSER=${it.toString().padEnd(100, 'x')}" }.toTypedArray()
        val tags = extract("fLaC".toByteArray() + flacBlock(4, true, comments("vendor", *entries)) + ByteArray(128))
        assertTrue(tags.composer!!.length <= 8 * 1024)
        assertTrue(tags.composer!!.startsWith("0"))
    }

    @Test
    fun oggVorbisAndOpusCommentsAndPictureAreRead() {
        val picture = Base64.getEncoder().encodeToString(flacPicture(3, "image/jpeg"))
        val vorbis = byteArrayOf(3) + "vorbis".toByteArray() + comments("Xiph", "COMPOSER=Bach", "METADATA_BLOCK_PICTURE=$picture", "TRACKNUMBER=2/8")
        val tags = extract(oggPage(0, byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23), firstPage = true) + oggPage(1, vorbis))
        assertEquals("Ogg", tags.container)
        assertEquals("Bach", tags.composer)
        assertEquals("Xiph", tags.encoder)
        assertEquals(8, tags.trackTotal)
        assertEquals("image/jpeg", tags.coverMimeType)
        assertEquals(true, tags.coverIsFront)

        val opus = "OpusTags".toByteArray() + comments("libopus", "ISRC=OPUS1")
        val opusTags = extract(oggPage(0, "OpusHead".toByteArray() + ByteArray(11), firstPage = true) + oggPage(1, opus))
        assertEquals("OPUS1", opusTags.isrc)
    }

    @Test
    fun wavAndAiffId3ChunksAreRead() {
        val tag = id3(3, frame3("TCOM", utf8Frame("Bach")))
        val riffBody = "WAVE".toByteArray() + "fmt ".toByteArray() + littleEndian(4) + ByteArray(4) + "id3 ".toByteArray() + littleEndian(tag.size) + tag
        val wav = extract("RIFF".toByteArray() + littleEndian(riffBody.size) + riffBody)
        assertEquals("Wave", wav.container)
        assertEquals("Bach", wav.composer)
        val aiffBody = "AIFF".toByteArray() + "ID3 ".toByteArray() + bigEndian(tag.size) + tag
        val aiff = extract("FORM".toByteArray() + bigEndian(aiffBody.size) + aiffBody)
        assertEquals("AIFF", aiff.container)
        assertEquals("Bach", aiff.composer)
    }

    @Test
    fun mp4ItemsAndFreeformAtomsAreRead() {
        val tags = extract(
            mp4(
                atom("©wrt", dataAtom(1, "Bach".toByteArray())),
                atom("©too", dataAtom(1, "Lavf60".toByteArray())),
                atom("trkn", dataAtom(0, byteArrayOf(0, 0, 0, 3, 0, 12, 0, 0))),
                atom("disk", dataAtom(0, byteArrayOf(0, 0, 0, 1, 0, 2))),
                freeform("LYRICIST", "Picander"),
                freeform("ISRC", "MP4ISRC"),
                freeform("replaygain_track_gain", "-3.00 dB"),
                atom("covr", dataAtom(14, ByteArray(4096))),
            ),
        )
        assertEquals("MPEG-4", tags.container)
        assertEquals("Bach", tags.composer)
        assertEquals("Picander", tags.lyricist)
        assertEquals("Lavf60", tags.encoder)
        assertEquals(12, tags.trackTotal)
        assertEquals(2, tags.discTotal)
        assertEquals("MP4ISRC", tags.isrc)
        assertEquals("-3.00 dB", tags.replayGainTrackGain)
        assertEquals("image/png", tags.coverMimeType)
    }

    @Test
    fun aFileWithoutTagsOrWithBadDataGivesWhatItCan() {
        assertEquals(TrackTags(), extract(ByteArray(0)))
        assertEquals(TrackTags(), extract("not an audio file".toByteArray()))
        assertEquals("MPEG Audio", extract(byteArrayOf(0xff.toByte(), 0xfb.toByte()) + ByteArray(64)).container)
        assertEquals("ADTS", extract(byteArrayOf(0xff.toByte(), 0xf1.toByte()) + ByteArray(64)).container)
        // A frame that claims more than the tag holds ends the walk with what came before it.
        val cut = id3(3, frame3("TCOM", utf8Frame("Kept"))) + ByteArray(8)
        assertEquals("Kept", extract(cut).composer)
        val huge = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0x7f, 0x7f, 0x7f, 0x7f)
        assertNull(extract(huge).composer)
    }

    @Test
    fun overlongTextIsSkippedAndTheNextFrameStillRead() {
        val tags = extract(
            id3(
                3,
                frame3("TCOM", utf8Frame("x".repeat(9_000))),
                frame3("TEXT", utf8Frame("Still read")),
            ),
        )
        assertNull(tags.composer)
        assertEquals("Still read", tags.lyricist)
    }

    @Test
    fun compressionFollowsTheCodecAndAnUnknownOneStaysHidden() {
        listOf("audio/raw", "audio/flac", "audio/alac", "AUDIO/FLAC").forEach { assertEquals(it, true, codecIsLossless(it)) }
        listOf("audio/mpeg", "audio/mp4a-latm", "audio/vorbis", "audio/opus", "audio/g711-alaw").forEach {
            assertEquals(it, false, codecIsLossless(it))
        }
        assertNull(codecIsLossless("audio/something-new"))
        assertNull(codecIsLossless(null))
    }

    @Test
    fun channelLayoutsWithoutACommonNameStayHidden() {
        assertEquals("L R", channelLayoutLabel(12))
        assertEquals("5.1", channelLayoutLabel(252))
        assertNull(channelLayoutLabel(1))
    }
}
