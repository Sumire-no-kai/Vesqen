package io.github.sumirenokai.vesqen.usb.pcm

import java.nio.ByteBuffer
import kotlin.math.floor

/** Little-endian PCM as a decoder or a WAV file hands it over. */
enum class UsbPcmEncoding(val bytesPerSample: Int) {
    INT16(2),
    INT24(3),
    INT32(4),
    FLOAT(4),
}

/**
 * Lays PCM out for the USB wire without changing a value. Every sample must lie on the lattice of the
 * source file's bit depth, whatever the decoder handed over: float is scaled by 2^(N-1) for an N-bit
 * source and must come out a whole number in range, and wider integers must have nothing below the
 * source's bits (D10). Samples then sit at the top of their subslot, so 16 bits in a 24-bit setting
 * gain zeros below them (#65). A value off the lattice stops the conversion: there is no rounding,
 * dithering or truncation here.
 */
class ExactPcmConverter(
    private val encoding: UsbPcmEncoding,
    private val channels: Int,
    /** The source file's bit depth, from its metadata, not the decoder's output. */
    private val sourceBits: Int,
    private val bitResolution: Int,
    private val subslotBytes: Int,
) {
    private val floatScale = (1L shl (sourceBits - 1)).toDouble()
    private val bitsBelowSource = (1 shl (Int.SIZE_BITS - sourceBits)) - 1
    private val subslotShift = Int.SIZE_BITS - subslotBytes * Byte.SIZE_BITS

    init {
        require(subslotBytes in 1..Int.SIZE_BYTES) { "Subslot of $subslotBytes bytes" }
        require(bitResolution in 1..subslotBytes * Byte.SIZE_BITS) { "$bitResolution bits in $subslotBytes bytes" }
        require(sourceBits in 1..bitResolution) { "A $sourceBits-bit source on a $bitResolution-bit wire" }
        require(encoding == UsbPcmEncoding.FLOAT || sourceBits <= encoding.bytesPerSample * Byte.SIZE_BITS) {
            "A $sourceBits-bit source decoded to $encoding has lost precision"
        }
    }

    /**
     * Moves as many whole frames as both buffers allow. False means a sample cannot be represented
     * exactly, and both buffers are then partly consumed, so the stream has to stop.
     */
    fun convert(source: ByteBuffer, target: ByteBuffer): Boolean {
        val samples = minOf(source.remaining() / encoding.bytesPerSample, target.remaining() / subslotBytes) / channels * channels
        repeat(samples) {
            val value: Int
            if (encoding == UsbPcmEncoding.FLOAT) {
                val scaled = Float.fromBits(readInt32(source)).toDouble() * floatScale
                // NaN fails the first comparison, and infinities fail the range.
                if (scaled != floor(scaled) || scaled < -floatScale || scaled >= floatScale) return false
                value = scaled.toInt() shl (Int.SIZE_BITS - sourceBits)
            } else {
                value = readTopAligned(source)
                if (value and bitsBelowSource != 0) return false
            }
            var wire = value ushr subslotShift
            repeat(subslotBytes) {
                target.put(wire.toByte())
                wire = wire ushr Byte.SIZE_BITS
            }
        }
        return true
    }

    private fun readTopAligned(source: ByteBuffer): Int = when (encoding) {
        UsbPcmEncoding.INT16 -> (nextByte(source) shl 16) or (nextByte(source) shl 24)
        UsbPcmEncoding.INT24 -> (nextByte(source) shl 8) or (nextByte(source) shl 16) or (nextByte(source) shl 24)
        UsbPcmEncoding.INT32, UsbPcmEncoding.FLOAT -> readInt32(source)
    }

    private fun readInt32(source: ByteBuffer): Int =
        nextByte(source) or (nextByte(source) shl 8) or (nextByte(source) shl 16) or (nextByte(source) shl 24)

    private fun nextByte(source: ByteBuffer): Int = source.get().toInt() and 0xFF
}
