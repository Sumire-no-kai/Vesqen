package io.github.sumirenokai.vesqen.usb.descriptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dumps read from real devices. Each one is recorded in docs/M7_DEVICE_ACCEPTANCE.md. */
class UacRealDeviceDescriptorTest {
    @Test
    fun `jbl flip 7 is a uac1 speaker with two 48 khz formats and no feedback`() {
        val topology = parsed("jbl-flip-7-0ecb-2107")

        assertEquals(0x0200, topology.usbVersionBcd)
        assertEquals(0x0ECB, topology.vendorId)
        assertEquals(0x2107, topology.productId)
        assertEquals(listOf(1), topology.configurations.map(UacConfiguration::value))

        // Interface 0 is HID and stays out of this: only 1 and 2 are audio.
        val function = topology.functions.single()
        assertEquals(UacVersion.UAC1, function.version)
        assertEquals(1, function.controlInterface)
        assertEquals(listOf(UacInputTerminal(1, 0x0101, clockId = null)), function.inputTerminals)
        assertEquals(listOf(UacOutputTerminal(3, 0x0301, 9, clockId = null)), function.outputTerminals)
        assertTrue(function.clockEntities.isEmpty())

        val unit = function.featureUnitsAfter(1).single()
        assertEquals(9, unit.id)
        assertEquals(UacControlAccess.PRESENT_ACCESS_UNKNOWN, unit.masterVolume)
        assertEquals(UacControlAccess.PRESENT_ACCESS_UNKNOWN, unit.masterMute)
        assertEquals(listOf(UacControlAccess.ABSENT, UacControlAccess.ABSENT), unit.channelVolumes)

        val streaming = function.streamingInterfaces.single()
        assertEquals(2, streaming.interfaceNumber)
        val (closed, sixteen, twentyFour) = streaming.alternateSettings
        assertEquals(0, closed.alternateSetting)
        assertFalse(closed.isPlayback)

        assertEquals(1, sixteen.alternateSetting)
        assertEquals(format(subslotBytes = 2, bits = 16), sixteen.format)
        assertEquals(endpoint(maxPacketBytes = 192), sixteen.dataEndpoint)

        assertEquals(2, twentyFour.alternateSetting)
        assertEquals(format(subslotBytes = 3, bits = 24), twentyFour.format)
        assertEquals(endpoint(maxPacketBytes = 288), twentyFour.dataEndpoint)

        listOf(sixteen, twentyFour).forEach { alt ->
            assertTrue(alt.isPlayback)
            assertEquals(1, alt.terminalLink)
            assertEquals(null, alt.feedbackEndpoint)
            assertTrue(alt.endpointSetsSampleRate)
            assertTrue(alt.streamingBlockers.isEmpty())
        }
    }

    /** Android calls it a USB headset, but the device describes no stream back to the host. */
    @Test
    fun `jbl flip 7 has no microphone stream`() {
        val alternates = parsed("jbl-flip-7-0ecb-2107").functions
            .flatMap(UacFunction::streamingInterfaces)
            .flatMap(UacStreamingInterface::alternateSettings)

        assertFalse(alternates.any(UacAltSetting::isCapture))
    }

    private fun format(subslotBytes: Int, bits: Int) =
        UacFormat(UacPcmKind.LINEAR_PCM, true, 2, subslotBytes, bits, SampleRates.Discrete(listOf(48_000)))

    private fun endpoint(maxPacketBytes: Int) = UacEndpoint(
        address = 0x03,
        isIsochronous = true,
        syncType = UacSyncType.ADAPTIVE,
        usage = UacEndpointUsage.DATA,
        maxPacketBytes = maxPacketBytes,
        additionalTransactions = 0,
        interval = 1,
        refresh = 0,
        synchAddress = 0,
    )

    private fun parsed(sample: String): UsbAudioTopology {
        val hex = checkNotNull(javaClass.getResource("/descriptors/$sample.hex")).readText()
            .filterNot(Char::isWhitespace)
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology
    }
}
