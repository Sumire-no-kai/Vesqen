package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputSignalEventSpecTest {
    @Test
    fun `route and USB events bind the active playback session`() {
        OutputTelemetrySignal.entries.forEach { signal ->
            assertEquals(
                "playback-session",
                outputSignalEventSpec(signal, "playback-session").playbackSessionId,
            )
        }
    }

    @Test
    fun `route and USB events remain global without active playback`() {
        OutputTelemetrySignal.entries.forEach { signal ->
            assertNull(outputSignalEventSpec(signal, null).playbackSessionId)
        }
    }
}
