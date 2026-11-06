package io.github.sumirenokai.vesqen.library

/** The PCM an AIFF `COMM` chunk describes. Each sample fills [bytesPerSample] bytes. */
internal data class AiffPcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val frames: Long,
    val littleEndian: Boolean,
) {
    val bytesPerSample: Int get() = (bitDepth + 7) / 8
    val bytesPerFrame: Int get() = channels * bytesPerSample
}

/** Plain AIFF ends at 18 bytes; AIFF-C adds a compression type, then a name nothing here reads. */
internal const val AIFF_COMM_MIN_SIZE = 18
internal const val AIFF_COMM_READ_SIZE = 22

/**
 * Reads the start of a `COMM` chunk, or null for audio Vesqen does not play: 8-bit, floating-point,
 * A-law or µ-law, and values no real file has. AIFF stores 8-bit audio signed, unlike every other
 * PCM path here.
 */
internal fun parseAiffComm(body: ByteArray, aifc: Boolean): AiffPcmFormat? {
    if (body.size < AIFF_COMM_MIN_SIZE) return null
    fun u8(at: Int) = body[at].toInt() and 255
    fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    val channels = u16(0)
    val frames = (u16(2).toLong() shl 16) or u16(4).toLong()
    val bitDepth = u16(6)
    val littleEndian = if (aifc) {
        if (body.size < AIFF_COMM_READ_SIZE) return null
        when (String(body, AIFF_COMM_MIN_SIZE, 4, Charsets.US_ASCII)) {
            "NONE", "twos", "in24", "in32" -> false
            "sowt" -> true
            else -> return null
        }
    } else {
        false
    }
    // An 80-bit extended float: 2^power with the binary point after the mantissa's top bit. The sign
    // bit makes the power out of range. Some old rates are fractions, such as 22254.54 Hz.
    val power = u16(8) - 16383
    if (power !in 0..19) return null
    val mantissa = (10..17).fold(0L) { value, at -> (value shl 8) or u8(at).toLong() }
    val sampleRate = (((mantissa ushr (62 - power)) + 1) ushr 1).toInt()
    if (sampleRate !in 1..768_000 || channels !in 1..32 || bitDepth !in 9..32) return null
    return AiffPcmFormat(sampleRate, channels, bitDepth, frames, littleEndian)
}
