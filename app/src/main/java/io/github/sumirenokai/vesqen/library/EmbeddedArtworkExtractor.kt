package io.github.sumirenokai.vesqen.library

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/**
 * Bounded, stream-first artwork extraction for Android 8/9 devices whose MediaProvider cannot
 * expose thumbnails to a normal third-party app. No extractor reads audio frames into memory and
 * every encoded or decoded picture is capped before allocation.
 */
internal fun extractBoundedEmbeddedArtwork(
    input: InputStream,
    declaredLength: Long?,
): ByteArray? {
    val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, ARTWORK_BUFFER_BYTES)
    buffered.mark(FORMAT_PROBE_BYTES)
    val header = buffered.readArtworkExact(FORMAT_PROBE_BYTES) ?: return null
    buffered.reset()
    return when {
        header.hasAsciiPrefix("ID3") -> extractBoundedId3Picture(buffered)
        header.hasAsciiPrefix("fLaC") -> extractBoundedFlacPicture(buffered)
        header.hasAsciiPrefix("OggS") -> extractBoundedOggPicture(buffered)
        header.hasAsciiPrefix("RIFF") && header.hasAsciiAt("WAVE", 8) ->
            extractBoundedChunkedId3Picture(buffered, littleEndian = true, declaredLength = declaredLength)
        header.hasAsciiPrefix("FORM") &&
            (header.hasAsciiAt("AIFF", 8) || header.hasAsciiAt("AIFC", 8)) ->
            extractBoundedChunkedId3Picture(buffered, littleEndian = false, declaredLength = declaredLength)
        header.hasAsciiAt("ftyp", 4) || header.hasAsciiAt("moov", 4) || header.hasAsciiAt("free", 4) ->
            declaredLength?.takeIf { it >= MP4_ATOM_HEADER_BYTES }?.let { length ->
                extractBoundedMp4Cover(buffered, length)
            }
        else -> null
    }
}

internal fun extractBoundedId3Picture(input: InputStream): ByteArray? {
    val header = input.readArtworkExact(ID3_HEADER_BYTES) ?: return null
    if (!header.hasAsciiPrefix("ID3")) return null
    val majorVersion = header[3].toInt() and 0xff
    if (majorVersion !in 2..4) return null
    val tagSize = decodeSynchsafeInt(header, 6) ?: return null
    if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null
    val tagUsesUnsynchronisation = (header[5].toInt() and ID3_UNSYNCHRONISATION_FLAG) != 0
    val rawTagInput = BoundedArtworkInputStream(input, tagSize.toLong())
    val tagInput: InputStream = if (tagUsesUnsynchronisation) {
        UnsynchronisationInputStream(rawTagInput)
    } else {
        rawTagInput
    }

    var remaining = tagSize
    if ((header[5].toInt() and ID3_EXTENDED_HEADER_FLAG) != 0) {
        if (majorVersion == 2) return null
        val sizeBytes = tagInput.readArtworkExact(4) ?: return null
        val declaredExtendedSize = when (majorVersion) {
            4 -> decodeSynchsafeInt(sizeBytes, 0)
            else -> decodeBigEndianInt(sizeBytes, 0)?.let { it + 4 }
        } ?: return null
        if (declaredExtendedSize < 4 || declaredExtendedSize > remaining) return null
        if (!tagInput.skipArtworkExactly((declaredExtendedSize - 4).toLong())) return null
        remaining -= declaredExtendedSize
    }

    val frameHeaderBytes = if (majorVersion == 2) ID3_V22_FRAME_HEADER_BYTES else ID3_FRAME_HEADER_BYTES
    while (remaining >= frameHeaderBytes) {
        val frameHeader = tagInput.readArtworkExact(frameHeaderBytes) ?: return null
        remaining -= frameHeaderBytes
        val frameIdBytes = if (majorVersion == 2) 3 else 4
        if (frameHeader.take(frameIdBytes).all { it == 0.toByte() }) return null
        val frameId = String(frameHeader, 0, frameIdBytes, Charsets.US_ASCII)
        val frameSize = when (majorVersion) {
            2 -> decodeBigEndian24(frameHeader, 3)
            4 -> decodeSynchsafeInt(frameHeader, 4)
            else -> decodeBigEndianInt(frameHeader, 4)
        } ?: return null
        if (frameSize <= 0 || frameSize > remaining) return null

        // Status flags never alter the frame payload. Format flags can introduce grouping,
        // compression, encryption or per-frame unsynchronisation, so fail closed on those.
        val formatFlags = if (majorVersion >= 3) frameHeader[9].toInt() and 0xff else 0
        val allowedFormatFlags = if (majorVersion == 4 && tagUsesUnsynchronisation) {
            ID3_V24_FRAME_UNSYNCHRONISATION_FLAG
        } else {
            0
        }
        val hasUnsupportedFormatFlags = (formatFlags and allowedFormatFlags.inv()) != 0
        if ((frameId == "APIC" || frameId == "PIC") && !hasUnsupportedFormatFlags) {
            if (frameSize.toLong() > MAX_EMBEDDED_ART_BYTES.toLong() + ID3_APIC_OVERHEAD_BYTES) return null
            val frame = tagInput.readArtworkExact(frameSize) ?: return null
            val imageOffset = if (majorVersion == 2) {
                findId3V22PictureOffset(frame)
            } else {
                findId3ApicImageOffset(frame)
            } ?: return null
            val imageSize = frame.size - imageOffset
            if (imageSize <= 0 || imageSize > MAX_EMBEDDED_ART_BYTES) return null
            return frame.copyOfRange(imageOffset, frame.size)
        }
        if (!tagInput.skipArtworkExactly(frameSize.toLong())) return null
        remaining -= frameSize
    }
    return null
}

private fun extractBoundedChunkedId3Picture(
    input: InputStream,
    littleEndian: Boolean,
    declaredLength: Long?,
): ByteArray? {
    val containerHeader = input.readArtworkExact(12) ?: return null
    val declaredContainerBytes = readUnsignedInt(containerHeader, 4, littleEndian) + 8L
    var remaining = minOf(
        declaredLength?.takeIf { it >= 12 } ?: declaredContainerBytes,
        declaredContainerBytes,
    ) - 12L
    var chunks = 0
    while (remaining >= 8L && chunks++ < MAX_CONTAINER_CHUNKS) {
        val chunkHeader = input.readArtworkExact(8) ?: return null
        remaining -= 8L
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val chunkLength = readUnsignedInt(chunkHeader, 4, littleEndian)
        if (chunkLength > remaining) return null
        if (chunkId.equals("ID3 ", ignoreCase = true) || chunkId == "id3 ") {
            if (chunkLength <= 0 || chunkLength > MAX_ID3_TAG_BYTES.toLong()) return null
            val chunk = input.readArtworkExact(chunkLength.toInt()) ?: return null
            return extractBoundedId3Picture(ByteArrayInputStream(chunk))
        }
        if (!input.skipArtworkExactly(chunkLength)) return null
        remaining -= chunkLength
        if ((chunkLength and 1L) != 0L) {
            if (remaining <= 0 || !input.skipArtworkExactly(1)) return null
            remaining--
        }
    }
    return null
}

private fun extractBoundedMp4Cover(input: InputStream, declaredLength: Long): ByteArray? =
    scanMp4Atoms(
        input = input,
        remainingBytes = declaredLength,
        depth = 0,
        insideCover = false,
        budget = Mp4AtomBudget(),
    )

private fun scanMp4Atoms(
    input: InputStream,
    remainingBytes: Long,
    depth: Int,
    insideCover: Boolean,
    budget: Mp4AtomBudget,
): ByteArray? {
    if (depth > MAX_MP4_DEPTH || remainingBytes < MP4_ATOM_HEADER_BYTES) return null
    var remaining = remainingBytes
    while (remaining >= MP4_ATOM_HEADER_BYTES && budget.remaining-- > 0) {
        val header = input.readArtworkExact(MP4_ATOM_HEADER_BYTES) ?: return null
        val atomType = String(header, 4, 4, Charsets.ISO_8859_1)
        var headerBytes = MP4_ATOM_HEADER_BYTES
        val compactSize = readUnsignedInt(header, 0, littleEndian = false)
        val atomSize = when (compactSize) {
            0L -> remaining
            1L -> {
                val extended = input.readArtworkExact(8) ?: return null
                headerBytes += 8
                readUnsignedLong(extended) ?: return null
            }
            else -> compactSize
        }
        if (atomSize < headerBytes || atomSize > remaining) return null
        var payloadBytes = atomSize - headerBytes

        if (insideCover && atomType == "data") {
            if (payloadBytes <= MP4_DATA_PREAMBLE_BYTES ||
                payloadBytes - MP4_DATA_PREAMBLE_BYTES > MAX_EMBEDDED_ART_BYTES.toLong()
            ) return null
            if (!input.skipArtworkExactly(MP4_DATA_PREAMBLE_BYTES)) return null
            return input.readArtworkExact((payloadBytes - MP4_DATA_PREAMBLE_BYTES).toInt())
        }

        if (atomType in MP4_CONTAINER_ATOMS) {
            if (atomType == "meta") {
                if (payloadBytes < MP4_FULL_BOX_BYTES || !input.skipArtworkExactly(MP4_FULL_BOX_BYTES)) return null
                payloadBytes -= MP4_FULL_BOX_BYTES
            }
            val atomInput = BoundedArtworkInputStream(input, payloadBytes)
            scanMp4Atoms(
                input = atomInput,
                remainingBytes = payloadBytes,
                depth = depth + 1,
                insideCover = insideCover || atomType == "covr",
                budget = budget,
            )?.let { return it }
            if (!atomInput.skipRemaining()) return null
            remaining -= atomSize
            continue
        }
        if (!input.skipArtworkExactly(payloadBytes)) return null
        remaining -= atomSize
    }
    return null
}

private fun extractBoundedOggPicture(input: InputStream): ByteArray? {
    val packet = ByteArrayOutputStream()
    var pages = 0
    var completedPackets = 0
    var scannedBytes = 0L
    while (pages++ < MAX_OGG_PAGES && scannedBytes < MAX_OGG_METADATA_BYTES) {
        val pageHeader = input.readArtworkExact(27) ?: return null
        scannedBytes += pageHeader.size
        if (!pageHeader.hasAsciiPrefix("OggS") || pageHeader[4] != 0.toByte()) return null
        val segmentCount = pageHeader[26].toInt() and 0xff
        val lacing = input.readArtworkExact(segmentCount) ?: return null
        scannedBytes += lacing.size
        for (laceByte in lacing) {
            val segmentLength = laceByte.toInt() and 0xff
            if (packet.size().toLong() + segmentLength > MAX_OGG_PACKET_BYTES) return null
            val segment = input.readArtworkExact(segmentLength) ?: return null
            scannedBytes += segmentLength
            if (scannedBytes > MAX_OGG_METADATA_BYTES) return null
            packet.write(segment)
            if (segmentLength < 255) {
                completedPackets++
                val bytes = packet.toByteArray()
                extractPictureFromOggCommentPacket(bytes)?.let { return it }
                packet.reset()
                if (completedPackets >= MAX_OGG_PACKETS) return null
            }
        }
    }
    return null
}

private fun extractPictureFromOggCommentPacket(packet: ByteArray): ByteArray? {
    val prefixBytes = when {
        packet.hasAsciiPrefix("OpusTags") -> 8
        packet.size >= 7 && packet[0] == 3.toByte() && packet.hasAsciiAt("vorbis", 1) -> 7
        else -> return null
    }
    var cursor = prefixBytes
    val vendorLength = readUnsignedInt(packet, cursor, littleEndian = true).takeIf { it <= Int.MAX_VALUE }?.toInt()
        ?: return null
    cursor += 4
    cursor = cursor.advanceWithin(vendorLength, packet.size) ?: return null
    val commentCount = readUnsignedInt(packet, cursor, littleEndian = true)
        .takeIf { it <= MAX_OGG_COMMENTS }?.toInt() ?: return null
    cursor += 4
    repeat(commentCount) {
        val length = readUnsignedInt(packet, cursor, littleEndian = true)
            .takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        cursor += 4
        val end = cursor.advanceWithin(length, packet.size) ?: return null
        val separator = (cursor until end).firstOrNull { packet[it] == '='.code.toByte() }
        if (separator != null && separator - cursor <= MAX_COMMENT_KEY_BYTES) {
            val key = String(packet, cursor, separator - cursor, Charsets.US_ASCII)
            if (key.equals("METADATA_BLOCK_PICTURE", ignoreCase = true) ||
                key.equals("COVERART", ignoreCase = true)
            ) {
                val encodedStart = separator + 1
                val encodedLength = end - encodedStart
                if (encodedLength <= 0 || encodedLength > MAX_BASE64_ART_BYTES) return null
                val decoded = try {
                    Base64.getDecoder().decode(packet.copyOfRange(encodedStart, end))
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (key.equals("METADATA_BLOCK_PICTURE", ignoreCase = true)) {
                    return extractFlacPictureBlock(decoded, MAX_EMBEDDED_ART_BYTES)
                }
                return decoded.takeIf { it.isNotEmpty() && it.size <= MAX_EMBEDDED_ART_BYTES }
            }
        }
        cursor = end
    }
    return null
}

private fun ByteArray.hasAsciiPrefix(value: String): Boolean = hasAsciiAt(value, 0)

private fun ByteArray.hasAsciiAt(value: String, offset: Int): Boolean {
    if (offset < 0 || offset + value.length > size) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

private fun readUnsignedInt(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
    if (offset < 0 || offset + 4 > bytes.size) return Long.MAX_VALUE
    var result = 0L
    repeat(4) { index ->
        val sourceIndex = if (littleEndian) offset + 3 - index else offset + index
        result = (result shl 8) or (bytes[sourceIndex].toLong() and 0xff)
    }
    return result
}

private fun readUnsignedLong(bytes: ByteArray): Long? {
    if (bytes.size != 8 || (bytes[0].toInt() and 0x80) != 0) return null
    var result = 0L
    bytes.forEach { byte -> result = (result shl 8) or (byte.toLong() and 0xff) }
    return result
}

private fun Int.advanceWithin(amount: Int, limit: Int): Int? {
    if (amount < 0) return null
    val result = toLong() + amount.toLong()
    return result.takeIf { it <= limit.toLong() }?.toInt()
}

private fun InputStream.readArtworkExact(size: Int): ByteArray? {
    if (size < 0) return null
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) return null
        if (read == 0) continue
        offset += read
    }
    return bytes
}

private fun InputStream.skipArtworkExactly(size: Long): Boolean {
    if (size < 0) return false
    var remaining = size
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() >= 0) {
            remaining--
        } else {
            return false
        }
    }
    return true
}

private class Mp4AtomBudget(var remaining: Int = MAX_MP4_ATOMS)

private class BoundedArtworkInputStream(
    private val delegate: InputStream,
    initialRemaining: Long,
) : InputStream() {
    private var remaining = initialRemaining.coerceAtLeast(0)

    override fun read(): Int {
        if (remaining == 0L) return -1
        return delegate.read().also { value -> if (value >= 0) remaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val boundedLength = minOf(length.toLong(), remaining).toInt()
        return delegate.read(bytes, offset, boundedLength).also { read ->
            if (read > 0) remaining -= read.toLong()
        }
    }

    override fun skip(byteCount: Long): Long {
        if (remaining == 0L) return 0
        return delegate.skip(minOf(byteCount, remaining)).also { skipped -> remaining -= skipped }
    }

    fun skipRemaining(): Boolean = skipArtworkExactly(remaining)
}

/** Removes the zero byte inserted after 0xff by ID3v2 tag-level unsynchronisation. */
private class UnsynchronisationInputStream(
    private val delegate: InputStream,
) : InputStream() {
    private var previousWasFf = false

    override fun read(): Int {
        while (true) {
            val value = delegate.read()
            if (value < 0) return -1
            if (previousWasFf && value == 0) {
                previousWasFf = false
                continue
            }
            previousWasFf = value == 0xff
            return value
        }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            val value = read()
            if (value < 0) return if (count == 0) -1 else count
            bytes[offset + count] = value.toByte()
            count++
        }
        return count
    }

    override fun skip(byteCount: Long): Long {
        var skipped = 0L
        while (skipped < byteCount && read() >= 0) skipped++
        return skipped
    }
}

private fun decodeBigEndian24(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 3 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xff) shl 16) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        (bytes[offset + 2].toInt() and 0xff)
}

private fun findId3V22PictureOffset(frame: ByteArray): Int? {
    if (frame.size < 6) return null
    val textEncoding = frame[0].toInt() and 0xff
    var cursor = 5 // encoding, three-byte image format, picture type
    if (textEncoding == 1 || textEncoding == 2) {
        while (cursor + 1 < frame.size && !(frame[cursor] == 0.toByte() && frame[cursor + 1] == 0.toByte())) {
            cursor++
        }
        if (cursor + 1 >= frame.size) return null
        cursor += 2
    } else {
        while (cursor < frame.size && frame[cursor] != 0.toByte()) cursor++
        if (cursor >= frame.size) return null
        cursor++
    }
    return cursor.takeIf { it < frame.size }
}

private const val ARTWORK_BUFFER_BYTES = 64 * 1024
private const val FORMAT_PROBE_BYTES = 12
private const val ID3_HEADER_BYTES = 10
private const val ID3_FRAME_HEADER_BYTES = 10
private const val ID3_V22_FRAME_HEADER_BYTES = 6
private const val ID3_UNSYNCHRONISATION_FLAG = 0x80
private const val ID3_EXTENDED_HEADER_FLAG = 0x40
private const val ID3_V24_FRAME_UNSYNCHRONISATION_FLAG = 0x02
private const val ID3_APIC_OVERHEAD_BYTES = 4_096L
private const val MAX_ID3_TAG_BYTES = 16 * 1024 * 1024
private const val MAX_CONTAINER_CHUNKS = 10_000
private const val MP4_ATOM_HEADER_BYTES = 8
private const val MP4_FULL_BOX_BYTES = 4L
private const val MP4_DATA_PREAMBLE_BYTES = 8L
private const val MAX_MP4_DEPTH = 8
private const val MAX_MP4_ATOMS = 20_000
private val MP4_CONTAINER_ATOMS = setOf("moov", "udta", "meta", "ilst", "covr")
private const val MAX_OGG_PAGES = 256
private const val MAX_OGG_PACKETS = 8
private const val MAX_OGG_METADATA_BYTES = 32L * 1024L * 1024L
private const val MAX_OGG_PACKET_BYTES = 16L * 1024L * 1024L
private const val MAX_OGG_COMMENTS = 100_000L
private const val MAX_COMMENT_KEY_BYTES = 64
private const val MAX_BASE64_ART_BYTES = (MAX_EMBEDDED_ART_BYTES * 4 / 3) + 32
