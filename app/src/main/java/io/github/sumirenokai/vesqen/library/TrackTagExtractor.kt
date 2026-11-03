package io.github.sumirenokai.vesqen.library

import io.github.sumirenokai.vesqen.lyrics.decodeLatinLabelledLyricsText
import io.github.sumirenokai.vesqen.lyrics.decodeLyricsText
import io.github.sumirenokai.vesqen.lyrics.decodeMarkedUtf16LyricsText
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Base64

/** The tags track details show beyond the catalog's own (#240). Null or empty means the file has none. */
data class TrackTags(
    val container: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val encoder: String? = null,
    val isrc: String? = null,
    val trackTotal: Int? = null,
    val discTotal: Int? = null,
    val replayGainTrackGain: String? = null,
    val replayGainTrackPeak: String? = null,
    val replayGainAlbumGain: String? = null,
    val replayGainAlbumPeak: String? = null,
    val coverMimeType: String? = null,
    val coverIsFront: Boolean = false,
)

/**
 * Reads [TrackTags] the way the artwork and lyrics extractors read theirs: audio is skipped, never
 * read, and only short text is held in memory, so a large picture or a long comment costs no
 * allocation. A malformed container ends the walk with what was found so far; it never throws for
 * bad data, and tags never block playback.
 */
internal fun extractBoundedTrackTags(input: InputStream, declaredLength: Long?): TrackTags {
    val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, ARTWORK_BUFFER_BYTES)
    buffered.mark(FORMAT_PROBE_BYTES)
    val header = buffered.readArtworkExact(FORMAT_PROBE_BYTES) ?: return TrackTags()
    buffered.reset()
    val tags = TagCollector()
    when {
        header.hasAsciiPrefix("ID3") -> collectLeadingId3Tags(buffered, tags)
        header.hasAsciiPrefix("fLaC") -> {
            tags.container = "FLAC"
            collectFlacTags(buffered, tags)
        }
        header.hasAsciiPrefix("OggS") -> {
            tags.container = "Ogg"
            collectOggTags(buffered, tags)
        }
        header.hasAsciiPrefix("RIFF") && header.hasAsciiAt("WAVE", 8) -> {
            tags.container = "Wave"
            collectChunkedId3Tags(buffered, littleEndian = true, declaredLength, tags)
        }
        header.hasAsciiPrefix("FORM") && (header.hasAsciiAt("AIFF", 8) || header.hasAsciiAt("AIFC", 8)) -> {
            tags.container = "AIFF"
            collectChunkedId3Tags(buffered, littleEndian = false, declaredLength, tags)
        }
        header.hasAsciiAt("ftyp", 4) || header.hasAsciiAt("moov", 4) || header.hasAsciiAt("free", 4) -> {
            tags.container = "MPEG-4"
            declaredLength?.takeIf { it >= MP4_ATOM_HEADER_BYTES }?.let { length ->
                collectMp4Tags(buffered, length, depth = 0, Mp4AtomBudget(), tags)
            }
        }
        else -> tags.container = frameSyncContainer(header)
    }
    return tags.result()
}

private fun frameSyncContainer(header: ByteArray): String? {
    val first = header[0].toInt() and 0xff
    val second = header[1].toInt() and 0xff
    if (first != 0xff || (second and 0xe0) != 0xe0) return null
    // Layer bits of zero mean ADTS AAC; anything else is an MPEG audio frame.
    return if ((second and 0x06) == 0) "ADTS" else "MPEG Audio"
}

private class TagCollector {
    var container: String? = null
    var composer: String? = null
    var lyricist: String? = null
    var encoder: String? = null
    var vendor: String? = null
    var isrc: String? = null
    var trackTotal: Int? = null
    var discTotal: Int? = null
    var trackGain: String? = null
    var trackPeak: String? = null
    var albumGain: String? = null
    var albumPeak: String? = null
    var coverMimeType: String? = null
    var coverIsFront = false

    /** The first picture counts, or the first front cover if one comes later. */
    fun offerCover(mimeType: String?, front: Boolean) {
        val mime = mimeType?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return
        if (coverMimeType == null || (front && !coverIsFront)) {
            coverMimeType = mime
            coverIsFront = front
        }
    }

    /** ReplayGain has the same names in ID3 TXXX, Vorbis comments and MP4 freeform atoms. */
    fun offerNamed(name: String, value: String) {
        when (name.uppercase()) {
            "COMPOSER" -> composer = composer.withCredit(value)
            "LYRICIST" -> lyricist = lyricist.withCredit(value)
            "ENCODER", "ENCODED-BY", "ENCODEDBY" -> encoder = encoder ?: value
            "ISRC" -> isrc = isrc ?: value
            "TRACKTOTAL", "TOTALTRACKS" -> trackTotal = trackTotal ?: value.positiveNumber()
            "DISCTOTAL", "TOTALDISCS" -> discTotal = discTotal ?: value.positiveNumber()
            "TRACKNUMBER" -> trackTotal = trackTotal ?: value.totalAfterSlash()
            "DISCNUMBER" -> discTotal = discTotal ?: value.totalAfterSlash()
            "REPLAYGAIN_TRACK_GAIN" -> trackGain = trackGain ?: value
            "REPLAYGAIN_TRACK_PEAK" -> trackPeak = trackPeak ?: value
            "REPLAYGAIN_ALBUM_GAIN" -> albumGain = albumGain ?: value
            "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = albumPeak ?: value
        }
    }

    fun result() = TrackTags(
        container = container,
        composer = composer,
        lyricist = lyricist,
        encoder = encoder ?: vendor,
        isrc = isrc,
        trackTotal = trackTotal,
        discTotal = discTotal,
        replayGainTrackGain = trackGain,
        replayGainTrackPeak = trackPeak,
        replayGainAlbumGain = albumGain,
        replayGainAlbumPeak = albumPeak,
        coverMimeType = coverMimeType,
        coverIsFront = coverIsFront,
    )
}

/** Vorbis comments hold several credits by repeating the field. A file can repeat it freely, so the text is capped. */
private fun String?.withCredit(value: String): String = when {
    this == null -> value
    value in split(CREDIT_SEPARATOR) || length + value.length > MAX_TAG_TEXT_BYTES -> this
    else -> this + CREDIT_SEPARATOR + value
}

private fun String.positiveNumber(): Int? = trim().toIntOrNull()?.takeIf { it > 0 }

private fun String.totalAfterSlash(): Int? = substringAfter('/', "").positiveNumber()

// ID3

/** The audio after the tag names the container: some taggers put one before "fLaC" or ADTS, not only MPEG audio. */
private fun collectLeadingId3Tags(input: BufferedInputStream, tags: TagCollector) {
    input.mark(ID3_HEADER_BYTES)
    val header = input.readArtworkExact(ID3_HEADER_BYTES) ?: return
    input.reset()
    val tagSize = decodeSynchsafeInt(header, 6)?.takeIf { it in 1..MAX_ID3_TAG_BYTES } ?: return
    val hasFooter = (header[3].toInt() and 0xff) == 4 && (header[5].toInt() and ID3_V24_FOOTER) != 0
    val tag = BoundedArtworkInputStream(input, ID3_HEADER_BYTES + tagSize.toLong() + if (hasFooter) ID3_HEADER_BYTES else 0)
    collectId3Tags(tag, tags)
    if (!tag.skipRemaining()) return
    input.mark(4)
    val magic = input.readArtworkExact(4) ?: return
    input.reset()
    if (magic.hasAsciiPrefix("fLaC")) {
        tags.container = "FLAC"
        collectFlacTags(input, tags)
    } else {
        tags.container = frameSyncContainer(magic)
    }
}

private fun collectId3Tags(input: InputStream, tags: TagCollector) {
    val header = input.readArtworkExact(ID3_HEADER_BYTES) ?: return
    if (!header.hasAsciiPrefix("ID3")) return
    val majorVersion = header[3].toInt() and 0xff
    if (majorVersion !in 2..4) return
    val tagSize = decodeSynchsafeInt(header, 6)?.takeIf { it in 1..MAX_ID3_TAG_BYTES } ?: return
    val tagUnsynchronised = (header[5].toInt() and ID3_UNSYNCHRONISATION_FLAG) != 0
    val rawTag = BoundedArtworkInputStream(input, tagSize.toLong())
    // v2.2 and v2.3 unsynchronise the whole tag; v2.4 does it per frame.
    val tagInput: InputStream = if (tagUnsynchronised && majorVersion < 4) UnsynchronisationInputStream(rawTag) else rawTag
    var remaining = tagSize
    if ((header[5].toInt() and ID3_EXTENDED_HEADER_FLAG) != 0) {
        if (majorVersion == 2) return
        val sizeBytes = tagInput.readArtworkExact(4) ?: return
        val extended = when (majorVersion) {
            4 -> decodeSynchsafeInt(sizeBytes, 0)
            else -> decodeBigEndianInt(sizeBytes, 0)?.let { it + 4 }
        } ?: return
        if (extended < 4 || extended > remaining || !tagInput.skipArtworkExactly((extended - 4).toLong())) return
        remaining -= extended
    }
    val frameHeaderBytes = if (majorVersion == 2) ID3_V22_FRAME_HEADER_BYTES else ID3_FRAME_HEADER_BYTES
    while (remaining >= frameHeaderBytes) {
        val frameHeader = tagInput.readArtworkExact(frameHeaderBytes) ?: return
        remaining -= frameHeaderBytes
        val idBytes = if (majorVersion == 2) 3 else 4
        if (frameHeader.take(idBytes).all { it == 0.toByte() }) return
        val frameId = String(frameHeader, 0, idBytes, Charsets.US_ASCII)
        val frameSize = when (majorVersion) {
            2 -> decodeBigEndian24(frameHeader, 3)
            4 -> decodeSynchsafeInt(frameHeader, 4)
            else -> decodeBigEndianInt(frameHeader, 4)
        } ?: return
        if (frameSize <= 0 || frameSize > remaining) return
        remaining -= frameSize
        // Compressed, encrypted or grouped frames are not decoded.
        val formatFlags = if (majorVersion >= 3) frameHeader[9].toInt() and 0xff else 0
        val readable = if (majorVersion == 4) ID3_V24_FRAME_UNSYNCHRONISATION_FLAG or ID3_V24_DATA_LENGTH else 0
        val decodable = (formatFlags and readable.inv()) == 0
        when {
            decodable && frameId in ID3_PICTURE_FRAMES -> {
                val prefix = minOf(frameSize, MAX_PICTURE_PREFIX_BYTES)
                val bytes = tagInput.readArtworkExact(prefix) ?: return
                readId3Picture(frameId, bytes.withoutDataLength(formatFlags), tags)
                if (!tagInput.skipArtworkExactly((frameSize - prefix).toLong())) return
            }
            decodable && frameId in ID3_TEXT_FRAMES && frameSize <= MAX_TAG_TEXT_BYTES -> {
                var bytes = tagInput.readArtworkExact(frameSize) ?: return
                bytes = bytes.withoutDataLength(formatFlags)
                val unsynchronised = majorVersion == 4 &&
                    (tagUnsynchronised || (formatFlags and ID3_V24_FRAME_UNSYNCHRONISATION_FLAG) != 0)
                if (unsynchronised) bytes = UnsynchronisationInputStream(bytes.inputStream()).readBytes()
                readId3Text(frameId, bytes, tags)
            }
            else -> if (!tagInput.skipArtworkExactly(frameSize.toLong())) return
        }
    }
}

private fun ByteArray.withoutDataLength(formatFlags: Int): ByteArray =
    if ((formatFlags and ID3_V24_DATA_LENGTH) != 0 && size >= 4) copyOfRange(4, size) else this

private fun readId3Text(frameId: String, frame: ByteArray, tags: TagCollector) {
    val values = decodeId3Values(frame)
    if (values.isEmpty()) return
    when (frameId) {
        // The description comes first, and the value after it.
        "TXXX", "TXX" -> if (values.size >= 2) tags.offerNamed(values[0], values[1])
        "TCOM", "TCM" -> tags.composer = tags.composer ?: values.joinToString(CREDIT_SEPARATOR)
        "TEXT", "TXT" -> tags.lyricist = tags.lyricist ?: values.joinToString(CREDIT_SEPARATOR)
        "TSSE", "TSS" -> tags.encoder = tags.encoder ?: values.first()
        "TSRC", "TRC" -> tags.isrc = tags.isrc ?: values.first()
        "TRCK", "TRK" -> tags.trackTotal = tags.trackTotal ?: values.first().totalAfterSlash()
        "TPOS", "TPA" -> tags.discTotal = tags.discTotal ?: values.first().totalAfterSlash()
    }
}

/**
 * A text frame's values: v2.4 and TXXX separate them with a terminator of the encoding's width.
 * The decoders refuse a NUL, so the bytes are split first. In UTF-16 only the first value may carry
 * the byte order mark; a later one without a mark reads in the same order.
 */
private fun decodeId3Values(frame: ByteArray): List<String> {
    if (frame.size < 2) return emptyList()
    val encoding = frame[0].toInt() and 0xff
    val step = if (encoding == 1 || encoding == 2) 2 else 1
    val parts = ArrayList<ByteArray>()
    var start = 1
    var at = 1
    while (at + step <= frame.size) {
        if ((0 until step).all { frame[at + it] == 0.toByte() }) {
            parts += frame.copyOfRange(start, at)
            start = at + step
        }
        at += step
    }
    if (start < frame.size) parts += frame.copyOfRange(start, frame.size)
    var utf16 = Charsets.UTF_16BE
    return parts.mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        when (encoding) {
            0 -> decodeLatinLabelledLyricsText(part)
            1 -> when {
                part.size >= 2 && part[0] == 0xFF.toByte() && part[1] == 0xFE.toByte() -> {
                    utf16 = Charsets.UTF_16LE
                    decodeMarkedUtf16LyricsText(part)
                }
                part.size >= 2 && part[0] == 0xFE.toByte() && part[1] == 0xFF.toByte() -> {
                    utf16 = Charsets.UTF_16BE
                    decodeMarkedUtf16LyricsText(part)
                }
                else -> decodeLyricsText(part, utf16)
            }
            2 -> decodeLyricsText(part, Charsets.UTF_16BE)
            3 -> decodeLyricsText(part, Charsets.UTF_8)
            else -> null
        }?.removePrefix("\uFEFF")?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** APIC: encoding, a terminated MIME type, then the picture type. PIC: encoding, a 3-byte format, then the type. */
private fun readId3Picture(frameId: String, frame: ByteArray, tags: TagCollector) {
    if (frameId == "PIC") {
        if (frame.size < 5) return
        val format = String(frame, 1, 3, Charsets.ISO_8859_1).trim().lowercase()
        tags.offerCover(if (format == "jpg") "image/jpeg" else "image/$format", frame[4].toInt() == PICTURE_TYPE_FRONT)
        return
    }
    val end = (1 until frame.size).firstOrNull { frame[it] == 0.toByte() } ?: return
    val type = frame.getOrNull(end + 1)?.toInt() ?: return
    tags.offerCover(String(frame, 1, end - 1, Charsets.ISO_8859_1), type == PICTURE_TYPE_FRONT)
}

private fun collectChunkedId3Tags(input: InputStream, littleEndian: Boolean, declaredLength: Long?, tags: TagCollector) {
    val containerHeader = input.readArtworkExact(12) ?: return
    val containerBytes = readUnsignedInt(containerHeader, 4, littleEndian) + 8L
    var remaining = minOf(declaredLength?.takeIf { it >= 12 } ?: containerBytes, containerBytes) - 12L
    var chunks = 0
    while (remaining >= 8L && chunks++ < MAX_CONTAINER_CHUNKS) {
        val chunkHeader = input.readArtworkExact(8) ?: return
        remaining -= 8L
        val chunkLength = readUnsignedInt(chunkHeader, 4, littleEndian)
        if (chunkLength > remaining) return
        if (String(chunkHeader, 0, 4, Charsets.US_ASCII).equals("ID3 ", ignoreCase = true)) {
            if (chunkLength in 1..MAX_ID3_TAG_BYTES.toLong()) collectId3Tags(BoundedArtworkInputStream(input, chunkLength), tags)
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

// FLAC and Ogg

private fun collectFlacTags(input: InputStream, tags: TagCollector) {
    val magic = input.readArtworkExact(4) ?: return
    if (!magic.hasAsciiPrefix("fLaC")) return
    var scanned = 4L
    while (scanned <= MAX_FLAC_METADATA_BYTES.toLong()) {
        val header = input.readArtworkExact(4) ?: return
        val isLast = (header[0].toInt() and 0x80) != 0
        val blockType = header[0].toInt() and 0x7f
        val blockLength = decodeBigEndian24(header, 1) ?: return
        scanned += 4L + blockLength
        if (scanned > MAX_FLAC_METADATA_BYTES.toLong()) return
        val block = BoundedArtworkInputStream(input, blockLength.toLong())
        when (blockType) {
            FLAC_COMMENT_BLOCK -> collectVorbisComments(block, tags)
            FLAC_PICTURE_BLOCK -> readFlacPictureHeader(block, tags)
        }
        if (!block.skipRemaining() || isLast) return
    }
}

/** A FLAC picture block, also the body of a Vorbis METADATA_BLOCK_PICTURE: type, then a sized MIME type. */
private fun readFlacPictureHeader(input: InputStream, tags: TagCollector) {
    val head = input.readArtworkExact(8) ?: return
    val mimeLength = readUnsignedInt(head, 4, littleEndian = false)
    if (mimeLength !in 1..MAX_MIME_BYTES) return
    val mime = input.readArtworkExact(mimeLength.toInt()) ?: return
    tags.offerCover(String(mime, Charsets.US_ASCII), readUnsignedInt(head, 0, littleEndian = false) == PICTURE_TYPE_FRONT.toLong())
}

private fun collectOggTags(input: InputStream, tags: TagCollector) {
    val packet = OggPacketInputStream(input)
    repeat(MAX_OGG_PACKETS) {
        if (!packet.startNextPacket()) return
        val prefix = packet.readArtworkExact(7) ?: return
        val isVorbis = prefix[0] == 3.toByte() && prefix.hasAsciiAt("vorbis", 1)
        val isOpus = prefix.hasAsciiPrefix("OpusTag") && packet.read() == 's'.code
        if (isVorbis || isOpus) {
            collectVorbisComments(packet, tags)
            return
        }
        if (!packet.skipRestOfPacket()) return
    }
}

private fun collectVorbisComments(input: InputStream, tags: TagCollector) {
    val vendorLength = input.readLittleEndianInt() ?: return
    if (vendorLength <= MAX_TAG_TEXT_BYTES) {
        val vendor = input.readArtworkExact(vendorLength.toInt()) ?: return
        tags.vendor = decodeLyricsText(vendor, Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)
    } else if (!input.skipArtworkExactly(vendorLength)) {
        return
    }
    val count = input.readLittleEndianInt()?.takeIf { it <= MAX_OGG_COMMENTS } ?: return
    repeat(count.toInt()) {
        val length = input.readLittleEndianInt() ?: return
        val key = StringBuilder()
        var consumed = 0L
        var separated = false
        while (consumed < length && key.length < MAX_COMMENT_KEY_BYTES) {
            val value = input.read()
            if (value < 0) return
            consumed++
            if (value == '='.code) {
                separated = true
                break
            }
            key.append(value.toChar())
        }
        val valueLength = length - consumed
        val name = key.toString().uppercase()
        when {
            !separated -> if (!input.skipArtworkExactly(valueLength)) return
            name == "METADATA_BLOCK_PICTURE" -> {
                val prefix = minOf(valueLength, MAX_PICTURE_BASE64_PREFIX_BYTES.toLong()).toInt()
                val encoded = input.readArtworkExact(prefix) ?: return
                // Whole base64 groups only, since the prefix cuts the value.
                val whole = encoded.copyOf(encoded.size - encoded.size % 4)
                runCatching { Base64.getDecoder().decode(whole) }.getOrNull()
                    ?.let { readFlacPictureHeader(it.inputStream(), tags) }
                if (!input.skipArtworkExactly(valueLength - prefix)) return
            }
            name in VORBIS_TEXT_COMMENTS && valueLength <= MAX_TAG_TEXT_BYTES -> {
                val bytes = input.readArtworkExact(valueLength.toInt()) ?: return
                decodeLyricsText(bytes, Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)?.let { tags.offerNamed(name, it) }
            }
            else -> if (!input.skipArtworkExactly(valueLength)) return
        }
    }
}

private fun InputStream.readLittleEndianInt(): Long? =
    readArtworkExact(4)?.let { readUnsignedInt(it, 0, littleEndian = true) }

// MP4

private fun collectMp4Tags(input: InputStream, remainingBytes: Long, depth: Int, budget: Mp4AtomBudget, tags: TagCollector) {
    if (depth > MAX_MP4_DEPTH) return
    var remaining = remainingBytes
    while (remaining >= MP4_ATOM_HEADER_BYTES && budget.remaining-- > 0) {
        val header = input.readArtworkExact(MP4_ATOM_HEADER_BYTES) ?: return
        val atomType = String(header, 4, 4, Charsets.ISO_8859_1)
        var headerBytes = MP4_ATOM_HEADER_BYTES
        val atomSize = when (val compact = readUnsignedInt(header, 0, littleEndian = false)) {
            0L -> remaining
            1L -> {
                val extended = input.readArtworkExact(8) ?: return
                headerBytes += 8
                readUnsignedLong(extended) ?: return
            }
            else -> compact
        }
        if (atomSize < headerBytes || atomSize > remaining) return
        var payload = atomSize - headerBytes
        remaining -= atomSize
        when {
            atomType in MP4_TAG_CONTAINERS -> {
                if (atomType == "meta") {
                    if (payload < MP4_FULL_BOX_BYTES || !input.skipArtworkExactly(MP4_FULL_BOX_BYTES)) return
                    payload -= MP4_FULL_BOX_BYTES
                }
                val atom = BoundedArtworkInputStream(input, payload)
                collectMp4Tags(atom, payload, depth + 1, budget, tags)
                if (!atom.skipRemaining()) return
            }
            atomType == "covr" -> {
                val atom = BoundedArtworkInputStream(input, payload)
                readMp4Cover(atom, tags)
                if (!atom.skipRemaining()) return
            }
            atomType in MP4_TEXT_ITEMS && payload <= MAX_TAG_TEXT_BYTES -> {
                val body = input.readArtworkExact(payload.toInt()) ?: return
                readMp4Item(atomType, body, tags)
            }
            else -> if (!input.skipArtworkExactly(payload)) return
        }
    }
}

private fun readMp4Cover(input: InputStream, tags: TagCollector) {
    val head = input.readArtworkExact(MP4_ATOM_HEADER_BYTES + MP4_DATA_PREAMBLE_BYTES.toInt()) ?: return
    if (!head.hasAsciiAt("data", 4)) return
    val mime = when (readUnsignedInt(head, MP4_ATOM_HEADER_BYTES, littleEndian = false) and MP4_TYPE_MASK) {
        MP4_TYPE_JPEG -> "image/jpeg"
        MP4_TYPE_PNG -> "image/png"
        else -> return
    }
    tags.offerCover(mime, front = true)
}

private fun readMp4Item(atomType: String, body: ByteArray, tags: TagCollector) {
    var name: String? = null
    var offset = 0
    while (offset + MP4_ATOM_HEADER_BYTES <= body.size) {
        val size = readUnsignedInt(body, offset, littleEndian = false)
        if (size < MP4_ATOM_HEADER_BYTES || offset + size > body.size) return
        val end = offset + size.toInt()
        val contentStart = offset + MP4_ATOM_HEADER_BYTES
        when {
            // A freeform atom names its value; both name and value have a preamble of their own.
            body.hasAsciiAt("name", offset + 4) && contentStart + 4 <= end ->
                name = String(body, contentStart + 4, end - contentStart - 4, Charsets.UTF_8)
            body.hasAsciiAt("data", offset + 4) && contentStart + MP4_DATA_PREAMBLE_BYTES <= end ->
                readMp4Data(atomType, name, body.copyOfRange(contentStart, end), tags)
        }
        offset = end
    }
}

private fun readMp4Data(atomType: String, name: String?, data: ByteArray, tags: TagCollector) {
    val type = readUnsignedInt(data, 0, littleEndian = false) and MP4_TYPE_MASK
    val value = data.copyOfRange(MP4_DATA_PREAMBLE_BYTES.toInt(), data.size)
    // trkn and disk are binary: reserved, index and total as big-endian 16-bit numbers.
    if (atomType == "trkn" || atomType == "disk") {
        if (value.size < 6) return
        val total = ((value[4].toInt() and 0xff) shl 8) or (value[5].toInt() and 0xff)
        if (total <= 0) return
        if (atomType == "trkn") tags.trackTotal = tags.trackTotal ?: total else tags.discTotal = tags.discTotal ?: total
        return
    }
    val text = when (type) {
        MP4_TYPE_UTF8 -> decodeLyricsText(value, Charsets.UTF_8)
        MP4_TYPE_UTF16 -> decodeLyricsText(value, Charsets.UTF_16BE)
        else -> null
    }?.trim()?.takeIf(String::isNotEmpty) ?: return
    when (atomType) {
        "©wrt" -> tags.composer = tags.composer ?: text
        "©too" -> tags.encoder = tags.encoder ?: text
        "----" -> name?.let { tags.offerNamed(it, text) }
    }
}

private const val MAX_TAG_TEXT_BYTES = 8 * 1024
private const val CREDIT_SEPARATOR = ", "
private const val MAX_PICTURE_PREFIX_BYTES = 512
private const val MAX_PICTURE_BASE64_PREFIX_BYTES = 512
private const val MAX_MIME_BYTES = 64L
private const val PICTURE_TYPE_FRONT = 3
private const val ID3_V24_FOOTER = 0x10
private const val ID3_V24_DATA_LENGTH = 0x01
private const val FLAC_COMMENT_BLOCK = 4
private const val FLAC_PICTURE_BLOCK = 6
private const val MP4_TYPE_MASK = 0x00ffffffL
private const val MP4_TYPE_UTF8 = 1L
private const val MP4_TYPE_UTF16 = 2L
private const val MP4_TYPE_JPEG = 13L
private const val MP4_TYPE_PNG = 14L
private val ID3_PICTURE_FRAMES = setOf("APIC", "PIC")
private val ID3_TEXT_FRAMES = setOf("TXXX", "TXX", "TCOM", "TCM", "TEXT", "TXT", "TSSE", "TSS", "TSRC", "TRC", "TRCK", "TRK", "TPOS", "TPA")
private val VORBIS_TEXT_COMMENTS = setOf(
    "COMPOSER", "LYRICIST", "ENCODER", "ENCODED-BY", "ENCODEDBY", "ISRC", "TRACKTOTAL", "TOTALTRACKS", "DISCTOTAL",
    "TOTALDISCS", "TRACKNUMBER", "DISCNUMBER", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_GAIN",
    "REPLAYGAIN_ALBUM_PEAK",
)
private val MP4_TAG_CONTAINERS = setOf("moov", "udta", "meta", "ilst")
private val MP4_TEXT_ITEMS = setOf("©wrt", "©too", "trkn", "disk", "----")
