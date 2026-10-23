package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouteChangeTrackerTest {
    @Test
    fun `restart suppresses only the repeated initial route`() {
        val tracker = OutputRouteChangeTracker<String>()

        assertFalse(tracker.update("speaker"))
        assertTrue(tracker.update("usb"))

        tracker.resetLifecycle()
        // The last registration's route is not carried into the next one.
        assertNull(tracker.current)
        assertFalse(tracker.update("usb"))
        assertTrue(tracker.update("bluetooth"))
    }
}
