package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCallbackLifecycleGateTest {
    @Test
    fun `callbacks are accepted only within each started lifecycle`() {
        val gate = OutputCallbackLifecycleGate()

        assertFalse(gate.isAccepting)

        gate.start()
        assertTrue(gate.isAccepting)

        gate.stop()
        assertFalse(gate.isAccepting)

        gate.start()
        assertTrue(gate.isAccepting)
    }
}
