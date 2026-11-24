package io.github.sumirenokai.vesqen.usb.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #284: UAC2 requests as Audio Device Class 2.0 section 5.2 lays them out. */
class Uac2ControlClientTest {
    private val device = FakeUac2Controls()
    private val client = Uac2ControlClient(device)

    @Test
    fun `clock rates are read as a count and then every triplet`() {
        device.rateRanges = listOf(Triple(44_100L, 44_100L, 0L), Triple(48_000L, 192_000L, 48_000L))
        assertEquals(
            listOf(UacRateRange(44_100, 44_100, 0), UacRateRange(48_000, 192_000, 48_000)),
            client.clockRates(CLOCK, AC),
        )
        assertEquals(listOf(2, 2 + 2 * 12), device.calls.filter { it.request == RANGE }.map { it.length })
        device.calls.forEach { call ->
            assertEquals(0xA1, call.requestType)
            assertEquals(0x0100, call.value) // SAM_FREQ_CONTROL, channel 0
            assertEquals(CLOCK shl 8 or AC, call.index)
        }
    }

    @Test
    fun `a rate range that cannot be used is no answer`() {
        device.rateRanges = emptyList()
        assertNull(client.clockRates(CLOCK, AC))
        device.rateRanges = listOf(Triple(96_000L, 48_000L, 0L))
        assertNull("A range ending below its start", client.clockRates(CLOCK, AC))
        device.rateRanges = listOf(Triple(0L, 0L, 0L))
        assertNull("0 Hz", client.clockRates(CLOCK, AC))
        device.rateRanges = listOf(Triple(48_000L, 48_000L, 0L))
        device.shortAnswers = true
        assertNull(client.clockRates(CLOCK, AC))
        device.shortAnswers = false
        device.countInWholeAnswer = 3
        assertNull("The count changed between the two reads", client.clockRates(CLOCK, AC))
    }

    @Test
    fun `a resolution of zero offers the minimum alone`() {
        val range = UacRateRange(44_100, 48_000, 0)
        assertTrue(44_100 in range)
        assertFalse(48_000 in range)
        val stepped = UacRateRange(48_000, 192_000, 48_000)
        assertTrue(144_000 in stepped)
        assertFalse(72_000 in stepped)
        assertFalse(240_000 in stepped)
    }

    @Test
    fun `the clock rate is read and written as four bytes`() {
        device.clockRate = 44_100
        assertEquals(44_100, client.clockRate(CLOCK, AC))
        assertTrue(client.setClockRate(CLOCK, AC, 192_000))
        assertEquals(192_000, device.clockRate)
        val write = device.calls.last()
        assertEquals(listOf(0x21, CUR, 0x0100, CLOCK shl 8 or AC, 4), listOf(write.requestType, write.request, write.value, write.index, write.length))
        device.shortAnswers = true
        assertNull(client.clockRate(CLOCK, AC))
    }

    @Test
    fun `validity and selection are single bytes`() {
        device.valid = false
        assertEquals(false, client.clockValid(CLOCK, AC))
        device.valid = true
        assertEquals(true, client.clockValid(CLOCK, AC))
        assertEquals(0x0200, device.calls.last().value) // CLOCK_VALID_CONTROL
        device.selection = 2
        assertEquals(2, client.clockSelection(SELECTOR, AC))
        assertEquals(SELECTOR shl 8 or AC, device.calls.last().index)
        device.selection = 0
        assertNull("Inputs are counted from 1", client.clockSelection(SELECTOR, AC))
    }

    @Test
    fun `the volume range takes the first subrange as Linux does`() {
        device.volumeRanges = listOf(Triple(-15_360, 0, 256), Triple(0, 1_536, 512))
        assertEquals(UacVolumeRange(-15_360, 0, 256), client.volumeRange(UNIT, AC))
        assertEquals(0x0200, device.calls.last().value) // VOLUME_CONTROL, master channel
        device.volumeRanges = listOf(Triple(-0x8000, 0, 256))
        assertNull("Silence is a current value only", client.volumeRange(UNIT, AC))
        device.volumeRanges = listOf(Triple(-256, 0, 0))
        assertNull(client.volumeRange(UNIT, AC))
    }

    @Test
    fun `volume is signed and mute is a flag`() {
        assertTrue(client.setVolume(UNIT, AC, -7_680))
        assertEquals(-7_680, client.volume(UNIT, AC))
        assertTrue(client.setMute(UNIT, AC, true))
        assertEquals(true, client.mute(UNIT, AC))
        val mute = device.calls.last()
        assertEquals(listOf(0xA1, CUR, 0x0100, UNIT shl 8 or AC, 1), listOf(mute.requestType, mute.request, mute.value, mute.index, mute.length))
    }

    private data class Call(val requestType: Int, val request: Int, val value: Int, val index: Int, val length: Int)

    /** One clock source, one selector and one Feature Unit, answering by entity and control. */
    private class FakeUac2Controls : UsbControlTransfer {
        val calls = mutableListOf<Call>()
        var rateRanges = listOf(Triple(48_000L, 48_000L, 0L))
        var volumeRanges = listOf(Triple(-15_360, 0, 256))
        var clockRate = 48_000
        var valid = true
        var selection = 1
        var volume = 0
        var muted = false
        var shortAnswers = false
        var countInWholeAnswer: Int? = null

        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            calls += Call(requestType, request, value, index, length)
            val entity = index shr 8
            val control = value shr 8
            if (requestType == 0x21) {
                val written = (0 until length).fold(0L) { sum, i -> sum or ((buffer[i].toLong() and 0xFF) shl (8 * i)) }
                when {
                    entity == CLOCK && control == 1 -> clockRate = written.toInt()
                    entity == UNIT && control == 2 -> volume = written.toInt().toShort().toInt()
                    entity == UNIT && control == 1 -> muted = written != 0L
                    else -> return -1
                }
                return length
            }
            val answer: ByteArray = when {
                request == RANGE && entity == CLOCK -> range(rateRanges.map { listOf(it.first, it.second, it.third) }, 4)
                request == RANGE && entity == UNIT -> range(volumeRanges.map { (a, b, c) -> listOf(a.toLong(), b.toLong(), c.toLong()) }, 2)
                entity == CLOCK && control == 1 -> bytes(clockRate.toLong(), 4)
                entity == CLOCK && control == 2 -> bytes(if (valid) 1 else 0, 1)
                entity == SELECTOR -> bytes(selection.toLong(), 1)
                entity == UNIT && control == 2 -> bytes(volume.toLong(), 2)
                entity == UNIT && control == 1 -> bytes(if (muted) 1 else 0, 1)
                else -> return -1
            }
            val sent = minOf(length, answer.size) - if (shortAnswers) 1 else 0
            answer.copyInto(buffer, 0, 0, sent)
            return sent
        }

        private fun range(triplets: List<List<Long>>, size: Int): ByteArray {
            val count = if (countInWholeAnswer != null && calls.last().length > 2) countInWholeAnswer!! else triplets.size
            return bytes(count.toLong(), 2) + triplets.flatMap { it }.fold(ByteArray(0)) { all, value -> all + bytes(value, size) }
        }

        private fun bytes(value: Long, size: Int) = ByteArray(size) { (value shr (8 * it)).toByte() }
    }

    private companion object {
        const val AC = 0
        const val CLOCK = 10
        const val SELECTOR = 11
        const val UNIT = 2
        const val CUR = 0x01
        const val RANGE = 0x02
    }
}
