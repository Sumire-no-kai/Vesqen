package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryRateTrackerTest {
    @Test
    fun `rates use monotonic time and retain the source window`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(epoch = 10_000, elapsed = 1_000), "session", 100, 20)
        tracker.add(instant(epoch = 9_000, elapsed = 2_000), "session", 1_100, 270)

        val sourceRate = requireNotNull(tracker.sourceReadBitrate())
        val cpuRate = requireNotNull(tracker.processCpuPercent())

        assertEquals(8_000.0, sourceRate.value, 0.0)
        assertEquals(25.0, cpuRate.value, 0.0)
        assertEquals(1_000, sourceRate.window.durationMs)
        assertEquals(10_000, sourceRate.window.startedAtEpochMs)
        assertEquals(9_000, sourceRate.window.endedAtEpochMs)
    }

    @Test
    fun `session changes clear rate history`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(1_000, 1_000), "first", 100, 10)
        tracker.add(instant(2_000, 2_000), "second", 200, 20)

        assertNull(tracker.sourceReadBitrate())
        assertNull(tracker.processCpuPercent())
    }

    @Test
    fun `counter rollback does not create a negative rate`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(1_000, 1_000), "session", 1_000, 100)
        tracker.add(instant(2_000, 2_000), "session", 100, 10)

        assertNull(tracker.sourceReadBitrate())
        assertNull(tracker.processCpuPercent())
    }

    private fun instant(epoch: Long, elapsed: Long) = TelemetryInstant(
        epochMs = epoch,
        elapsedRealtimeMs = elapsed,
    )
}
