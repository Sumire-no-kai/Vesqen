package io.github.sumirenokai.vesqen.playback

import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.control.UacRateRange
import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacAltSetting
import io.github.sumirenokai.vesqen.usb.descriptor.UacConfiguration
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpoint
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpointUsage
import io.github.sumirenokai.vesqen.usb.descriptor.UacFormat
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacPcmKind
import io.github.sumirenokai.vesqen.usb.descriptor.UacStreamingInterface
import io.github.sumirenokai.vesqen.usb.descriptor.UacSyncType
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion
import io.github.sumirenokai.vesqen.usb.descriptor.UsbAudioTopology
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingRefusal
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** 4d: a refusal on the own path names the device's limit before the track's format. */
class DirectUsbRefusalTest {
    private val cd = SourcePcmFormat(sampleRateHz = 44_100, bitDepth = 16, channelCount = 2)

    @Test
    fun `an asynchronous UAC2 device is named for that, not as lacking the track's format`() {
        val refusal = directRefusal(
            topology(function(alt(16), alt(24, alternate = 2), version = UacVersion.UAC2)),
            listOf(StreamingRefusal.ASYNCHRONOUS_NOT_IMPLEMENTED, StreamingRefusal.ASYNCHRONOUS_NOT_IMPLEMENTED),
            cd,
        )

        assertEquals(DirectUsbInspection.Refused("async_not_supported"), refusal)
    }

    @Test
    fun `a UAC2 device offers the rates its clock listed once claimed`() {
        val uac2 = function(alt(16, rates = SampleRates.RuntimeUnknown), alt(24, alternate = 2, rates = SampleRates.RuntimeUnknown), version = UacVersion.UAC2)
        val clock = listOf(UacRateRange(44_100, 44_100, 0), UacRateRange(48_000, 96_000, 48_000), UacRateRange(176_400, 192_000, 0))
        val details = cd.toDirectDetails().withOffer(uac2, clock)

        assertEquals(listOf(44_100..44_100, 48_000..96_000, 176_400..176_400), details.deviceSampleRatesHz)
        assertEquals(24, details.deviceMaxBitsAtSourceRate)
        assertEquals("Before the clock answers nothing is known", emptyList<IntRange>(), cd.toDirectDetails().withOffer(uac2).deviceSampleRatesHz)
    }

    @Test
    fun `a rate the clock refused after the takeover reads like a refusal before it`() {
        assertEquals(
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED to "direct_usb.rate_unsupported",
            DirectUsbFailure(DirectUsbFailure.Reason.SAMPLE_RATE_UNSUPPORTED, "48000 Hz").toStrictFailure(),
        )
        assertEquals(
            UsbOutputFailure.PLATFORM_ERROR to "direct_usb.sample_rate_set_failed",
            DirectUsbFailure(DirectUsbFailure.Reason.SAMPLE_RATE_SET_FAILED, "48000 Hz").toStrictFailure(),
        )
    }

    @Test
    fun `another protocol version is not called UAC2`() {
        val refusal = directRefusal(
            topology(function(alt(16), version = UacVersion.OTHER)),
            listOf(StreamingRefusal.VERSION_NOT_IMPLEMENTED),
            cd,
        )

        assertEquals("uac_version_unsupported", refusal.code)
    }

    @Test
    fun `an asynchronous endpoint at one depth outranks a missing format at another`() {
        val refusal = directRefusal(
            topology(function(alt(24, rates = SampleRates.Discrete(listOf(44_100)), sync = UacSyncType.ASYNCHRONOUS))),
            listOf(StreamingRefusal.NO_MATCHING_FORMAT, StreamingRefusal.ASYNCHRONOUS_NOT_IMPLEMENTED),
            cd,
        )

        assertEquals(DirectUsbInspection.Refused("async_not_supported"), refusal)
    }

    @Test
    fun `bus limits are told apart from the format`() {
        assertEquals("speed_unsupported", directRefusal(topology(function(alt(16))), listOf(StreamingRefusal.SPEED_UNSUPPORTED), cd).code)
        val packet = directRefusal(topology(function(alt(16))), listOf(StreamingRefusal.PACKET_TOO_SMALL), cd)
        assertEquals(DirectUsbInspection.Refused("format_exceeds_bus", formatRefused = true), packet)
    }

    @Test
    fun `a rate the DAC lacks lists the rates it has, as on the JBL Flip 7`() {
        // The JBL offers 48 kHz only, at 16 and 24 bits (docs/M7_DEVICE_ACCEPTANCE.md).
        val jbl = function(alt(16), alt(24, alternate = 2))
        val refusal = directRefusal(topology(jbl), List(3) { StreamingRefusal.NO_MATCHING_FORMAT }, cd)

        assertEquals("rate_unsupported", refusal.code)
        assertEquals(true, refusal.formatRefused)
        assertEquals(
            DirectUsbDetails(
                sourceSampleRateHz = 44_100,
                sourceBitDepth = 16,
                sourceChannelCount = 2,
                deviceSampleRatesHz = listOf(48_000..48_000),
                deviceChannelCount = 2,
            ),
            refusal.details,
        )
    }

    @Test
    fun `a continuous range is kept as a range`() {
        val wide = function(alt(24, rates = SampleRates.Continuous(8_000, 96_000)))
        val hiRes = SourcePcmFormat(sampleRateHz = 192_000, bitDepth = 24, channelCount = 2)

        val refusal = directRefusal(topology(wide), List(2) { StreamingRefusal.NO_MATCHING_FORMAT }, hiRes)

        assertEquals("rate_unsupported", refusal.code)
        assertEquals(listOf(8_000..96_000), refusal.details?.deviceSampleRatesHz)
    }

    @Test
    fun `a depth the DAC lacks at a rate it has names its deepest`() {
        val sixteenOnly = function(alt(16))
        val hiRes = SourcePcmFormat(sampleRateHz = 48_000, bitDepth = 24, channelCount = 2)

        val refusal = directRefusal(topology(sixteenOnly), List(2) { StreamingRefusal.NO_MATCHING_FORMAT }, hiRes)

        assertEquals("depth_unsupported", refusal.code)
        assertEquals(
            DirectUsbDetails(
                sourceSampleRateHz = 48_000,
                sourceBitDepth = 24,
                sourceChannelCount = 2,
                deviceSampleRatesHz = listOf(48_000..48_000),
                deviceMaxBitsAtSourceRate = 16,
                deviceChannelCount = 2,
            ),
            refusal.details,
        )
    }

    @Test
    fun `a refusal at the format gate names the checked item and what the DAC offers`() {
        val jbl = function(alt(16), alt(24, alternate = 2))
        val next = TelemetrySourceFacts(codecMime = "audio/flac", codecLabel = "FLAC", sampleRateHz = 44_100, bitDepth = 16, channelCount = 2)

        assertEquals(
            DirectUsbDetails(
                sourceCodec = "FLAC",
                sourceSampleRateHz = 44_100,
                sourceBitDepth = 16,
                sourceChannelCount = 2,
                deviceSampleRatesHz = listOf(48_000..48_000),
                deviceChannelCount = 2,
            ),
            next.toDirectDetails().withOffer(jbl),
        )
        // A mono source on the stereo JBL names both channel counts for the reason (#295).
        val mono = SourcePcmFormat(48_000, 16, 1).toDirectDetails().withOffer(jbl)
        assertEquals(1, mono.sourceChannelCount)
        assertEquals(2, mono.deviceChannelCount)
        assertEquals(24, next.copy(sampleRateHz = 48_000).toDirectDetails().withOffer(jbl).deviceMaxBitsAtSourceRate)
    }

    @Test
    fun `rates a device cannot mean are left out instead of throwing`() {
        // #290: the parser accepts a zero rate and a reversed range; the reason must not throw on them.
        val broken = function(alt(16, rates = SampleRates.Discrete(listOf(0))), alt(24, alternate = 2, rates = SampleRates.Continuous(96_000, 8_000)))
        assertEquals(
            DirectUsbInspection.Refused("no_matching_format", formatRefused = true),
            directRefusal(topology(broken), List(3) { StreamingRefusal.NO_MATCHING_FORMAT }, cd),
        )

        val mixed = function(alt(16, rates = SampleRates.Discrete(listOf(0, 48_000))))
        assertEquals(
            listOf(48_000..48_000),
            directRefusal(topology(mixed), List(3) { StreamingRefusal.NO_MATCHING_FORMAT }, cd).details?.deviceSampleRatesHz,
        )
    }

    @Test
    fun `a plan carries a source only at its rate, depth and channel count`() {
        val stereo24 = StreamingPlan(
            interfaceNumber = 2, alternateSetting = 2, endpointAddress = 3, endpointSetsSampleRate = true, sampleRateHz = 48_000,
            channels = 2, subslotBytes = 3, bitResolution = 24, serviceIntervalUs = 1000, maxPacketBytes = 288,
            packetsPerUrb = 32, urbCount = 3, ringFrames = 24_000,
        )
        assertEquals(true, stereo24.carries(SourcePcmFormat(48_000, 16, 2)))
        assertEquals(true, stereo24.carries(SourcePcmFormat(48_000, 24, 2)))
        assertEquals(false, stereo24.carries(SourcePcmFormat(44_100, 16, 2)))
        // #295: a known mono or multichannel source would need mixing, so it is refused before takeover.
        assertEquals(false, stereo24.carries(SourcePcmFormat(48_000, 16, 1)))
        assertEquals(false, stereo24.carries(SourcePcmFormat(48_000, 16, 6)))
    }

    @Test
    fun `Chain names the protocol and the plan's synchronisation`() {
        val plan = StreamingPlan(
            interfaceNumber = 2, alternateSetting = 1, endpointAddress = 3, endpointSetsSampleRate = true, sampleRateHz = 48_000,
            channels = 2, subslotBytes = 2, bitResolution = 16, serviceIntervalUs = 1000, maxPacketBytes = 192,
            packetsPerUrb = 32, urbCount = 3, ringFrames = 24_000,
        )
        val jbl = function(alt(16), alt(24, alternate = 2))
        assertEquals("uac1", jbl.protocolToken())
        assertEquals("adaptive", jbl.syncToken(plan))
        assertEquals("asynchronous", function(alt(16, sync = UacSyncType.ASYNCHRONOUS)).syncToken(plan))
        assertEquals("uac2", function(alt(16), version = UacVersion.UAC2).protocolToken())
        assertEquals("none", jbl.syncToken(plan.copy(alternateSetting = 9)))
    }

    @Test
    fun `settings the own output cannot stream at all leave the format reason general`() {
        val mono = function(alt(16, channels = 1))

        val refusal = directRefusal(topology(mono), listOf(StreamingRefusal.NO_MATCHING_FORMAT), cd)

        assertEquals(DirectUsbInspection.Refused("no_matching_format", formatRefused = true), refusal)
    }

    private fun topology(vararg functions: UacFunction) = UsbAudioTopology(
        usbVersionBcd = 0x0200,
        vendorId = 0x0ecb,
        productId = 0x2107,
        configurations = listOf(UacConfiguration(1, functions.toList())),
    )

    private fun function(vararg alternates: UacAltSetting, version: UacVersion = UacVersion.UAC1) = UacFunction(
        version = version,
        interfaceProtocol = 0,
        controlInterface = 0,
        inputTerminals = emptyList(),
        outputTerminals = emptyList(),
        featureUnits = emptyList(),
        otherUnits = emptyList(),
        clockEntities = emptyList(),
        streamingInterfaces = listOf(UacStreamingInterface(2, alternates.toList())),
    )

    private fun alt(
        bits: Int,
        alternate: Int = 1,
        rates: SampleRates = SampleRates.Discrete(listOf(48_000)),
        sync: UacSyncType = UacSyncType.ADAPTIVE,
        channels: Int = 2,
    ) = UacAltSetting(
        version = UacVersion.UAC1,
        alternateSetting = alternate,
        terminalLink = 1,
        format = UacFormat(UacPcmKind.LINEAR_PCM, true, channels, if (bits > 16) 3 else 2, bits, rates),
        dataEndpoint = UacEndpoint(
            address = 0x03,
            isIsochronous = true,
            syncType = sync,
            usage = UacEndpointUsage.DATA,
            maxPacketBytes = 288,
            additionalTransactions = 0,
            interval = 1,
            refresh = 0,
            synchAddress = if (sync == UacSyncType.ASYNCHRONOUS) 0x83 else 0,
        ),
        feedbackEndpoint = if (sync == UacSyncType.ASYNCHRONOUS) feedback() else null,
        endpointSetsSampleRate = true,
    )

    private fun feedback() = UacEndpoint(
        address = 0x83,
        isIsochronous = true,
        syncType = UacSyncType.NONE,
        usage = UacEndpointUsage.FEEDBACK,
        maxPacketBytes = 3,
        additionalTransactions = 0,
        interval = 1,
        refresh = 5,
        synchAddress = 0,
    )
}
