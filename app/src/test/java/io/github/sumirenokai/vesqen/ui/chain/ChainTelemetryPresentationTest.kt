package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainTelemetryPresentationTest {
    @Test
    fun `SI presentation applies decimal engineering prefixes without changing the reading`() {
        val sampleRate = requireNotNull(decimalEngineeringPresentation(96_000.0, TelemetryUnit.HERTZ))
        val duration = requireNotNull(decimalEngineeringPresentation(1_500.0, TelemetryUnit.MILLISECONDS))
        val decodeTime = requireNotNull(decimalEngineeringPresentation(2_500.0, TelemetryUnit.NANOSECONDS))
        val current = requireNotNull(decimalEngineeringPresentation(-1_500.0, TelemetryUnit.MILLIAMPERES))

        assertEquals(96.0, sampleRate.value, 1e-9)
        assertEquals("kHz", sampleRate.unitSymbol)
        assertEquals(1.5, duration.value, 1e-9)
        assertEquals("s", duration.unitSymbol)
        assertEquals(2.5, decodeTime.value, 1e-9)
        assertEquals("µs", decodeTime.unitSymbol)
        assertEquals(-1.5, current.value, 1e-9)
        assertEquals("A", current.unitSymbol)
        assertNull(decimalEngineeringPresentation(87.0, TelemetryUnit.PERCENT))
    }

    @Test
    fun `low power effective interval does not mark a valid snapshot stale`() {
        val snapshot = TelemetrySnapshot.empty(capturedAtElapsedRealtimeMs = 1_000)

        assertFalse(
            isTelemetrySnapshotStale(
                snapshot = snapshot,
                nowElapsedRealtimeMs = 4_999,
                refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                powerMode = TelemetryPowerMode.LOW_POWER,
            ),
        )
        assertTrue(
            isTelemetrySnapshotStale(
                snapshot = snapshot,
                nowElapsedRealtimeMs = 5_001,
                refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                powerMode = TelemetryPowerMode.LOW_POWER,
            ),
        )
    }
}
