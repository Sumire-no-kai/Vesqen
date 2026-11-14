package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.DirectUsbDetails
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 4d: which sentence a stop on Vesqen's own USB output gets, and with what. */
class DirectUsbTextTest {
    @Test
    fun `the JBL Flip 7 refusing a CD track names both rates`() {
        val status = failed(
            "direct_usb.rate_unsupported",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            sourceRate = 44_100,
            details = DirectUsbDetails(sourceBitDepth = 16, deviceSampleRatesHz = listOf(48_000..48_000)),
        )

        assertEquals(
            StringSpec(R.string.usb_failure_direct_rate, listOf("44.1 kHz", "JBL Flip 7", "48 kHz")),
            spec(status),
        )
    }

    @Test
    fun `several rates and a range are listed in order`() {
        val status = failed(
            "direct_usb.rate_unsupported",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            sourceRate = 192_000,
            details = DirectUsbDetails(deviceSampleRatesHz = listOf(44_100..44_100, 48_000..48_000, 88_200..96_000)),
        )

        assertEquals(listOf("192 kHz", "JBL Flip 7", "44.1 kHz、48 kHz、88.2–96 kHz"), spec(status)?.args)
    }

    @Test
    fun `a depth refusal names the track's depth and the DAC's deepest`() {
        val status = failed(
            "direct_usb.depth_unsupported",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            details = DirectUsbDetails(sourceBitDepth = 24, deviceMaxBitsAtSourceRate = 16),
        )

        assertEquals(StringSpec(R.string.usb_failure_direct_depth, listOf(24, "JBL Flip 7", 16)), spec(status))
    }

    @Test
    fun `a refusal in the middle of a queue names the next track, not the one still playing`() {
        // The JBL is set for the 48 kHz track that plays; the gate refused the 44.1 kHz one after it.
        val status = failed(
            "direct_usb.no_matching_direct_format",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            sourceRate = 48_000,
            details = DirectUsbDetails(sourceSampleRateHz = 44_100, sourceBitDepth = 16, deviceSampleRatesHz = listOf(48_000..48_000)),
        )
        assertEquals(listOf("44.1 kHz", "JBL Flip 7", "48 kHz"), spec(status)?.args)

        // A rate the DAC has needs it set again, which the own output cannot do yet (4c).
        val supported = status.copy(
            directDetails = DirectUsbDetails(
                sourceSampleRateHz = 44_100,
                sourceBitDepth = 16,
                deviceSampleRatesHz = listOf(44_100..44_100, 48_000..48_000),
                deviceMaxBitsAtSourceRate = 24,
            ),
        )
        assertNull(spec(supported))
    }

    @Test
    fun `a mono track on a stereo DAC names both channel counts`() {
        val details = DirectUsbDetails(sourceChannelCount = 1, deviceChannelCount = 2)
        val expected = StringSpec(R.string.usb_failure_direct_channels, listOf(1, "JBL Flip 7", 2))
        assertEquals(expected, spec(failed("direct_usb.channels_unsupported", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, details = details)))
        assertEquals(expected, spec(failed("direct_usb.no_matching_direct_format", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, details = details)))
    }

    @Test
    fun `a sentence whose facts are missing falls back to the general reason`() {
        assertNull(spec(failed("direct_usb.rate_unsupported", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, sourceRate = 44_100)))
        assertNull(spec(failed("direct_usb.depth_unsupported", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED)))
        assertNull(spec(failed("direct_usb.sample_rate_set_failed", UsbOutputFailure.PLATFORM_ERROR)))
    }

    @Test
    fun `the source's own layer names the codec or depth when known`() {
        val mp3 = failed(
            "direct_usb.source_not_lossless",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            details = DirectUsbDetails(sourceCodec = "MP3"),
        )
        assertEquals(StringSpec(R.string.usb_failure_direct_lossless_codec, listOf("MP3")), spec(mp3))
        assertEquals(
            StringSpec(R.string.usb_failure_direct_lossless),
            spec(failed("direct_usb.source_not_lossless", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED)),
        )
        val wide = failed(
            "direct_usb.source_bit_depth_unsupported",
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            details = DirectUsbDetails(sourceBitDepth = 32),
        )
        assertEquals(StringSpec(R.string.usb_failure_direct_lossless_bits, listOf(32)), spec(wide))
    }

    @Test
    fun `device limits name the device`() {
        assertEquals(
            StringSpec(R.string.usb_failure_direct_uac_version, listOf("JBL Flip 7")),
            spec(failed("direct_usb.uac_version_unsupported", UsbOutputFailure.PLATFORM_ERROR)),
        )
        // Feedback is part of the transfer until it has texts of its own.
        for (code in listOf("usb_transfer_failed", "feedback_timeout", "feedback_out_of_range")) {
            assertEquals(
                StringSpec(R.string.usb_failure_direct_transfer, listOf("JBL Flip 7")),
                spec(failed("direct_usb.$code", UsbOutputFailure.PLATFORM_ERROR)),
            )
        }
        assertEquals(
            StringSpec(R.string.usb_failure_direct_permission_required, listOf("JBL Flip 7")),
            spec(failed("direct_usb.permission_required", UsbOutputFailure.PLATFORM_ERROR)),
        )
    }

    @Test
    fun `the official path and failures that already say it keep the general reason`() {
        assertNull(spec(failed("strict_usb.no_bit_perfect_mixer", UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE)))
        assertNull(spec(failed("direct_usb.device_disconnected", UsbOutputFailure.DEVICE_DISCONNECTED)))
        assertNull(spec(failed("direct_usb.source_format_unknown", UsbOutputFailure.SOURCE_FORMAT_UNKNOWN)))
        assertNull(spec(failed("direct_usb.channels_unsupported", UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED)))
    }

    @Test
    fun `rates read as written on a DAC`() {
        assertEquals("44.1 kHz", formatKilohertz(44_100))
        assertEquals("48 kHz", formatKilohertz(48_000))
        assertEquals("352.8 kHz", formatKilohertz(352_800))
        assertEquals("11.025 kHz", formatKilohertz(11_025))
    }

    private fun spec(status: UsbOutputStatus) = directUsbFailureSpec(status, device = "JBL Flip 7", rateSeparator = "、")

    private fun failed(
        code: String,
        failure: UsbOutputFailure,
        sourceRate: Int? = null,
        details: DirectUsbDetails? = null,
    ) = UsbOutputStatus(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        phase = UsbOutputPhase.FAILED,
        failure = failure,
        deviceName = "JBL Flip 7",
        sourceFormat = sourceRate?.let { AudioFormatSummary(it, 2, "16-bit source") },
        decisionCode = code,
        directDetails = details,
    )
}
