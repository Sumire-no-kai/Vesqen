package io.github.sumirenokai.vesqen.usb.plan

import io.github.sumirenokai.vesqen.usb.control.UacRateRange

import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacAltSetting
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpoint
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpointUsage
import io.github.sumirenokai.vesqen.usb.descriptor.UacFormat
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.descriptor.UacPcmKind
import io.github.sumirenokai.vesqen.usb.descriptor.UacStreamingInterface
import io.github.sumirenokai.vesqen.usb.descriptor.UacSyncType
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingPlannerTest {
    @Test
    fun `the real jbl flip 7 plans both of its formats`() {
        val function = jblFlip7()

        assertEquals(
            StreamingPlan(
                interfaceNumber = 2,
                alternateSetting = 1,
                endpointAddress = 0x03,
                endpointSetsSampleRate = true,
                sampleRateHz = 48_000,
                channels = 2,
                subslotBytes = 2,
                bitResolution = 16,
                serviceIntervalUs = 1000,
                maxPacketBytes = 192,
                packetsPerUrb = 32,
                urbCount = 3,
                ringFrames = 24_000,
            ),
            planned(function, UsbBusSpeed.FULL, 48_000, 16),
        )
        val twentyFour = planned(function, UsbBusSpeed.FULL, 48_000, 24)
        assertEquals(2, twentyFour.alternateSetting)
        assertEquals(3, twentyFour.subslotBytes)
        assertEquals(6, twentyFour.frameBytes)
        assertEquals(288, twentyFour.maxPacketBytes)
    }

    @Test
    fun `a format the device does not list is refused`() {
        val function = jblFlip7()

        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 44_100, 16))
        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 48_000, 32))
    }

    /** The three rows of the table in docs/M7_USB_ARCHITECTURE.md, "StreamingPlan". */
    @Test
    fun `urbs follow the service interval`() {
        fun plan(speed: UsbBusSpeed, interval: Int) =
            planned(function(alt(endpoint(interval = interval, maxPacket = 1024))), speed, 48_000, 16)

        with(plan(UsbBusSpeed.FULL, 1)) {
            assertEquals(1000, serviceIntervalUs)
            assertEquals(32, packetsPerUrb)
            assertEquals(3, urbCount)
        }
        with(plan(UsbBusSpeed.HIGH, 1)) {
            assertEquals(125, serviceIntervalUs)
            assertEquals(128, packetsPerUrb)
            assertEquals(6, urbCount)
        }
        with(plan(UsbBusSpeed.HIGH, 4)) {
            assertEquals(1000, serviceIntervalUs)
            assertEquals(32, packetsPerUrb)
            assertEquals(3, urbCount)
        }
        assertEquals(125, plan(UsbBusSpeed.SUPER, 1).serviceIntervalUs)
        assertEquals(125, plan(UsbBusSpeed.SUPER_PLUS, 1).serviceIntervalUs)
    }

    @Test
    fun `a long service interval still keeps two urbs in flight`() {
        val plan = planned(
            function(alt(endpoint(interval = 7, maxPacket = 1024), rates = SampleRates.Discrete(listOf(4_000)))),
            UsbBusSpeed.FULL,
            4_000,
            16,
        )

        assertEquals(64_000, plan.serviceIntervalUs)
        assertEquals(1, plan.packetsPerUrb)
        assertEquals(2, plan.urbCount)
    }

    @Test
    fun `a speed without a known service interval is refused`() {
        listOf(UsbBusSpeed.UNKNOWN, UsbBusSpeed.LOW, UsbBusSpeed.WIRELESS).forEach { speed ->
            assertEquals(StreamingRefusal.SPEED_UNSUPPORTED, refused(function(alt(endpoint())), speed, 48_000, 16))
        }
    }

    @Test
    fun `speeds come from the kernel values`() {
        assertEquals(UsbBusSpeed.UNKNOWN, UsbBusSpeed.fromKernel(0))
        assertEquals(UsbBusSpeed.LOW, UsbBusSpeed.fromKernel(1))
        assertEquals(UsbBusSpeed.FULL, UsbBusSpeed.fromKernel(2))
        assertEquals(UsbBusSpeed.HIGH, UsbBusSpeed.fromKernel(3))
        assertEquals(UsbBusSpeed.WIRELESS, UsbBusSpeed.fromKernel(4))
        assertEquals(UsbBusSpeed.SUPER, UsbBusSpeed.fromKernel(5))
        assertEquals(UsbBusSpeed.SUPER_PLUS, UsbBusSpeed.fromKernel(6))
        assertEquals(UsbBusSpeed.UNKNOWN, UsbBusSpeed.fromKernel(-1))
        assertEquals(UsbBusSpeed.UNKNOWN, UsbBusSpeed.fromKernel(7))
    }

    @Test
    fun `the largest packet of a fractional rate has to fit`() {
        fun function(maxPacket: Int) = function(alt(endpoint(maxPacket = maxPacket), rates = SampleRates.Discrete(listOf(44_100))))

        // 44.1 kHz at 1 ms sends 45 frames in some packets, and each frame here is 4 bytes.
        assertEquals(180, planned(function(180), UsbBusSpeed.FULL, 44_100, 16).maxPacketBytes)
        assertEquals(StreamingRefusal.PACKET_TOO_SMALL, refused(function(176), UsbBusSpeed.FULL, 44_100, 16))
    }

    @Test
    fun `a setting too small for the rate gives way to the next one`() {
        val plan = planned(
            function(alt(endpoint(maxPacket = 100), alternate = 1), alt(endpoint(maxPacket = 192), alternate = 2)),
            UsbBusSpeed.FULL,
            48_000,
            16,
        )

        assertEquals(2, plan.alternateSetting)
    }

    @Test
    fun `a continuous range includes its limits`() {
        val function = function(alt(endpoint(maxPacket = 1024), rates = SampleRates.Continuous(8_000, 96_000)))

        assertEquals(8_000, planned(function, UsbBusSpeed.FULL, 8_000, 16).sampleRateHz)
        assertEquals(96_000, planned(function, UsbBusSpeed.FULL, 96_000, 16).sampleRateHz)
        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 7_999, 16))
        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 96_001, 16))
    }

    @Test
    fun `rates known only at run time cannot be planned from descriptors`() {
        val function = function(alt(endpoint(), rates = SampleRates.RuntimeUnknown))

        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 48_000, 16))
    }

    @Test
    fun `a setting with a blocker is not used`() {
        val function = function(alt(endpoint(interval = 0)), alt(endpoint(address = 0x83)))

        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 48_000, 16))
    }

    @Test
    fun `capture settings are not used`() {
        val function = function(alt(endpoint(address = 0x83)))

        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(function, UsbBusSpeed.FULL, 48_000, 16))
    }

    @Test
    fun `uac2 plans before its clock answers and follows the clock once it has`() {
        val uac2 = function(alt(endpoint(), rates = SampleRates.RuntimeUnknown), version = UacVersion.UAC2)
        assertEquals(96_000, planned(uac2, UsbBusSpeed.HIGH, 96_000, 16).sampleRateHz)
        val clock = listOf(UacRateRange(44_100, 44_100, 0), UacRateRange(48_000, 192_000, 48_000))
        assertEquals(96_000, planned(uac2, UsbBusSpeed.HIGH, 96_000, 16, clock).sampleRateHz)
        assertEquals(44_100, planned(uac2, UsbBusSpeed.HIGH, 44_100, 16, clock).sampleRateHz)
        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(uac2, UsbBusSpeed.HIGH, 88_200, 16, clock))
        assertEquals(StreamingRefusal.NO_MATCHING_FORMAT, refused(uac2, UsbBusSpeed.HIGH, 72_000, 16, clock))
    }

    @Test
    fun `protocols past uac2 wait for what they need`() {
        assertEquals(
            StreamingRefusal.VERSION_NOT_IMPLEMENTED,
            refused(function(alt(endpoint()), version = UacVersion.OTHER), UsbBusSpeed.HIGH, 48_000, 16),
        )
    }

    /** #284: the data plane follows the feedback endpoint, polled as its bInterval says. */
    @Test
    fun `an asynchronous endpoint plans its feedback`() {
        val asynchronous = alt(endpoint(sync = UacSyncType.ASYNCHRONOUS, synchAddress = 0x81), feedback = feedbackEndpoint())

        assertEquals(
            FeedbackPlan(endpointAddress = 0x81, maxPacketBytes = 3, serviceIntervalUs = 1000, packetsPerUrb = 32, timeoutUs = 1_000_000),
            planned(function(asynchronous), UsbBusSpeed.FULL, 48_000, 16).feedback,
        )
        // An adaptive endpoint follows the nominal rate, and the first setting that fits is still taken.
        val adaptiveFirst = function(alt(endpoint(), alternate = 5), asynchronous)
        assertEquals(null, planned(adaptiveFirst, UsbBusSpeed.FULL, 48_000, 16).feedback)
        assertEquals(1, planned(function(asynchronous, alt(endpoint(), alternate = 5)), UsbBusSpeed.FULL, 48_000, 16).alternateSetting)
    }

    @Test
    fun `the feedback timeout allows four updates and never less than a second`() {
        fun feedbackOf(feedback: UacEndpoint, speed: UsbBusSpeed = UsbBusSpeed.FULL, version: UacVersion = UacVersion.UAC1): FeedbackPlan {
            val asynchronous = alt(endpoint(sync = UacSyncType.ASYNCHRONOUS, synchAddress = 0x81), feedback = feedback, version = version)
            return checkNotNull(planned(function(asynchronous, version = version), speed, 48_000, 16).feedback)
        }

        // UAC1's slowest refresh, bRefresh 9: a value every 512 ms.
        with(feedbackOf(feedbackEndpoint(refresh = 9))) {
            assertEquals(1000, serviceIntervalUs)
            assertEquals(2_048_000, timeoutUs)
        }
        // UAC2 has no bRefresh. At high speed bInterval 4 polls every 1 ms, and bInterval 1 every microframe.
        with(feedbackOf(feedbackEndpoint(interval = 4, maxPacket = 4, refresh = null), UsbBusSpeed.HIGH, UacVersion.UAC2)) {
            assertEquals(1000, serviceIntervalUs)
            assertEquals(32, packetsPerUrb)
            assertEquals(1_000_000, timeoutUs)
        }
        with(feedbackOf(feedbackEndpoint(interval = 1, maxPacket = 4, refresh = null), UsbBusSpeed.HIGH, UacVersion.UAC2)) {
            assertEquals(125, serviceIntervalUs)
            assertEquals(128, packetsPerUrb)
        }
        // A rare poll every 512 ms: one packet per URB, and four polls before the stream gives up.
        with(feedbackOf(feedbackEndpoint(interval = 10, refresh = null), version = UacVersion.UAC2)) {
            assertEquals(512_000, serviceIntervalUs)
            assertEquals(1, packetsPerUrb)
            assertEquals(2_048_000, timeoutUs)
        }
    }

    private fun planned(function: UacFunction, speed: UsbBusSpeed, rate: Int, bits: Int, clock: List<UacRateRange>? = null): StreamingPlan =
        (StreamingPlanner.plan(function, speed, rate, bits, clock) as StreamingPlanResult.Planned).plan

    private fun refused(function: UacFunction, speed: UsbBusSpeed, rate: Int, bits: Int, clock: List<UacRateRange>? = null): StreamingRefusal =
        (StreamingPlanner.plan(function, speed, rate, bits, clock) as StreamingPlanResult.Refused).reason

    private fun jblFlip7(): UacFunction {
        val hex = checkNotNull(javaClass.getResource("/descriptors/jbl-flip-7-0ecb-2107.hex")).readText()
            .filterNot(Char::isWhitespace)
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology.functions.single()
    }

    private fun function(vararg alternates: UacAltSetting, version: UacVersion = UacVersion.UAC1) = UacFunction(
        version = version,
        interfaceProtocol = 0,
        controlInterface = 0,
        inputTerminals = emptyList(),
        outputTerminals = emptyList(),
        featureUnits = emptyList(),
        otherUnits = emptyList(),
        clockEntities = emptyList(),
        streamingInterfaces = listOf(UacStreamingInterface(1, alternates.toList())),
    )

    private fun alt(
        data: UacEndpoint,
        alternate: Int = 1,
        rates: SampleRates = SampleRates.Discrete(listOf(8_000, 48_000)),
        feedback: UacEndpoint? = null,
        version: UacVersion = UacVersion.UAC1,
    ) = UacAltSetting(
        version = version,
        alternateSetting = alternate,
        terminalLink = 1,
        format = UacFormat(UacPcmKind.LINEAR_PCM, true, 2, 2, 16, rates),
        dataEndpoint = data,
        feedbackEndpoint = feedback,
        endpointSetsSampleRate = false,
    )

    private fun endpoint(
        address: Int = 0x01,
        interval: Int = 1,
        maxPacket: Int = 192,
        sync: UacSyncType = UacSyncType.ADAPTIVE,
        synchAddress: Int = 0,
    ) = UacEndpoint(
        address = address,
        isIsochronous = true,
        syncType = sync,
        usage = UacEndpointUsage.DATA,
        maxPacketBytes = maxPacket,
        additionalTransactions = 0,
        interval = interval,
        refresh = 0,
        synchAddress = synchAddress,
    )

    private fun feedbackEndpoint(interval: Int = 1, maxPacket: Int = 3, refresh: Int? = 5) = UacEndpoint(
        address = 0x81,
        isIsochronous = true,
        syncType = UacSyncType.NONE,
        usage = UacEndpointUsage.FEEDBACK,
        maxPacketBytes = maxPacket,
        additionalTransactions = 0,
        interval = interval,
        refresh = refresh,
        synchAddress = 0,
    )
}
