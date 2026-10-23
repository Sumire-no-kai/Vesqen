package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPcmDecoderPathTest {
    @Test
    fun `raw PCM the renderer passed on without a codec plays without a decoder`() {
        assertTrue(playsRawPcmWithoutCodec("audio/raw", decoderName = null, outputFromCodec = false))
    }

    @Test
    fun `a compressed stream whose codec is still starting is not a bypass`() {
        assertFalse(playsRawPcmWithoutCodec("audio/flac", decoderName = null, outputFromCodec = null))
        // FLAC states its PCM width, so even a codec-less output report (offload) does not make it raw.
        assertFalse(playsRawPcmWithoutCodec("audio/flac", decoderName = null, outputFromCodec = false))
    }

    @Test
    fun `raw PCM is a bypass only once its output was reported without a codec`() {
        // Before the first sample is rendered, or while a platform raw decoder starts.
        assertFalse(playsRawPcmWithoutCodec("audio/raw", decoderName = null, outputFromCodec = null))
        // Raw PCM the audio sink cannot take goes through a platform decoder.
        assertFalse(playsRawPcmWithoutCodec("audio/raw", decoderName = "c2.android.raw.decoder", outputFromCodec = true))
        assertFalse(playsRawPcmWithoutCodec("audio/raw", decoderName = "c2.android.raw.decoder", outputFromCodec = false))
    }

    @Test
    fun `codec flags do not apply without a codec and wait for one that is starting`() {
        assertEquals(
            TelemetryUnavailableReason.NOT_APPLICABLE,
            codecFlagUnavailableReason(hasActivePlayback = true, rawPcmWithoutCodec = true, sdkInt = 28, decoderName = null),
        )
        assertEquals(
            TelemetryUnavailableReason.NOT_APPLICABLE,
            codecFlagUnavailableReason(hasActivePlayback = true, rawPcmWithoutCodec = true, sdkInt = 35, decoderName = null),
        )
        assertEquals(
            TelemetryUnavailableReason.WARMING_UP,
            codecFlagUnavailableReason(hasActivePlayback = true, rawPcmWithoutCodec = false, sdkInt = 35, decoderName = null),
        )
        assertEquals(
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
            codecFlagUnavailableReason(hasActivePlayback = true, rawPcmWithoutCodec = false, sdkInt = 28, decoderName = "c2.android.flac.decoder"),
        )
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            codecFlagUnavailableReason(hasActivePlayback = false, rawPcmWithoutCodec = false, sdkInt = 35, decoderName = null),
        )
    }
}
