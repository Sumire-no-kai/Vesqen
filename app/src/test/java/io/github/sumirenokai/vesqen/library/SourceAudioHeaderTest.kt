package io.github.sumirenokai.vesqen.library

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SourceAudioHeaderTest {
    @Test fun flacUsesSourcePrecisionInsteadOfDecoderPcm() {
        for (bits in listOf(16, 24, 32)) {
            val info = ByteArray(34)
            val packed = (96_000L shl 44) or (1L shl 41) or ((bits - 1L) shl 36) or 96_000L
            ByteBuffer.wrap(info, 10, 8).putLong(packed)
            assertEquals(SourceAudioHeader(96_000, 2, bits), read("fLaC".toByteArray() + byteArrayOf(-128, 0, 0, 34) + info))
        }
    }

    @Test fun wavHandlesPaddedMetadataAnd24BitPcm() {
        val fmt = pcmFormat(1, 24)
        assertEquals(SourceAudioHeader(48_000, 2, 24), read(wav(chunk("JUNK", byteArrayOf(7)) + chunk("fmt ", fmt))))
    }

    @Test fun extensibleWavUsesValidBits() {
        val fmt = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .put(pcmFormat(0xfffe, 32)).putShort(22).putShort(24).putInt(3)
            .put(byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)).array()
        assertEquals(SourceAudioHeader(48_000, 2, 24), read(wav(chunk("fmt ", fmt))))
        fmt[30] = 9
        assertNull(read(wav(chunk("fmt ", fmt))))
    }

    @Test fun malformedTruncatedAndCompressedWavAreUnknown() {
        assertNull(read("fLaC".toByteArray() + byteArrayOf(0, 127, 0, 34)))
        val valid = wav(chunk("fmt ", pcmFormat(1, 24)))
        for (length in 0 until valid.size) assertNull(read(valid.copyOf(length)))
        assertNull(read(wav(chunk("fmt ", pcmFormat(6, 8)))))
    }

    @Test fun oversizedChunkStopsWithoutReadingPayload() {
        val bytes = wav("JUNK".toByteArray() + byteArrayOf(-1, -1, -1, 127))
        assertNull(read(bytes))
        assertNull(read(wav(chunk("data", ByteArray(16)) + chunk("fmt ", pcmFormat(1, 16)))))
    }

    @Test fun shortReadsAndNonSkippingStreamsRemainCorrect() {
        val bytes = wav(chunk("JUNK", byteArrayOf(7)) + chunk("fmt ", pcmFormat(1, 24)))
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
            override fun skip(n: Long): Long = 0
        }
        assertEquals(SourceAudioHeader(48_000, 2, 24), readSourceAudioHeader(input))
    }

    @Test fun aiffReadsItsFactsAndDurationFromBigEndianChunks() {
        val aiff = form("AIFF", beChunk("NAME", byteArrayOf(65)) + beChunk("COMM", comm(24, 96_000, 192_000)))
        assertEquals(SourceAudioHeader(96_000, 2, 24, durationMs = 2_000), read(aiff))
        val aifc = form("AIFC", beChunk("COMM", comm(16, 44_100, 0) + "sowt".toByteArray()))
        assertEquals(SourceAudioHeader(44_100, 2, 16), read(aifc))
    }

    @Test fun aiffVesqenCannotPlayOrCutFilesAreUnknown() {
        assertNull(read(form("AIFC", beChunk("COMM", comm(32, 48_000, 10) + "fl32".toByteArray()))))
        assertNull(read(form("AIFF", beChunk("COMM", comm(8, 48_000, 10)))))
        assertNull(read(form("AIFF", beChunk("COMM", ByteArray(4)))))
        assertNull(read(form("AIFF", beChunk("SSND", ByteArray(16)) + beChunk("COMM", comm(16, 48_000, 10)))))
        val valid = form("AIFF", beChunk("COMM", comm(16, 48_000, 10)))
        for (length in 0 until valid.size) assertNull(read(valid.copyOf(length)))
    }

    private fun comm(bits: Int, rate: Int, frames: Int): ByteArray {
        val exponent = 31 - Integer.numberOfLeadingZeros(rate)
        return ByteBuffer.allocate(18).putShort(2).putInt(frames).putShort(bits.toShort())
            .putShort((16383 + exponent).toShort()).putLong(rate.toLong() shl (63 - exponent)).array()
    }
    private fun beChunk(id: String, bytes: ByteArray) =
        id.toByteArray() + ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes + ByteArray(bytes.size % 2)
    private fun form(type: String, chunks: ByteArray) =
        "FORM".toByteArray() + ByteBuffer.allocate(4).putInt(chunks.size + 4).array() + type.toByteArray() + chunks
    private fun read(bytes: ByteArray) = readSourceAudioHeader(bytes.inputStream())
    private fun pcmFormat(code: Int, bits: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(code.toShort()).putShort(2).putInt(48_000).putInt(48_000 * 2 * bits / 8)
        .putShort((2 * bits / 8).toShort()).putShort(bits.toShort()).array()
    private fun chunk(id: String, bytes: ByteArray) = id.toByteArray() + le(bytes.size) + bytes + ByteArray(bytes.size % 2)
    private fun wav(chunks: ByteArray) = "RIFF".toByteArray() + le(chunks.size + 4) + "WAVE".toByteArray() + chunks
    private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
}
