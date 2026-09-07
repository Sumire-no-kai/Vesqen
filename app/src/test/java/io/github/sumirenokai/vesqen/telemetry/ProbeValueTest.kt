package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeValueTest {
    @Test
    fun `a successful empty enumeration remains available evidence`() {
        val result = captureProbeValue { emptyList<String>() }

        assertEquals(emptyList<String>(), result.value)
        assertNull(result.unavailableReason)
    }

    @Test
    fun `an enumeration exception is unavailable rather than a measured empty list`() {
        val result = captureProbeValue<List<String>> { error("query failed") }

        assertNull(result.value)
        assertEquals(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE, result.unavailableReason)
    }
}
