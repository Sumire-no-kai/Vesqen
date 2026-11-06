package io.github.sumirenokai.vesqen.usb.pcm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactPcmConverterTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach(::putFloat)
            flip()
        }

    private fun convert(converter: ExactPcmConverter, source: ByteBuffer, capacity: Int = 64): Pair<Boolean, ByteArray> {
        val target = ByteBuffer.allocate(capacity)
        val ok = converter.convert(source, target)
        return ok to target.array().copyOf(target.position())
    }

    @Test
    fun `16 bits on a 16-bit wire stay as they are`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2),
            ByteBuffer.wrap(bytes(0x34, 0x12, 0xCD, 0xAB)),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x34, 0x12, 0xCD, 0xAB), wire)
    }

    @Test
    fun `16 bits on a 24-bit wire gain zeros below them`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 24, subslotBytes = 3),
            ByteBuffer.wrap(bytes(0x34, 0x12, 0xCD, 0xAB)),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x34, 0x12, 0x00, 0xCD, 0xAB), wire)
    }

    @Test
    fun `24 bits on a 24-bit wire stay as they are`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.INT24, 2, sourceBits = 24, bitResolution = 24, subslotBytes = 3),
            ByteBuffer.wrap(bytes(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE)),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE), wire)
    }

    @Test
    fun `24 bits in a 32-bit subslot sit at the top`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.INT24, 2, sourceBits = 24, bitResolution = 24, subslotBytes = 4),
            ByteBuffer.wrap(bytes(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE)),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x56, 0x34, 0x12, 0x00, 0xBA, 0xDC, 0xFE), wire)
    }

    @Test
    fun `32 bits holding 24 reach a 24-bit wire, and anything lower does not`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.INT32, 2, sourceBits = 24, bitResolution = 24, subslotBytes = 3)
        val (ok, wire) = convert(converter, ByteBuffer.wrap(bytes(0x00, 0x56, 0x34, 0x12, 0x00, 0x01, 0x00, 0x00)))
        assertTrue(ok)
        assertArrayEquals(bytes(0x56, 0x34, 0x12, 0x01, 0x00, 0x00), wire)
        assertFalse(convert(converter, ByteBuffer.wrap(bytes(0x01, 0x56, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00))).first)
    }

    @Test
    fun `a 16-bit source in 24-bit samples reaches a 16-bit wire only when the low byte is empty`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.INT24, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2)
        val (ok, wire) = convert(converter, ByteBuffer.wrap(bytes(0x00, 0x34, 0x12, 0x00, 0xCD, 0xAB)))
        assertTrue(ok)
        assertArrayEquals(bytes(0x34, 0x12, 0xCD, 0xAB), wire)
        assertFalse(convert(converter, ByteBuffer.wrap(bytes(0x01, 0x34, 0x12, 0x00, 0xCD, 0xAB))).first)
    }

    @Test
    fun `float on the 16-bit lattice comes out as those integers, ends of the range included`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2),
            floats(-1f, 32767f / 32768f, 1f / 32768f, -0f),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x80, 0xFF, 0x7F, 0x01, 0x00, 0x00, 0x00), wire)
    }

    @Test
    fun `float on the 24-bit lattice comes out as those integers`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 24, bitResolution = 24, subslotBytes = 3),
            floats(-1f, 8_388_607f / 8_388_608f),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x00, 0x80, 0xFF, 0xFF, 0x7F), wire)
    }

    @Test
    fun `float that is not on the lattice is refused`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2)
        assertFalse(convert(converter, floats(1f / 65536f, 0f)).first)
    }

    @Test
    fun `float outside the range is refused`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2)
        assertFalse("Full scale has no positive integer", convert(converter, floats(1f, 0f)).first)
        assertFalse(convert(converter, floats(-1f - 1f / 32768f, 0f)).first)
    }

    @Test
    fun `values that are not numbers are refused`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2)
        assertFalse(convert(converter, floats(Float.NaN, 0f)).first)
        assertFalse(convert(converter, floats(Float.POSITIVE_INFINITY, 0f)).first)
        assertFalse(convert(converter, floats(Float.NEGATIVE_INFINITY, 0f)).first)
    }

    @Test
    fun `only whole frames move, so a short target leaves the rest in the source`() {
        val source = ByteBuffer.wrap(ByteArray(12))
        val (ok, wire) = convert(ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2), source, capacity = 7)
        assertTrue(ok)
        assertEquals(4, wire.size)
        assertEquals(4, source.position())
    }

    @Test
    fun `a partial trailing frame stays in the source`() {
        val source = ByteBuffer.wrap(ByteArray(6))
        val (ok, wire) = convert(ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2), source)
        assertTrue(ok)
        assertEquals(4, wire.size)
        assertEquals(2, source.remaining())
    }

    @Test
    fun `the source's own byte order does not matter`() {
        val source = ByteBuffer.wrap(bytes(0x34, 0x12, 0xCD, 0xAB)).order(ByteOrder.BIG_ENDIAN)
        val (_, wire) = convert(ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 16, subslotBytes = 2), source)
        assertArrayEquals(bytes(0x34, 0x12, 0xCD, 0xAB), wire)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a resolution larger than the subslot is rejected`() {
        ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 16, bitResolution = 24, subslotBytes = 2)
    }
    @Test
    fun `float on the wire's lattice but not the source's is refused`() {
        // 16-bit source on a 24-bit wire: half a 16-bit step is a whole 24-bit value, but not the source's.
        val converter = ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 24, subslotBytes = 3)
        assertFalse(converter.convert(floats(0.5f / 32768f, 0f), ByteBuffer.allocate(64)))
    }

    @Test
    fun `float on a 16-bit source's lattice reaches a 24-bit wire with zeros below`() {
        val (ok, wire) = convert(
            ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 16, bitResolution = 24, subslotBytes = 3),
            floats(1f / 32768f, -1f),
        )
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x80), wire)
    }

    @Test
    fun `24-bit samples carrying a 16-bit source must leave the low byte empty even on a 24-bit wire`() {
        val converter = ExactPcmConverter(UsbPcmEncoding.INT24, 2, sourceBits = 16, bitResolution = 24, subslotBytes = 3)
        assertFalse(converter.convert(ByteBuffer.wrap(bytes(0x01, 0x34, 0x12, 0x00, 0xCD, 0xAB)), ByteBuffer.allocate(64)))
        val (ok, wire) = convert(converter, ByteBuffer.wrap(bytes(0x00, 0x34, 0x12, 0x00, 0xCD, 0xAB)))
        assertTrue(ok)
        assertArrayEquals(bytes(0x00, 0x34, 0x12, 0x00, 0xCD, 0xAB), wire)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a source deeper than the wire is rejected`() {
        ExactPcmConverter(UsbPcmEncoding.FLOAT, 2, sourceBits = 24, bitResolution = 16, subslotBytes = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a source deeper than its decoded samples has lost precision`() {
        ExactPcmConverter(UsbPcmEncoding.INT16, 2, sourceBits = 24, bitResolution = 24, subslotBytes = 3)
    }
}
