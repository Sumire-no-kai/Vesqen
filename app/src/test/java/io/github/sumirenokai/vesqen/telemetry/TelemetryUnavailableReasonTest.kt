package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryUnavailableReasonTest {
    @Test
    fun `SoC model distinguishes unsupported Android from missing device data`() {
        assertEquals(
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
            socModelUnavailableReason(30),
        )
        assertEquals(
            TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT,
            socModelUnavailableReason(31),
        )
    }

    @Test
    fun `PCM rate reason distinguishes lifecycle format and passthrough states`() {
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            pcmDataRateUnavailableReason(false, false, false),
        )
        assertEquals(
            TelemetryUnavailableReason.WARMING_UP,
            pcmDataRateUnavailableReason(true, false, false),
        )
        assertEquals(
            TelemetryUnavailableReason.NOT_APPLICABLE,
            pcmDataRateUnavailableReason(true, true, false),
        )
        assertEquals(
            TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT,
            pcmDataRateUnavailableReason(true, true, true),
        )
    }
}
