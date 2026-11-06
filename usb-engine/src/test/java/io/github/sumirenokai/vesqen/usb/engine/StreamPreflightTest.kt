package io.github.sumirenokai.vesqen.usb.engine

import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.descriptor.UsbAudioTopology
import io.github.sumirenokai.vesqen.usb.plan.StreamingRefusal
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #276: the probes, and later the session, decide here before they claim an interface. */
class StreamPreflightTest {
    @Test
    fun aLibraryThatDoesNotLoadIsReportedAsSuchBeforeTheSpeedIsJudged() {
        // The JVM has no libvesqen_usb, so this is the real query a device without the library makes.
        val preflight = StreamPreflight.run(jblFlip7(), UsbStreamEngine.busSpeed(fileDescriptor = -1), 48_000, 16)

        assertTrue("$preflight", preflight is StreamPreflight.LibraryUnavailable)
        assertTrue((preflight as StreamPreflight.LibraryUnavailable).reason, preflight.reason.contains("vesqen_usb"))
    }

    @Test
    fun aLoadedLibraryThatCannotReadTheSpeedStillReportsTheSpeed() {
        val preflight = StreamPreflight.run(jblFlip7(), UsbStreamEngine.SpeedQuery.Answered(UsbBusSpeed.UNKNOWN), 48_000, 16)

        assertEquals(StreamPreflight.Refused(UsbBusSpeed.UNKNOWN, StreamingRefusal.SPEED_UNSUPPORTED), preflight)
    }

    @Test
    fun aKnownSpeedGivesThePlanForTheFunctionThatPlays() {
        val preflight = StreamPreflight.run(jblFlip7(), UsbStreamEngine.SpeedQuery.Answered(UsbBusSpeed.FULL), 48_000, 16)

        assertTrue("$preflight", preflight is StreamPreflight.Ready)
        val ready = preflight as StreamPreflight.Ready
        assertEquals(UsbBusSpeed.FULL, ready.speed)
        assertEquals(1, ready.plan.alternateSetting)
        assertEquals(16, ready.plan.bitResolution)
    }

    @Test
    fun aDeviceWithoutPlaybackIsReportedBeforeTheLibraryIsConsulted() {
        val noPlayback = UsbAudioTopology(usbVersionBcd = 0x0200, vendorId = 0, productId = 0, configurations = emptyList())

        val preflight = StreamPreflight.run(noPlayback, UsbStreamEngine.SpeedQuery.LibraryUnavailable("missing"), 48_000, 16)

        assertEquals(StreamPreflight.NoPlaybackFunction, preflight)
    }

    private fun jblFlip7(): UsbAudioTopology {
        val hex = checkNotNull(javaClass.getResource("/descriptors/jbl-flip-7-0ecb-2107.hex")).readText()
            .filterNot(Char::isWhitespace)
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology
    }
}
