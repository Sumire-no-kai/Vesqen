package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryObservationTest {
    @Test
    fun `low power observers retain their two second delivery floor`() {
        val lowPower = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            derivedWindowMs = 2_000,
            powerMode = TelemetryPowerMode.LOW_POWER,
        )
        val standard = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            derivedWindowMs = 2_000,
            powerMode = TelemetryPowerMode.STANDARD,
        )

        assertEquals(2_000, lowPower.effectiveIntervalMs())
        assertEquals(250, standard.effectiveIntervalMs())
    }
}
