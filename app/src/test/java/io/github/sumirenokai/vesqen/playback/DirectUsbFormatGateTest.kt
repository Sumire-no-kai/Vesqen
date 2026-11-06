package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D10's format layers for the own USB output, against the architecture's table in "格式与承诺". */
class DirectUsbFormatGateTest {
    private fun plan(bits: Int, rate: Int = 48_000) = StreamingPlan(
        interfaceNumber = 2,
        alternateSetting = if (bits == 16) 1 else 2,
        endpointAddress = 3,
        endpointSetsSampleRate = true,
        sampleRateHz = rate,
        channels = 2,
        subslotBytes = bits / 8,
        bitResolution = bits,
        serviceIntervalUs = 1000,
        maxPacketBytes = 288,
        packetsPerUrb = 32,
        urbCount = 3,
        ringFrames = 24_000,
    )

    private fun source(mime: String? = MimeTypes.AUDIO_FLAC, bits: Int? = 16, rate: Int? = 48_000, channels: Int? = 2) =
        TelemetrySourceFacts(codecMime = mime, bitDepth = bits, sampleRateHz = rate, channelCount = channels)

    private fun decoded(encoding: Int = C.ENCODING_PCM_FLOAT, rate: Int = 48_000, channels: Int = 2, delay: Int = 0, padding: Int = 0): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setEncoderDelay(delay)
            .setEncoderPadding(padding)
            .build()

    private fun reason(decision: DirectUsbFormatGate.Decision) = (decision as DirectUsbFormatGate.Decision.Rejected).failure.reason

    @Test
    fun `a 16-bit source plays decoded as int16 or float`() {
        assertEquals(DirectUsbFormatGate.Decision.Accepted(16), DirectUsbFormatGate.check(source(), decoded(C.ENCODING_PCM_16BIT), plan(16)))
        assertEquals(DirectUsbFormatGate.Decision.Accepted(16), DirectUsbFormatGate.check(source(), decoded(), plan(16)))
    }

    @Test
    fun `a 16-bit source plays on a 24-bit setting with zeros below it`() {
        assertEquals(DirectUsbFormatGate.Decision.Accepted(16), DirectUsbFormatGate.check(source(), decoded(), plan(24)))
    }

    @Test
    fun `a 24-bit source plays decoded as int24, int32 or float`() {
        listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT).forEach { encoding ->
            assertEquals(DirectUsbFormatGate.Decision.Accepted(24), DirectUsbFormatGate.check(source(bits = 24), decoded(encoding), plan(24)))
        }
    }

    @Test
    fun `a 24-bit source decoded to 16 bits has lost precision`() {
        val decision = DirectUsbFormatGate.check(source(bits = 24), decoded(C.ENCODING_PCM_16BIT), plan(24))
        assertEquals(Reason.DECODER_PRECISION_LOST, reason(decision))
    }

    @Test
    fun `an output encoding that is not linear PCM has lost precision`() {
        assertEquals(Reason.DECODER_PRECISION_LOST, reason(DirectUsbFormatGate.check(source(), decoded(C.ENCODING_PCM_8BIT), plan(16))))
    }

    @Test
    fun `a 24-bit source cannot go to a 16-bit setting`() {
        assertEquals(Reason.NO_MATCHING_DIRECT_FORMAT, reason(DirectUsbFormatGate.check(source(bits = 24), decoded(), plan(16))))
    }

    @Test
    fun `a source at another rate than the setting does not play`() {
        assertEquals(Reason.NO_MATCHING_DIRECT_FORMAT, reason(DirectUsbFormatGate.check(source(rate = 44_100), decoded(rate = 44_100), plan(16))))
    }

    @Test
    fun `a decoder that changed the rate or channels does not play`() {
        assertEquals(Reason.NO_MATCHING_DIRECT_FORMAT, reason(DirectUsbFormatGate.check(source(), decoded(rate = 44_100), plan(16))))
        assertEquals(Reason.NO_MATCHING_DIRECT_FORMAT, reason(DirectUsbFormatGate.check(source(), decoded(channels = 1), plan(16))))
    }

    @Test
    fun `missing facts are unknown and say which`() {
        val decision = DirectUsbFormatGate.check(source(bits = null), decoded(), plan(16)) as DirectUsbFormatGate.Decision.Rejected
        assertEquals(Reason.SOURCE_FORMAT_UNKNOWN, decision.failure.reason)
        assertTrue(decision.failure.detail, decision.failure.detail.contains("bit depth"))
        assertEquals(Reason.SOURCE_FORMAT_UNKNOWN, reason(DirectUsbFormatGate.check(null, decoded(), plan(16))))
    }

    @Test
    fun `lossy and unknown codecs do not play`() {
        listOf(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, "audio/x-made-up").forEach { mime ->
            assertEquals(mime, Reason.SOURCE_FORMAT_UNSUPPORTED, reason(DirectUsbFormatGate.check(source(mime = mime), decoded(), plan(16))))
        }
    }

    @Test
    fun `FLAC, WAV, ALAC and AIFF play`() {
        listOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_ALAC, "audio/x-aiff", "audio/aiff").forEach { mime ->
            assertEquals(mime, DirectUsbFormatGate.Decision.Accepted(16), DirectUsbFormatGate.check(source(mime = mime), decoded(), plan(16)))
        }
    }

    @Test
    fun `20-bit and 32-bit sources do not play`() {
        listOf(20, 32).forEach { bits ->
            assertEquals("$bits", Reason.SOURCE_FORMAT_UNSUPPORTED, reason(DirectUsbFormatGate.check(source(bits = bits), decoded(), plan(24))))
        }
    }

    @Test
    fun `a source that needs trimming does not play`() {
        val decision = DirectUsbFormatGate.check(source(), decoded(delay = 2112, padding = 0), plan(16)) as DirectUsbFormatGate.Decision.Rejected
        assertEquals(Reason.SOURCE_FORMAT_UNSUPPORTED, decision.failure.reason)
        assertTrue(decision.failure.detail, decision.failure.detail.contains("2112"))
        assertEquals(Reason.SOURCE_FORMAT_UNSUPPORTED, reason(DirectUsbFormatGate.check(source(), decoded(padding = 576), plan(16))))
    }
}
