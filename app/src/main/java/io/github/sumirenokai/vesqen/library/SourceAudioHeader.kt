package io.github.sumirenokai.vesqen.library

import java.io.InputStream

/** Container facts, never the PCM format selected by a platform decoder. */
internal data class SourceAudioHeader(val sampleRate: Int, val channels: Int, val bitDepth: Int)

/** Fixed-size reads and a 1 MiB traversal limit; no audio or artwork payload is allocated. */
internal fun readSourceAudioHeader(input: InputStream): SourceAudioHeader? {
    fun exact(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count <= 0) return null
            offset += count
        }
        return bytes
    }
    fun ByteArray.u16(at: Int) = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    fun ByteArray.u32(at: Int): Long = (0..3).fold(0L) { n, i -> n or ((this[at + i].toLong() and 255) shl (8 * i)) }
    fun facts(rate: Int, channels: Int, bits: Int): SourceAudioHeader? =
        if (rate in 1..768_000 && channels in 1..32 && bits in 4..32) SourceAudioHeader(rate, channels, bits) else null
    val signature = exact(4)?.toString(Charsets.US_ASCII) ?: return null
    if (signature == "fLaC") {
        val block = exact(4) ?: return null
        if ((block[0].toInt() and 127) != 0 || block[1] != 0.toByte() || block[2] != 0.toByte() || block[3] != 34.toByte()) return null
        val info = exact(34) ?: return null
        val packed = (10..17).fold(0L) { n, i -> (n shl 8) or (info[i].toLong() and 255) }
        return facts((packed ushr 44).toInt(), ((packed ushr 41) and 7).toInt() + 1, ((packed ushr 36) and 31).toInt() + 1)
    }
    if (signature != "RIFF") return null
    val riff = exact(8) ?: return null
    if (String(riff, 4, 4, Charsets.US_ASCII) != "WAVE") return null
    var remaining = minOf(riff.u32(0) - 4, 1_048_576L)
    while (remaining >= 8) {
        val chunk = exact(8) ?: return null
        remaining -= 8
        val size = chunk.u32(4)
        if (size > remaining) return null
        when (String(chunk, 0, 4, Charsets.US_ASCII)) {
            "fmt " -> {
                if (size < 16) return null
                val fmt = exact(minOf(size, 40).toInt()) ?: return null
                val code = fmt.u16(0)
                val bits = when (code) {
                    1, 3 -> fmt.u16(14)
                    0xfffe -> {
                        // Extensible PCM/float: valid bits can differ from storage width.
                        if (fmt.size < 40 || fmt.u16(16) < 22 || fmt.u16(24) !in listOf(1, 3)) return null
                        val suffix = byteArrayOf(0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
                        if (!fmt.copyOfRange(26, 40).contentEquals(suffix)) return null
                        fmt.u16(18).takeIf { it in 1..fmt.u16(14) } ?: return null
                    }
                    else -> return null
                }
                return facts(fmt.u32(4).toInt(), fmt.u16(2), bits)
            }
            "data" -> return null
        }
        var skip = size + size % 2
        if (skip > remaining) return null
        remaining -= skip
        while (skip > 0) {
            val count = input.skip(skip)
            if (count > 0) skip -= count else {
                if (input.read() < 0) return null
                skip--
            }
        }
    }
    return null
}
