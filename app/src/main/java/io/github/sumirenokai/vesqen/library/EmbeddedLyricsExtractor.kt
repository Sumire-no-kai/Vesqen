package io.github.sumirenokai.vesqen.library

import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.MAX_LYRICS_TEXT_BYTES
import io.github.sumirenokai.vesqen.lyrics.SourceLyrics
import io.github.sumirenokai.vesqen.lyrics.decodeLatinLabelledLyricsText
import io.github.sumirenokai.vesqen.lyrics.decodeLyricsText
import io.github.sumirenokai.vesqen.lyrics.decodeMarkedUtf16LyricsText
import io.github.sumirenokai.vesqen.lyrics.parseLyrics
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Bounded, stream-first lyrics extraction (#169): ID3 `USLT`/`ULT` in MP3, WAV and AIFF, the
 * `LYRICS` and `UNSYNCEDLYRICS` comments of FLAC, Vorbis and Opus, and MP4 `©lyr`. Media3 1.11.0
 * leaves these undecoded or drops the encoding, so this follows the artwork walkers: audio is
 * skipped, never read, and every lyrics payload is capped before allocation. A malformed container
 * fails closed as absent; a lyrics tag that cannot be decoded counts as unreadable.
 */
internal fun extractBoundedEmbeddedLyrics(
    input: InputStream,
    declaredLength: Long?,
): SourceLyrics {
    val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, ARTWORK_BUFFER_BYTES)
    buffered.mark(FORMAT_PROBE_BYTES)
    val header = buffered.readArtworkExact(FORMAT_PROBE_BYTES) ?: return SourceLyrics.Absent
    buffered.reset()
    val candidates = EmbeddedLyricsCandidates()
    when {
        header.hasAsciiPrefix("ID3") -> collectId3Lyrics(buffered, candidates)
        header.hasAsciiPrefix("fLaC") -> collectFlacLyrics(buffered, candidates)
        header.hasAsciiPrefix("OggS") -> collectOggLyrics(buffered, candidates)
        header.hasAsciiPrefix("RIFF") && header.hasAsciiAt("WAVE", 8) ->
            collectChunkedId3Lyrics(buffered, littleEndian = true, declaredLength, candidates)
        header.hasAsciiPrefix("FORM") &&
            (header.hasAsciiAt("AIFF", 8) || header.hasAsciiAt("AIFC", 8)) ->
            collectChunkedId3Lyrics(buffered, littleEndian = false, declaredLength, candidates)
        header.hasAsciiAt("ftyp", 4) || header.hasAsciiAt("moov", 4) || header.hasAsciiAt("free", 4) ->
            declaredLength?.takeIf { it >= MP4_ATOM_HEADER_BYTES }?.let { length ->
                collectMp4Lyrics(buffered, length, depth = 0, insideLyrics = false, Mp4AtomBudget(), candidates)
            }
    }
    return candidates.result()
}

/**
 * Keeps the first synced lyrics, or else the first plain ones. A file may carry several frames or
 * comments, for example plain `UNSYNCEDLYRICS` before an LRC in `LYRICS`.
 */
private class EmbeddedLyricsCandidates {
    private var synced: Lyrics? = null
    private var plain: Lyrics? = null
    private var unreadable = false
    private var offered = 0

    val done: Boolean get() = synced != null || offered >= MAX_EMBEDDED_LYRICS_CANDIDATES

    /** [text] is null when the tag exists but cannot be decoded. */
    fun offer(text: String?) {
        offered++
        if (text == null) {
            unreadable = true
            return
        }
        when (val lyrics = parseLyrics(text)) {
            is Lyrics.Synced -> if (synced == null) synced = lyrics
            is Lyrics.Plain -> if (plain == null) plain = lyrics
            null -> Unit
        }
    }

    fun result(): SourceLyrics = (synced ?: plain)?.let(SourceLyrics::Readable)
        ?: if (unreadable) SourceLyrics.Unreadable else SourceLyrics.Absent
}

private fun collectId3Lyrics(input: InputStream, candidates: EmbeddedLyricsCandidates) {
    val header = input.readArtworkExact(ID3_HEADER_BYTES) ?: return
    if (!header.hasAsciiPrefix("ID3")) return
    val majorVersion = header[3].toInt() and 0xff
    if (majorVersion !in 2..4) return
    val tagSize = decodeSynchsafeInt(header, 6) ?: return
    if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return
    val tagUsesUnsynchronisation = (header[5].toInt() and ID3_UNSYNCHRONISATION_FLAG) != 0
    val rawTagInput = BoundedArtworkInputStream(input, tagSize.toLong())
    val tagInput: InputStream = if (tagUsesUnsynchronisation) {
        UnsynchronisationInputStream(rawTagInput)
    } else {
        rawTagInput
    }

    var remaining = tagSize
    if ((header[5].toInt() and ID3_EXTENDED_HEADER_FLAG) != 0) {
        if (majorVersion == 2) return
        val sizeBytes = tagInput.readArtworkExact(4) ?: return
        val declaredExtendedSize = when (majorVersion) {
            4 -> decodeSynchsafeInt(sizeBytes, 0)
            else -> decodeBigEndianInt(sizeBytes, 0)?.let { it + 4 }
        } ?: return
        if (declaredExtendedSize < 4 || declaredExtendedSize > remaining) return
        if (!tagInput.skipArtworkExactly((declaredExtendedSize - 4).toLong())) return
        remaining -= declaredExtendedSize
    }

    val frameHeaderBytes = if (majorVersion == 2) ID3_V22_FRAME_HEADER_BYTES else ID3_FRAME_HEADER_BYTES
    while (remaining >= frameHeaderBytes && !candidates.done) {
        val frameHeader = tagInput.readArtworkExact(frameHeaderBytes) ?: return
        remaining -= frameHeaderBytes
        val frameIdBytes = if (majorVersion == 2) 3 else 4
        if (frameHeader.take(frameIdBytes).all { it == 0.toByte() }) return
        val frameId = String(frameHeader, 0, frameIdBytes, Charsets.US_ASCII)
        val frameSize = when (majorVersion) {
            2 -> decodeBigEndian24(frameHeader, 3)
            4 -> decodeSynchsafeInt(frameHeader, 4)
            else -> decodeBigEndianInt(frameHeader, 4)
        } ?: return
        if (frameSize <= 0 || frameSize > remaining) return
        remaining -= frameSize

        if (frameId == "USLT" || frameId == "ULT") {
            // The same format-flag rule as the artwork walker: compressed, encrypted or grouped
            // frames are not decoded.
            val formatFlags = if (majorVersion >= 3) frameHeader[9].toInt() and 0xff else 0
            val allowedFormatFlags = if (majorVersion == 4 && tagUsesUnsynchronisation) {
                ID3_V24_FRAME_UNSYNCHRONISATION_FLAG
            } else {
                0
            }
            if ((formatFlags and allowedFormatFlags.inv()) == 0 && frameSize <= MAX_ID3_LYRICS_FRAME_BYTES) {
                val frame = tagInput.readArtworkExact(frameSize) ?: return
                candidates.offer(decodeId3LyricsFrame(frame))
                continue
            }
            candidates.offer(null)
        }
        if (!tagInput.skipArtworkExactly(frameSize.toLong())) return
    }
}

/**
 * `USLT` and v2.2 `ULT`: encoding byte, three-byte language, a terminated descriptor, then the
 * text. UTF-16 terminators are two zero bytes on a two-byte boundary, so a descriptor that ends in
 * an ASCII character is not cut one byte early. Returns null when the text cannot be decoded.
 */
private fun decodeId3LyricsFrame(frame: ByteArray): String? {
    if (frame.size < ID3_LYRICS_DESCRIPTOR_OFFSET) return null
    val encoding = frame[0].toInt() and 0xff
    val textStart = when (encoding) {
        0, 3 -> (ID3_LYRICS_DESCRIPTOR_OFFSET until frame.size)
            .firstOrNull { frame[it] == 0.toByte() }
            ?.plus(1)
        1, 2 -> (ID3_LYRICS_DESCRIPTOR_OFFSET until frame.size - 1 step 2)
            .firstOrNull { frame[it] == 0.toByte() && frame[it + 1] == 0.toByte() }
            ?.plus(2)
        else -> null
    } ?: return null
    var textEnd = frame.size
    // Some taggers end UTF-16 text with a single zero byte.
    if ((encoding == 1 || encoding == 2) && (textEnd - textStart) % 2 == 1 && frame[textEnd - 1] == 0.toByte()) {
        textEnd--
    }
    val text = frame.copyOfRange(textStart, textEnd)
    return when (encoding) {
        0 -> decodeLatinLabelledLyricsText(text)
        1 -> decodeMarkedUtf16LyricsText(text)
        2 -> decodeLyricsText(text, Charsets.UTF_16BE)
        else -> decodeLyricsText(text, Charsets.UTF_8)
    }
}

private fun collectChunkedId3Lyrics(
    input: InputStream,
    littleEndian: Boolean,
    declaredLength: Long?,
    candidates: EmbeddedLyricsCandidates,
) {
    val containerHeader = input.readArtworkExact(12) ?: return
    val declaredContainerBytes = readUnsignedInt(containerHeader, 4, littleEndian) + 8L
    var remaining = minOf(
        declaredLength?.takeIf { it >= 12 } ?: declaredContainerBytes,
        declaredContainerBytes,
    ) - 12L
    var chunks = 0
    while (remaining >= 8L && chunks++ < MAX_CONTAINER_CHUNKS) {
        val chunkHeader = input.readArtworkExact(8) ?: return
        remaining -= 8L
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val chunkLength = readUnsignedInt(chunkHeader, 4, littleEndian)
        if (chunkLength > remaining) return
        if (chunkId.equals("ID3 ", ignoreCase = true)) {
            if (chunkLength <= 0 || chunkLength > MAX_ID3_TAG_BYTES.toLong()) return
            collectId3Lyrics(BoundedArtworkInputStream(input, chunkLength), candidates)
            return
        }
        if (!input.skipArtworkExactly(chunkLength)) return
        remaining -= chunkLength
        if ((chunkLength and 1L) != 0L) {
            if (remaining <= 0 || !input.skipArtworkExactly(1)) return
            remaining--
        }
    }
}

private fun collectFlacLyrics(input: InputStream, candidates: EmbeddedLyricsCandidates) {
    val magic = input.readArtworkExact(4) ?: return
    if (!magic.hasAsciiPrefix("fLaC")) return
    var scannedBytes = 4L
    while (scannedBytes <= MAX_FLAC_METADATA_BYTES.toLong()) {
        val header = input.readArtworkExact(4) ?: return
        scannedBytes += header.size
        val isLast = (header[0].toInt() and 0x80) != 0
        val blockType = header[0].toInt() and 0x7f
        val blockLength = decodeBigEndian24(header, 1) ?: return
        scannedBytes += blockLength.toLong()
        if (scannedBytes > MAX_FLAC_METADATA_BYTES.toLong()) return
        if (blockType == FLAC_VORBIS_COMMENT_BLOCK) {
            // A FLAC stream holds at most one comment block.
            collectVorbisCommentLyrics(BoundedArtworkInputStream(input, blockLength.toLong()), candidates)
            return
        }
        if (!input.skipArtworkExactly(blockLength.toLong()) || isLast) return
    }
}

private fun collectOggLyrics(input: InputStream, candidates: EmbeddedLyricsCandidates) {
    val packet = OggPacketInputStream(input)
    repeat(MAX_OGG_PACKETS) {
        if (!packet.startNextPacket()) return
        if (packet.startsWithCommentHeader()) {
            collectVorbisCommentLyrics(packet, candidates)
            return
        }
        if (!packet.skipRestOfPacket()) return
    }
}

private fun InputStream.startsWithCommentHeader(): Boolean {
    val prefix = readArtworkExact(VORBIS_COMMENT_PREFIX_BYTES) ?: return false
    if (prefix[0] == VORBIS_COMMENT_PACKET_TYPE && prefix.hasAsciiAt("vorbis", 1)) return true
    return prefix.hasAsciiPrefix("OpusTag") && read() == 's'.code
}

/** Reads a Vorbis comment list from the vendor length onward. Values are UTF-8 by the format. */
private fun collectVorbisCommentLyrics(input: InputStream, candidates: EmbeddedLyricsCandidates) {
    val vendorLength = input.readLittleEndianUnsignedInt() ?: return
    if (!input.skipArtworkExactly(vendorLength)) return
    val commentCount = input.readLittleEndianUnsignedInt()?.takeIf { it <= MAX_OGG_COMMENTS } ?: return
    repeat(commentCount.toInt()) {
        if (candidates.done) return
        val length = input.readLittleEndianUnsignedInt() ?: return
        val key = ByteArray(MAX_COMMENT_KEY_BYTES)
        var keyLength = 0
        var consumed = 0L
        var hasSeparator = false
        while (consumed < length && keyLength < MAX_COMMENT_KEY_BYTES) {
            val value = input.read()
            if (value < 0) return
            consumed++
            if (value == '='.code) {
                hasSeparator = true
                break
            }
            key[keyLength++] = value.toByte()
        }
        val valueLength = length - consumed
        val isLyrics = hasSeparator && String(key, 0, keyLength, Charsets.US_ASCII).let { name ->
            name.equals("LYRICS", ignoreCase = true) || name.equals("UNSYNCEDLYRICS", ignoreCase = true)
        }
        if (isLyrics && valueLength <= MAX_LYRICS_TEXT_BYTES) {
            val value = input.readArtworkExact(valueLength.toInt()) ?: return
            candidates.offer(decodeLyricsText(value))
            return@repeat
        }
        if (isLyrics) candidates.offer(null)
        if (!input.skipArtworkExactly(valueLength)) return
    }
}

private fun InputStream.readLittleEndianUnsignedInt(): Long? =
    readArtworkExact(4)?.let { bytes -> readUnsignedInt(bytes, 0, littleEndian = true) }

private fun collectMp4Lyrics(
    input: InputStream,
    remainingBytes: Long,
    depth: Int,
    insideLyrics: Boolean,
    budget: Mp4AtomBudget,
    candidates: EmbeddedLyricsCandidates,
) {
    if (depth > MAX_MP4_DEPTH || remainingBytes < MP4_ATOM_HEADER_BYTES) return
    var remaining = remainingBytes
    while (remaining >= MP4_ATOM_HEADER_BYTES && budget.remaining-- > 0 && !candidates.done) {
        val header = input.readArtworkExact(MP4_ATOM_HEADER_BYTES) ?: return
        val atomType = String(header, 4, 4, Charsets.ISO_8859_1)
        var headerBytes = MP4_ATOM_HEADER_BYTES
        val compactSize = readUnsignedInt(header, 0, littleEndian = false)
        val atomSize = when (compactSize) {
            0L -> remaining
            1L -> {
                val extended = input.readArtworkExact(8) ?: return
                headerBytes += 8
                readUnsignedLong(extended) ?: return
            }
            else -> compactSize
        }
        if (atomSize < headerBytes || atomSize > remaining) return
        var payloadBytes = atomSize - headerBytes
        remaining -= atomSize

        if (insideLyrics && atomType == "data") {
            // Type indicator (version byte plus 24-bit type) and locale, then the text.
            val textBytes = payloadBytes - MP4_DATA_PREAMBLE_BYTES
            if (textBytes < 0) return
            val preamble = input.readArtworkExact(MP4_DATA_PREAMBLE_BYTES.toInt()) ?: return
            if (textBytes <= MAX_LYRICS_TEXT_BYTES) {
                val text = input.readArtworkExact(textBytes.toInt()) ?: return
                val type = readUnsignedInt(preamble, 0, littleEndian = false) and MP4_DATA_TYPE_MASK
                candidates.offer(
                    when (type) {
                        MP4_DATA_TYPE_UTF8 -> decodeLyricsText(text, Charsets.UTF_8)
                        MP4_DATA_TYPE_UTF16 -> decodeLyricsText(text, Charsets.UTF_16BE)
                        else -> null
                    },
                )
                continue
            }
            candidates.offer(null)
            if (!input.skipArtworkExactly(textBytes)) return
            continue
        }

        if (atomType in MP4_LYRICS_CONTAINER_ATOMS) {
            if (atomType == "meta") {
                if (payloadBytes < MP4_FULL_BOX_BYTES || !input.skipArtworkExactly(MP4_FULL_BOX_BYTES)) return
                payloadBytes -= MP4_FULL_BOX_BYTES
            }
            val atomInput = BoundedArtworkInputStream(input, payloadBytes)
            collectMp4Lyrics(
                input = atomInput,
                remainingBytes = payloadBytes,
                depth = depth + 1,
                insideLyrics = insideLyrics || atomType == MP4_LYRICS_ATOM,
                budget = budget,
                candidates = candidates,
            )
            if (candidates.done || !atomInput.skipRemaining()) return
            continue
        }
        if (!input.skipArtworkExactly(payloadBytes)) return
    }
}

/**
 * Reads one Ogg packet at a time across pages, so a comment packet that also holds a large picture
 * never has to fit in memory. Reads return -1 at the end of the current packet.
 */
private class OggPacketInputStream(private val delegate: InputStream) : InputStream() {
    private var lacing = ByteArray(0)
    private var nextLace = 0
    private var segmentRemaining = 0
    private var lastSegmentOfPacket = true
    private var pages = 0
    private var scannedBytes = 0L

    /** Moves to the next packet; the current one must have been read or skipped to its end. */
    fun startNextPacket(): Boolean = segmentRemaining == 0 && lastSegmentOfPacket && startNextSegment()

    fun skipRestOfPacket(): Boolean {
        while (true) {
            if (segmentRemaining > 0) {
                if (!delegate.skipArtworkExactly(segmentRemaining.toLong())) return false
                segmentRemaining = 0
            }
            if (lastSegmentOfPacket) return true
            if (!startNextSegment()) return false
        }
    }

    override fun read(): Int {
        if (!hasPacketBytes()) return -1
        return delegate.read().also { value -> if (value >= 0) segmentRemaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!hasPacketBytes()) return -1
        return delegate.read(bytes, offset, minOf(length, segmentRemaining)).also { read ->
            if (read > 0) segmentRemaining -= read
        }
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0 || !hasPacketBytes()) return 0
        return delegate.skip(minOf(byteCount, segmentRemaining.toLong())).also { skipped ->
            if (skipped > 0) segmentRemaining -= skipped.toInt()
        }
    }

    private fun hasPacketBytes(): Boolean {
        while (segmentRemaining == 0) {
            if (lastSegmentOfPacket || !startNextSegment()) return false
        }
        return true
    }

    private fun startNextSegment(): Boolean {
        while (nextLace >= lacing.size) {
            if (!readPageHeader()) return false
        }
        val length = lacing[nextLace++].toInt() and 0xff
        segmentRemaining = length
        lastSegmentOfPacket = length < OGG_FULL_SEGMENT_BYTES
        return true
    }

    private fun readPageHeader(): Boolean {
        if (pages++ >= MAX_OGG_PAGES) return false
        val header = delegate.readArtworkExact(OGG_PAGE_HEADER_BYTES) ?: return false
        if (!header.hasAsciiPrefix("OggS") || header[4] != 0.toByte()) return false
        lacing = delegate.readArtworkExact(header[26].toInt() and 0xff) ?: return false
        nextLace = 0
        scannedBytes += header.size + lacing.size + lacing.sumOf { it.toInt() and 0xff }
        return scannedBytes <= MAX_OGG_METADATA_BYTES
    }
}

private const val MAX_EMBEDDED_LYRICS_CANDIDATES = 8
private const val ID3_LYRICS_DESCRIPTOR_OFFSET = 4
private const val MAX_ID3_LYRICS_FRAME_BYTES = MAX_LYRICS_TEXT_BYTES + 4_096
private const val FLAC_VORBIS_COMMENT_BLOCK = 4
private const val VORBIS_COMMENT_PREFIX_BYTES = 7
private const val VORBIS_COMMENT_PACKET_TYPE: Byte = 3
private const val OGG_PAGE_HEADER_BYTES = 27
private const val OGG_FULL_SEGMENT_BYTES = 255
private const val MP4_LYRICS_ATOM = "\u00A9lyr"
private const val MP4_DATA_TYPE_MASK = 0xFFFFFFL
private const val MP4_DATA_TYPE_UTF8 = 1L
private const val MP4_DATA_TYPE_UTF16 = 2L
private val MP4_LYRICS_CONTAINER_ATOMS = setOf("moov", "udta", "meta", "ilst", MP4_LYRICS_ATOM)
