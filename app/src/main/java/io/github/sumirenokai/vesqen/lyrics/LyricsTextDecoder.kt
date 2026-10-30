package io.github.sumirenokai.vesqen.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a lyrics file without guessing (#169, owner decision 9). A byte order mark decides first:
 * UTF-8 and UTF-16 are read, UTF-32 is refused. Then strict UTF-8, then the legacy Chinese and
 * Japanese encodings, accepted only when one of them clearly fits better than the others. Anything
 * else is unreadable: null means "show that the lyrics cannot be read", never mojibake.
 */
internal fun decodeLyricsText(bytes: ByteArray): String? {
    if (bytes.size > MAX_LYRICS_TEXT_BYTES) return null
    // A UTF-32LE mark starts with the UTF-16LE one, so UTF-32 is checked first.
    val text = when {
        bytes.startsWith(UTF32_LE_BOM) || bytes.startsWith(UTF32_BE_BOM) -> null
        bytes.startsWith(UTF8_BOM) -> decodeStrictly(bytes, Charsets.UTF_8, UTF8_BOM.size)
        bytes.startsWith(UTF16_LE_BOM) -> decodeStrictly(bytes, Charsets.UTF_16LE, UTF16_LE_BOM.size)
        bytes.startsWith(UTF16_BE_BOM) -> decodeStrictly(bytes, Charsets.UTF_16BE, UTF16_BE_BOM.size)
        else -> decodeStrictly(bytes, Charsets.UTF_8) ?: (readLegacyCjk(bytes) as? LegacyCjkReading.Text)?.text
    }
    return text?.plausibleLyricsOrNull()
}

/** Decodes text whose container declares [charset], such as an MP4 atom or an ID3 frame. */
internal fun decodeLyricsText(bytes: ByteArray, charset: Charset): String? {
    if (bytes.size > MAX_LYRICS_TEXT_BYTES) return null
    return decodeStrictly(bytes, charset)?.plausibleLyricsOrNull()
}

/** ID3 encoding 1: UTF-16 that must start with its byte order mark. */
internal fun decodeMarkedUtf16LyricsText(bytes: ByteArray): String? = when {
    bytes.isEmpty() -> ""
    bytes.startsWith(UTF16_LE_BOM) -> decodeLyricsText(bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size), Charsets.UTF_16LE)
    bytes.startsWith(UTF16_BE_BOM) -> decodeLyricsText(bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size), Charsets.UTF_16BE)
    else -> null
}

/**
 * ID3 encoding 0 declares ISO-8859-1, but taggers often store UTF-8 or a Chinese or Japanese code
 * page there. Those are tried first (owner decision 9). Text that reads as East Asian without a
 * clear encoding is unreadable; anything else is read as Western European, which never fails.
 */
internal fun decodeLatinLabelledLyricsText(bytes: ByteArray): String? {
    if (bytes.size > MAX_LYRICS_TEXT_BYTES) return null
    val text = decodeStrictly(bytes, Charsets.UTF_8) ?: when (val legacy = readLegacyCjk(bytes)) {
        is LegacyCjkReading.Text -> legacy.text
        LegacyCjkReading.Ambiguous -> null
        LegacyCjkReading.None -> WINDOWS_1252?.let { decodeStrictly(bytes, it) } ?: String(bytes, Charsets.ISO_8859_1)
    }
    return text?.plausibleLyricsOrNull()
}

private sealed interface LegacyCjkReading {
    class Text(val text: String) : LegacyCjkReading

    /** Reads as East Asian text, but not clearly as one of the encodings Now shows. */
    data object Ambiguous : LegacyCjkReading

    data object None : LegacyCjkReading
}

private fun readLegacyCjk(bytes: ByteArray): LegacyCjkReading {
    val readings = LegacyCjkEncoding.entries.mapNotNull { encoding ->
        val charset = encoding.charset ?: return@mapNotNull null
        val text = decodeStrictly(bytes, charset) ?: return@mapNotNull null
        LegacyReading(encoding, text, encoding.countCharacters(bytes)).takeIf { encoding.plausible(it.count) }
    }
    val best = readings.maxByOrNull { it.count.share } ?: return LegacyCjkReading.None
    if (best.count.counted < MIN_LEGACY_CHARACTERS || best.count.share < MIN_LEGACY_SHARE) {
        // Mostly common characters, yet too few or too mixed to name the encoding: still East Asian
        // text, which a Western reading would turn into mojibake (#191 B1). Western text that decodes
        // at all reads as rare characters, or as a few among many Latin letters: in all-caps
        // "CORAÇÃO", "ÇÃ" is one common GB18030 hanzi.
        val eastAsian = best.count.share >= MIN_EAST_ASIAN_SHARE && best.count.counted > best.text.asciiLetters()
        return if (eastAsian) LegacyCjkReading.Ambiguous else LegacyCjkReading.None
    }
    val runnerUpShare = readings.filter { it !== best }.maxOfOrNull { it.count.share } ?: 0.0
    return if (best.encoding.shown && best.count.share - runnerUpShare >= MIN_LEGACY_MARGIN) {
        LegacyCjkReading.Text(best.text)
    } else {
        LegacyCjkReading.Ambiguous
    }
}

private class LegacyReading(val encoding: LegacyCjkEncoding, val text: String, val count: CharacterCount)

private fun String.asciiLetters(): Int = count { it in 'a'..'z' || it in 'A'..'Z' }

/**
 * Non-ASCII characters of a byte sequence read in one encoding, and how many of them fall in that
 * encoding's most frequent block. Punctuation rows count as neither, since these encodings put
 * their punctuation in byte ranges the others read as common characters.
 */
private class CharacterCount(
    val counted: Int,
    val frequent: Int,
    /** Characters only this encoding's script has, such as kana; see [LegacyCjkEncoding.plausible]. */
    val marked: Int,
) {
    val share: Double get() = if (counted == 0) 0.0 else frequent.toDouble() / counted
}

/**
 * The encodings Now reads, plus Korean and EUC-JP as rivals that are never shown: the PRD reads
 * only the first three. Korean text reads cleanly as GB18030 common hanzi and EUC-JP Japanese as
 * Big5, so without the rivals both would show as the wrong script; with them they tie.
 */
private enum class LegacyCjkEncoding(val shown: Boolean, private vararg val names: String) {
    /** GB2312 level-1 hanzi: rows B0–D7. */
    Gb18030(shown = true, "GB18030") {
        override fun countCharacters(bytes: ByteArray): CharacterCount =
            countMultiBytes(bytes, isSingleByte = { false }) { lead, trail ->
                when {
                    trail in 0x30..0x39 -> Weight.CountedFourBytes
                    lead in 0xA1..0xA3 && trail >= 0xA1 -> Weight.Neutral
                    lead in 0xB0..0xD7 && trail in 0xA1..0xFE -> Weight.Frequent
                    else -> Weight.Counted
                }
            }
    },

    /** Big5 frequent hanzi: A440–C67E. */
    Big5(shown = true, "Big5") {
        override fun countCharacters(bytes: ByteArray): CharacterCount =
            countMultiBytes(bytes, isSingleByte = { false }) { lead, trail ->
                val code = (lead shl 8) or trail
                when {
                    lead in 0xA1..0xA3 -> Weight.Neutral
                    code in 0xA440..0xC67E -> Weight.Frequent
                    else -> Weight.Counted
                }
            }
    },

    /** Kana and JIS level-1 kanji. windows-31j is the Windows form of Shift_JIS. */
    ShiftJis(shown = true, "windows-31j", "Shift_JIS") {
        override fun countCharacters(bytes: ByteArray): CharacterCount =
            countMultiBytes(bytes, isSingleByte = { it !in 0x81..0x9F && it !in 0xE0..0xFC }) { lead, trail ->
                val code = (lead shl 8) or trail
                when {
                    lead == 0x81 || (lead == 0x82 && trail < 0x9F) -> Weight.Neutral
                    code in 0x829F..0x82F1 || code in 0x8340..0x8396 || code in 0x889F..0x9872 -> Weight.Frequent
                    else -> Weight.Counted
                }
            }
    },

    /** Hangul syllables of KS X 1001: rows B0–C8. windows-949 adds the rarer syllables. */
    Korean(shown = false, "x-windows-949", "windows-949", "EUC-KR") {
        override fun countCharacters(bytes: ByteArray): CharacterCount =
            countMultiBytes(bytes, isSingleByte = { false }) { lead, trail ->
                when {
                    lead in 0xA1..0xA3 && trail >= 0xA1 -> Weight.Neutral
                    lead in 0xB0..0xC8 && trail >= 0xA1 -> Weight.Frequent
                    else -> Weight.Counted
                }
            }
    },

    /**
     * Kana rows A4–A5 and JIS level-1 kanji B0A1–CFD3. GB2312 puts its level-1 hanzi in the same
     * rows as these kanji, so Chinese text alone would read as fairly common Japanese; Japanese
     * always has kana, Chinese text practically never.
     */
    EucJp(shown = false, "EUC-JP") {
        override fun countCharacters(bytes: ByteArray): CharacterCount =
            countMultiBytes(bytes, isSingleByte = { false }) { lead, trail ->
                val code = (lead shl 8) or trail
                when {
                    lead == 0x8F -> Weight.CountedThreeBytes
                    lead in 0xA1..0xA3 -> Weight.Neutral
                    lead == 0xA4 || lead == 0xA5 -> Weight.FrequentMarked
                    code in 0xB0A1..0xCFD3 -> Weight.Frequent
                    else -> Weight.Counted
                }
            }

        override fun plausible(count: CharacterCount): Boolean = count.marked * MIN_KANA_SHARE_DIVISOR >= count.counted
    };

    val charset: Charset? by lazy { names.firstNotNullOfOrNull(::supportedCharsetOrNull) }

    abstract fun countCharacters(bytes: ByteArray): CharacterCount

    /** Whether this reading takes part at all. */
    open fun plausible(count: CharacterCount): Boolean = true
}

private enum class Weight(val width: Int) {
    Neutral(2),
    Counted(2),
    Frequent(2),
    FrequentMarked(2),
    CountedThreeBytes(3),
    CountedFourBytes(4),
}

/**
 * Walks bytes the way a multi-byte encoding reads them. Only called after a strict decode
 * succeeded, so a lead byte always has its trail.
 */
private inline fun countMultiBytes(
    bytes: ByteArray,
    isSingleByte: (Int) -> Boolean,
    weigh: (lead: Int, trail: Int) -> Weight,
): CharacterCount {
    var counted = 0
    var frequent = 0
    var marked = 0
    var index = 0
    while (index < bytes.size) {
        val lead = bytes[index].toInt() and 0xff
        if (lead < 0x80) {
            index++
            continue
        }
        if (isSingleByte(lead) || index + 1 >= bytes.size) {
            counted++
            index++
            continue
        }
        val weight = weigh(lead, bytes[index + 1].toInt() and 0xff)
        if (weight != Weight.Neutral) counted++
        if (weight == Weight.Frequent || weight == Weight.FrequentMarked) frequent++
        if (weight == Weight.FrequentMarked) marked++
        index += weight.width
    }
    return CharacterCount(counted, frequent, marked)
}

private fun decodeStrictly(bytes: ByteArray, charset: Charset, offset: Int = 0): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/** Refuses decoded text that is binary data or a wrong reading rather than lyrics. */
private fun String.plausibleLyricsOrNull(): String? {
    // Tags pad with NULs; old text editors end files with SUB (0x1A).
    val text = trimEnd('\u0000', '\u001A')
    var strayControls = 0
    for (char in text) {
        when {
            char == '\u0000' || char == '\uFFFD' -> return null
            char < ' ' && char != '\t' && char != '\n' && char != '\r' -> strayControls++
        }
    }
    return text.takeIf { strayControls <= maxOf(MAX_STRAY_CONTROLS, text.length / 100) }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun supportedCharsetOrNull(name: String): Charset? =
    if (Charset.isSupported(name)) Charset.forName(name) else null

private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Larger files are refused before decoding; lyrics are a few kilobytes. */
internal const val MAX_LYRICS_TEXT_BYTES = 512 * 1024

private const val MIN_LEGACY_CHARACTERS = 6
private const val MIN_LEGACY_SHARE = 0.8
private const val MIN_LEGACY_MARGIN = 0.2
private const val MIN_EAST_ASIAN_SHARE = 0.5
private const val MAX_STRAY_CONTROLS = 2

/** EUC-JP competes only when at least a tenth of its characters are kana. */
private const val MIN_KANA_SHARE_DIVISOR = 10
private val UTF8_BOM = bytesOf(0xEF, 0xBB, 0xBF)
private val UTF16_LE_BOM = bytesOf(0xFF, 0xFE)
private val UTF16_BE_BOM = bytesOf(0xFE, 0xFF)
private val UTF32_LE_BOM = bytesOf(0xFF, 0xFE, 0x00, 0x00)
private val UTF32_BE_BOM = bytesOf(0x00, 0x00, 0xFE, 0xFF)
private val WINDOWS_1252: Charset? = supportedCharsetOrNull("windows-1252")
