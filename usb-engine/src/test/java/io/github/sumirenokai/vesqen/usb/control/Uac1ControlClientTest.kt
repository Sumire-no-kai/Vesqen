package io.github.sumirenokai.vesqen.usb.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Uac1ControlClientTest {
    private data class Request(val requestType: Int, val request: Int, val value: Int, val index: Int, val length: Int, val timeoutMs: Int)

    private class FakeDevice : UsbControlTransfer {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<ByteArray>()
        val answers = mutableMapOf<Int, ByteArray>()

        /** What each write returns. */
        var writeResult: Int? = null

        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            requests += Request(requestType, request, value, index, length, timeoutMs)
            if (requestType and 0x80 == 0) {
                bodies += buffer.copyOf(length)
                return writeResult ?: length
            }
            val answer = answers[request] ?: return -1
            answer.copyInto(buffer, endIndex = minOf(answer.size, length))
            return answer.size
        }
    }

    private val device = FakeDevice()
    private val client = Uac1ControlClient(device)

    @Test
    fun `the volume range reads the three attributes of the master channel`() {
        // -96 dB, 0 dB and 1 dB.
        device.answers[GET_MIN] = bytes(0x00, 0xA0)
        device.answers[GET_MAX] = bytes(0x00, 0x00)
        device.answers[GET_RES] = bytes(0x00, 0x01)

        assertEquals(UacVolumeRange(-24_576, 0, 256), client.volumeRange(unitId = 9, interfaceNumber = 1))
        assertEquals(
            listOf(GET_MIN, GET_MAX, GET_RES).map { Request(0xA1, it, 0x0200, 0x0901, 2, 500) },
            device.requests,
        )
    }

    @Test
    fun `a range the spec does not allow gives no answer`() {
        fun rangeWith(minimum: ByteArray, maximum: ByteArray, resolution: ByteArray): UacVolumeRange? {
            device.answers[GET_MIN] = minimum
            device.answers[GET_MAX] = maximum
            device.answers[GET_RES] = resolution
            return client.volumeRange(9, 1)
        }

        // 0x8000 is silence, which only a current value can be.
        assertNull(rangeWith(bytes(0x00, 0x80), bytes(0x00, 0x00), bytes(0x00, 0x01)))
        assertNull(rangeWith(bytes(0x00, 0xA0), bytes(0x00, 0x80), bytes(0x00, 0x01)))
        // Minimum above maximum.
        assertNull(rangeWith(bytes(0x00, 0x01), bytes(0x00, 0x00), bytes(0x00, 0x01)))
        // A resolution has to be positive.
        assertNull(rangeWith(bytes(0x00, 0xA0), bytes(0x00, 0x00), bytes(0x00, 0x00)))
        assertNull(rangeWith(bytes(0x00, 0xA0), bytes(0x00, 0x00), bytes(0x00, 0xFF)))
        // Equal limits and the smallest resolution are legal.
        assertEquals(UacVolumeRange(0, 0, 1), rangeWith(bytes(0x00, 0x00), bytes(0x00, 0x00), bytes(0x01, 0x00)))
    }

    @Test
    fun `a short answer or a stall gives no answer`() {
        device.answers[GET_CUR] = bytes(0x00)
        assertNull(client.volume(9, 1))

        device.answers.clear()
        assertNull(client.volume(9, 1))
        assertNull(client.mute(9, 1))
        assertNull(client.sampleRate(0x03))

        device.answers[GET_MIN] = bytes(0x00, 0xA0)
        assertNull(client.volumeRange(9, 1))
    }

    @Test
    fun `the current volume is signed and silence is a legal value`() {
        device.answers[GET_CUR] = bytes(0x00, 0xE2)
        assertEquals(-7_680, client.volume(9, 1))
        assertEquals(Request(0xA1, GET_CUR, 0x0200, 0x0901, 2, 500), device.requests.single())

        device.answers[GET_CUR] = bytes(0x00, 0x80)
        assertEquals(UAC1_VOLUME_SILENCE, client.volume(9, 1))

        device.answers[GET_CUR] = bytes(0xFF, 0x7F)
        assertEquals(32_767, client.volume(9, 1))
    }

    @Test
    fun `setting the volume writes it little endian`() {
        assertTrue(client.setVolume(9, 1, -7_680))

        assertEquals(Request(0x21, SET_CUR, 0x0200, 0x0901, 2, 500), device.requests.single())
        assertArrayEquals(bytes(0x00, 0xE2), device.bodies.single())
    }

    @Test
    fun `mute uses its own control`() {
        device.answers[GET_CUR] = bytes(0x01)
        assertEquals(true, client.mute(9, 1))
        assertEquals(Request(0xA1, GET_CUR, 0x0100, 0x0901, 1, 500), device.requests.last())

        device.answers[GET_CUR] = bytes(0x00)
        assertEquals(false, client.mute(9, 1))

        assertTrue(client.setMute(9, 1, true))
        assertEquals(Request(0x21, SET_CUR, 0x0100, 0x0901, 1, 500), device.requests.last())
        assertArrayEquals(bytes(0x01), device.bodies.last())
        assertTrue(client.setMute(9, 1, false))
        assertArrayEquals(bytes(0x00), device.bodies.last())
    }

    @Test
    fun `the sample rate goes to the endpoint in three bytes`() {
        assertTrue(client.setSampleRate(0x03, 48_000))
        assertEquals(Request(0x22, SET_CUR, 0x0100, 0x03, 3, 500), device.requests.last())
        assertArrayEquals(bytes(0x80, 0xBB, 0x00), device.bodies.last())

        device.answers[GET_CUR] = bytes(0x00, 0xEE, 0x02)
        assertEquals(192_000, client.sampleRate(0x03))
        assertEquals(Request(0xA2, GET_CUR, 0x0100, 0x03, 3, 500), device.requests.last())
    }

    @Test
    fun `a write the device refuses reports failure`() {
        device.writeResult = -1

        assertFalse(client.setVolume(9, 1, 0))
        assertFalse(client.setMute(9, 1, false))
        assertFalse(client.setSampleRate(0x03, 48_000))

        device.writeResult = 1
        assertFalse(client.setVolume(9, 1, 0))
        assertFalse(client.setSampleRate(0x03, 48_000))
    }

    @Test
    fun `the timeout reaches every request`() {
        val quick = Uac1ControlClient(device, timeoutMs = 50)

        quick.setVolume(9, 1, 0)

        assertEquals(50, device.requests.single().timeoutMs)
    }

    @Test
    fun `the setting below a target stays on the device's steps and inside its range`() {
        val range = UacVolumeRange(minimum = -24_576, maximum = 0, resolution = 1_000)

        // -7680 sits between two steps, -24576 + 16 * 1000 and + 17 * 1000.
        assertEquals(-8_576, range.settingAtOrBelow(-7_680))
        assertEquals(-7_576, range.settingAtOrBelow(-7_576))
        // A device that cannot go that low uses its lowest setting.
        assertEquals(-24_576, range.settingAtOrBelow(-30_000))
        assertEquals(-576, range.settingAtOrBelow(100))
        assertEquals(-7_680, UacVolumeRange(-24_576, 0, 1).settingAtOrBelow(-7_680))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private companion object {
        const val SET_CUR = 0x01
        const val GET_CUR = 0x81
        const val GET_MIN = 0x82
        const val GET_MAX = 0x83
        const val GET_RES = 0x84
    }
}
