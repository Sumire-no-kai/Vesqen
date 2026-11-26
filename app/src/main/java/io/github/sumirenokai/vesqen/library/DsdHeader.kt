package io.github.sumirenokai.vesqen.library

import java.io.InputStream

/** The two DSD files Vesqen plays (#249): Sony's DSF and Philips' uncompressed DSDIFF. */
internal enum class DsdContainer { DSF, DFF }

/**
 * One DSD stream as its file describes it. Samples are 1-bit; [sampleRate] is the DSD rate, such
 * as 2,822,400 Hz for DSD64.
 *
 * DSF stores [blockBytesPerChannel] bytes of one channel, then the next channel, with the earliest
 * sample in the lowest bit when [lsbFirst]. DFF interleaves one byte per channel with the earliest
 * sample in the highest bit, so its block is one byte.
 */
internal data class DsdStreamInfo(
    val container: DsdContainer,
    val sampleRate: Int,
    val channels: Int,
    val samplesPerChannel: Long,
    /** The file offset of the first sample byte. */
    val dataStart: Long,
    val blockBytesPerChannel: Int,
    val lsbFirst: Boolean,
    /** For each channel in Android's order (front, centre, LFE, back), the channel in the file. */
    val channelOrder: List<Int> = List(channels) { it },
) {
    val durationUs: Long
        get() = samplesPerChannel / sampleRate * 1_000_000 + samplesPerChannel % sampleRate * 1_000_000 / sampleRate
}

/** Why a DSD file is recognised but not played. */
internal enum class DsdUnsupported { COMPRESSED, SAMPLE_RATE, CHANNELS, LAYOUT }

internal sealed interface DsdHeaderResult {
    data class Stream(val info: DsdStreamInfo) : DsdHeaderResult

    data class Unsupported(val reason: DsdUnsupported) : DsdHeaderResult

    /** Not DSD, or damaged. */
    data object Unreadable : DsdHeaderResult
}

/** A DSD file read forwards from its start: the header reader takes what it needs and skips the rest. */
internal interface DsdHeaderSource {
    /** The next [count] bytes, or null when the file ends first. */
    fun read(count: Int): ByteArray?

    /** Skips [count] bytes; false when the file ends first. */
    fun skip(count: Long): Boolean
}

/** [input] after [consumed], the bytes a caller has already read from the start of the file. */
internal class InputStreamDsdHeaderSource(
    private val input: InputStream,
    private var consumed: ByteArray = ByteArray(0),
) : DsdHeaderSource {
    override fun read(count: Int): ByteArray? {
        if (count < 0) return null
        val head = take(count)
        if (head.size == count) return head
        return head + (input.readArtworkExact(count - head.size) ?: return null)
    }

    override fun skip(count: Long): Boolean {
        if (count < 0) return false
        val head = take(minOf(count, Int.MAX_VALUE.toLong()).toInt())
        return input.skipArtworkExactly(count - head.size)
    }

    private fun take(count: Int): ByteArray {
        val taken = minOf(count, consumed.size)
        val head = consumed.copyOf(taken)
        consumed = consumed.copyOfRange(taken, consumed.size)
        return head
    }
}

/** Whether [prefix] starts like a DSF or a DSDIFF (DSD form) file. */
internal fun looksLikeDsd(prefix: ByteArray): Boolean =
    prefix.hasAscii("DSD ", 0) || (prefix.hasAscii("FRM8", 0) && prefix.hasAscii("DSD ", 12))

/**
 * Reads the stream's description from the start of a file and leaves [source] at its first sample
 * byte. Sizes and offsets come from the file and are checked against [fileLength] when it is known.
 */
internal fun readDsdHeader(source: DsdHeaderSource, fileLength: Long?): DsdHeaderResult {
    val start = source.read(16) ?: return DsdHeaderResult.Unreadable
    return when {
        start.hasAscii("DSD ", 0) -> readDsf(start, source, fileLength)
        start.hasAscii("FRM8", 0) && start.hasAscii("DSD ", 12) -> readDff(source, fileLength)
        else -> DsdHeaderResult.Unreadable
    }
}

internal fun parseDsdHeader(file: ByteArray, fileLength: Long?): DsdHeaderResult =
    readDsdHeader(InputStreamDsdHeaderSource(file.inputStream()), fileLength)

/** DSD64 to DSD512 in the 44.1 kHz and the 48 kHz family. */
internal fun isSupportedDsdRate(rate: Int): Boolean =
    DSD_FAMILY_RATES.any { base -> (0..3).any { shift -> rate == base shl shift } }

internal const val DSD_MAX_CHANNELS = 6

/** "DSD64" and so on: the rate as a multiple of 44.1 kHz, or of 48 kHz for that family; "DSD" without one. */
internal fun dsdRateLabel(sampleRate: Long?): String {
    val rate = sampleRate?.takeIf { it > 0 } ?: return "DSD"
    val base = if (rate % 3_072_000 == 0L) 48_000 else 44_100
    return "DSD${rate / base}"
}

private val DSD_FAMILY_RATES = listOf(2_822_400, 3_072_000)

/** DSF channel type 5: front left, front right, centre and LFE. Android's four channels are front and back pairs. */
private const val DSF_FOUR_WITH_LFE = 5L

private fun readDsf(start: ByteArray, source: DsdHeaderSource, fileLength: Long?): DsdHeaderResult {
    // DSD chunk (28 bytes), fmt chunk (usually 52), data chunk header (12); little-endian throughout.
    val dsd = start + (source.read(12) ?: return DsdHeaderResult.Unreadable)
    if (dsd.le64(4) != 28L) return DsdHeaderResult.Unreadable
    val fmtHeader = source.read(12) ?: return DsdHeaderResult.Unreadable
    if (!fmtHeader.hasAscii("fmt ", 0)) return DsdHeaderResult.Unreadable
    val fmtSize = fmtHeader.le64(4)
    if (fmtSize !in 52..1024) return DsdHeaderResult.Unreadable
    val fmt = fmtHeader + (source.read(fmtSize.toInt() - 12) ?: return DsdHeaderResult.Unreadable)
    val version = fmt.le32(12)
    val formatId = fmt.le32(16)
    val channelType = fmt.le32(20)
    val channels = fmt.le32(24)
    val rate = fmt.le32(28)
    val bits = fmt.le32(32)
    val samples = fmt.le64(36)
    val block = fmt.le32(44)
    if (version != 1L || formatId != 0L) return DsdHeaderResult.Unsupported(DsdUnsupported.COMPRESSED)
    if (bits != 1L && bits != 8L) return DsdHeaderResult.Unsupported(DsdUnsupported.LAYOUT)
    if (channels !in 1..DSD_MAX_CHANNELS.toLong()) return DsdHeaderResult.Unsupported(DsdUnsupported.CHANNELS)
    // The other types list their channels in Android's order.
    if (channels == 4L && channelType == DSF_FOUR_WITH_LFE) return DsdHeaderResult.Unsupported(DsdUnsupported.LAYOUT)
    if (rate > Int.MAX_VALUE || !isSupportedDsdRate(rate.toInt())) return DsdHeaderResult.Unsupported(DsdUnsupported.SAMPLE_RATE)
    if (block !in 1..65_536L || samples <= 0) return DsdHeaderResult.Unreadable
    val data = source.read(12) ?: return DsdHeaderResult.Unreadable
    if (!data.hasAscii("data", 0)) return DsdHeaderResult.Unreadable
    val dataSize = data.le64(4) - 12
    if (dataSize <= 0) return DsdHeaderResult.Unreadable
    val dataStart = 28 + fmtSize + 12
    val groupBytes = block * channels
    // Each channel's block follows the last; the final group is padded, so the data is whole groups.
    val neededBytes = ceilDiv(ceilDiv(samples, 8), block) * groupBytes
    // A file cut short, or a data chunk shorter than its count, plays the whole groups it holds,
    // as AIFF does.
    val availableBytes = minOf(neededBytes, dataSize, fileLength?.let { it - dataStart } ?: Long.MAX_VALUE)
    val groups = availableBytes / groupBytes
    if (groups <= 0) return DsdHeaderResult.Unreadable
    return DsdHeaderResult.Stream(
        DsdStreamInfo(
            container = DsdContainer.DSF,
            sampleRate = rate.toInt(),
            channels = channels.toInt(),
            samplesPerChannel = if (availableBytes < neededBytes) groups * block * 8 else samples,
            dataStart = dataStart,
            blockBytesPerChannel = block.toInt(),
            lsbFirst = bits == 1L,
        ),
    )
}

/**
 * DSDIFF is a FRM8 form of type "DSD " with big-endian 64-bit sizes and chunks padded to an even
 * length. FVER comes first and PROP before the sound data; any other chunk may come before the
 * sound data too and is skipped, whatever its size, as the specification requires.
 */
private fun readDff(source: DsdHeaderSource, fileLength: Long?): DsdHeaderResult {
    var position = 16L
    var property: DffProperty? = null
    repeat(MAX_DFF_CHUNKS) {
        val header = source.read(12) ?: return DsdHeaderResult.Unreadable
        position += 12
        val size = header.be64(4)
        val id = String(header, 0, 4, Charsets.US_ASCII)
        // Only the sound data of a file cut short may reach past its end.
        if (size < 0 || size == Long.MAX_VALUE) return DsdHeaderResult.Unreadable
        if (id != "DSD " && fileLength != null && size > fileLength - position) return DsdHeaderResult.Unreadable
        val padded = size + (size and 1)
        when (id) {
            "PROP" -> {
                if (size > MAX_DFF_PROPERTY_BYTES) return DsdHeaderResult.Unreadable
                val body = source.read(size.toInt()) ?: return DsdHeaderResult.Unreadable
                if (padded > size && !source.skip(1)) return DsdHeaderResult.Unreadable
                property = readDffProperty(body) ?: return DsdHeaderResult.Unreadable
                if (property?.compressed == true) return DsdHeaderResult.Unsupported(DsdUnsupported.COMPRESSED)
            }
            "DST " -> return DsdHeaderResult.Unsupported(DsdUnsupported.COMPRESSED)
            "DSD " -> {
                val prop = property ?: return DsdHeaderResult.Unreadable
                val channels = prop.channelIds.size
                if (channels !in 1..DSD_MAX_CHANNELS) return DsdHeaderResult.Unsupported(DsdUnsupported.CHANNELS)
                val order = dffChannelOrder(prop.channelIds) ?: return DsdHeaderResult.Unsupported(DsdUnsupported.LAYOUT)
                if (prop.rate > Int.MAX_VALUE || !isSupportedDsdRate(prop.rate.toInt())) {
                    return DsdHeaderResult.Unsupported(DsdUnsupported.SAMPLE_RATE)
                }
                // A file cut short plays the whole frames it holds, as AIFF does.
                val frames = minOf(size, fileLength?.let { it - position } ?: Long.MAX_VALUE) / channels
                if (frames <= 0 || frames > Long.MAX_VALUE / 8) return DsdHeaderResult.Unreadable
                return DsdHeaderResult.Stream(
                    DsdStreamInfo(
                        container = DsdContainer.DFF,
                        sampleRate = prop.rate.toInt(),
                        channels = channels,
                        samplesPerChannel = frames * 8,
                        dataStart = position,
                        blockBytesPerChannel = 1,
                        lsbFirst = false,
                        channelOrder = order,
                    ),
                )
            }
            else -> if (!source.skip(padded)) return DsdHeaderResult.Unreadable
        }
        position += padded
    }
    return DsdHeaderResult.Unreadable
}

private class DffProperty(val rate: Long, val channelIds: List<String>, val compressed: Boolean)

/** The sound property chunk; null when it is not one or is damaged. */
private fun readDffProperty(body: ByteArray): DffProperty? {
    if (!body.hasAscii("SND ", 0)) return null
    var rate = 0L
    var ids = emptyList<String>()
    var compressed = false
    var sub = 4
    while (sub + 12 <= body.size) {
        val subSize = body.be64(sub + 4)
        val subBody = sub + 12
        if (subSize < 0 || subBody + subSize > body.size) return null
        when (String(body, sub, 4, Charsets.US_ASCII)) {
            "FS  " -> if (subSize >= 4) rate = body.be32(subBody)
            "CHNL" -> if (subSize >= 2) {
                val count = body.be16(subBody)
                // An ID missing from a short chunk names no speaker, like a numbered one.
                ids = List(count) { index ->
                    val at = subBody + 2 + index * 4
                    if (at + 4 <= subBody + subSize) String(body, at, 4, Charsets.US_ASCII) else ""
                }
            }
            "CMPR" -> if (subSize >= 4) compressed = !body.hasAscii("DSD ", subBody)
        }
        sub = subBody + (subSize + (subSize and 1)).toInt()
    }
    return DffProperty(rate, ids, compressed)
}

private enum class Speaker { LEFT, RIGHT, CENTRE, LFE, LEFT_SURROUND, RIGHT_SURROUND }

private fun speakerFor(id: String): Speaker? = when (id) {
    "SLFT", "MLFT" -> Speaker.LEFT
    "SRGT", "MRGT" -> Speaker.RIGHT
    "C   " -> Speaker.CENTRE
    "LFE " -> Speaker.LFE
    "LS  " -> Speaker.LEFT_SURROUND
    "RS  " -> Speaker.RIGHT_SURROUND
    // 'C000' to 'C999' are numbered channels with no fixed speaker.
    else -> null
}

/** The speakers Android plays for each channel count, in its order (Media3's default channel masks). */
private val ANDROID_LAYOUTS = mapOf(
    2 to listOf(Speaker.LEFT, Speaker.RIGHT),
    3 to listOf(Speaker.LEFT, Speaker.RIGHT, Speaker.CENTRE),
    4 to listOf(Speaker.LEFT, Speaker.RIGHT, Speaker.LEFT_SURROUND, Speaker.RIGHT_SURROUND),
    5 to listOf(Speaker.LEFT, Speaker.RIGHT, Speaker.CENTRE, Speaker.LEFT_SURROUND, Speaker.RIGHT_SURROUND),
    6 to listOf(Speaker.LEFT, Speaker.RIGHT, Speaker.CENTRE, Speaker.LFE, Speaker.LEFT_SURROUND, Speaker.RIGHT_SURROUND),
)

/**
 * The CHNL chunk lists the channels in the order of their bytes. Returns, for each channel in
 * Android's order, the channel in the file, or null when the speakers are not a layout Android
 * plays for that count.
 */
private fun dffChannelOrder(ids: List<String>): List<Int>? {
    val speakers = ids.map(::speakerFor)
    // Numbered channels name no speaker; mono and stereo play them in the file's order.
    if (ids.size <= 2 && speakers.all { it == null }) return ids.indices.toList()
    if (ids.size == 1) return listOf(0)
    val layout = ANDROID_LAYOUTS[ids.size] ?: return null
    if (speakers.toSet() != layout.toSet() || speakers.distinct().size != speakers.size) return null
    return layout.map(speakers::indexOf)
}

/** Far more than a FRM8 form holds before its sound data. */
private const val MAX_DFF_CHUNKS = 256

/** A PROP chunk holds a few short properties; anything larger is not one. */
private const val MAX_DFF_PROPERTY_BYTES = 64 * 1024L

private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

private fun ByteArray.hasAscii(text: String, at: Int): Boolean =
    at + text.length <= size && text.indices.all { this[at + it] == text[it].code.toByte() }

private fun ByteArray.le32(at: Int): Long = (0..3).fold(0L) { n, i -> n or ((this[at + i].toLong() and 255) shl (8 * i)) }

private fun ByteArray.le64(at: Int): Long = (0..7).fold(0L) { n, i -> n or ((this[at + i].toLong() and 255) shl (8 * i)) }

private fun ByteArray.be16(at: Int): Int = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)

private fun ByteArray.be32(at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (this[at + i].toLong() and 255) }

private fun ByteArray.be64(at: Int): Long = (0..7).fold(0L) { n, i -> (n shl 8) or (this[at + i].toLong() and 255) }

/** What the artwork, lyrics and tag readers' 12-byte probe can tell: a DSF or a DSDIFF start. */
internal fun startsLikeDsd(prefix: ByteArray): Boolean = prefix.hasAscii("DSD ", 0) || prefix.hasAscii("FRM8", 0)

/**
 * Moves [this], at the start of a DSF or DFF file, to the start of its ID3v2 tag and says whether
 * there is one. DSF points to it from its header. DFF has no standard tag, but common tools write
 * an "ID3 " chunk, usually after the audio, which is skipped rather than read.
 */
internal fun InputStream.skipToDsdId3(declaredLength: Long?): Boolean {
    val start = readArtworkExact(16) ?: return false
    if (start.hasAscii("DSD ", 0)) {
        val rest = readArtworkExact(12) ?: return false
        val pointer = (start + rest).le64(20)
        if (pointer < 28 || (declaredLength != null && pointer >= declaredLength)) return false
        return skipArtworkExactly(pointer - 28)
    }
    if (!start.hasAscii("FRM8", 0) || !start.hasAscii("DSD ", 12)) return false
    var position = 16L
    repeat(MAX_DFF_CHUNKS) {
        val header = readArtworkExact(12) ?: return false
        position += 12
        val size = header.be64(4)
        if (size < 0 || (declaredLength != null && position + size > declaredLength)) return false
        // Writers differ in the ID's case, as they do for AIFF's ID3 chunk.
        if (String(header, 0, 4, Charsets.US_ASCII).equals("ID3 ", ignoreCase = true)) return true
        val next = size + (size and 1)
        if (!skipArtworkExactly(next)) return false
        position += next
    }
    return false
}
