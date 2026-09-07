package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryRateTrackerTest {
    @Test
    fun `frozen decoder observations are deduplicated and expire using capture time`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        fun capture(now: Long, observed: Long, count: Long) = tracker.add(
            instant = instant(now, now), dataSourceBytesTransferredTotal = 0, processCpuTimeMs = null,
            decoderSessionId = "session", decoderGeneration = 1,
            decoderInstant = instant(observed, observed), decoderQueuedInputBuffersTotal = count,
        )
        capture(1_000, 1_000, 10)
        capture(2_000, 2_000, 20)
        repeat(10_000) { capture(2_000, 2_000, 20) }
        assertEquals(2, tracker.retainedDecoderSampleCount)
        assertEquals(10.0, requireNotNull(tracker.decoderInputBufferRate()).value, 0.0)
        capture(8_000, 2_000, 20)
        assertEquals(0, tracker.retainedDecoderSampleCount)
        assertNull(tracker.decoderInputBufferRate())
        capture(9_000, 9_000, 30)
        assertNull(tracker.decoderInputBufferRate())
        capture(10_000, 10_000, 40)
        assertEquals(10.0, requireNotNull(tracker.decoderInputBufferRate()).value, 0.0)
    }

    @Test
    fun `decoder retention has a hard cap even if capture time stops`() {
        val tracker = TelemetryRateTracker(windowMs = 60_000)
        repeat(10_000) { sample ->
            tracker.add(instant(0, 0), 0, null, decoderSessionId = "session", decoderGeneration = 1,
                decoderInstant = instant(sample.toLong(), sample.toLong()), decoderQueuedInputBuffersTotal = sample.toLong())
        }
        assertEquals(4_096, tracker.retainedDecoderSampleCount)
    }

    @Test
    fun `rates use monotonic time and retain the source window`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(epoch = 10_000, elapsed = 1_000), 100, 20)
        tracker.add(instant(epoch = 9_000, elapsed = 2_000), 1_100, 270)

        val sourceRate = requireNotNull(tracker.dataSourceReadThroughput())
        val cpuRate = requireNotNull(tracker.processCpuPercent())

        assertEquals(8_000.0, sourceRate.value, 0.0)
        assertEquals(25.0, cpuRate.value, 0.0)
        assertEquals(1_000, sourceRate.window.durationMs)
        assertEquals(10_000, sourceRate.window.startedAtEpochMs)
        assertEquals(9_000, sourceRate.window.endedAtEpochMs)
    }

    @Test
    fun `runtime counters remain continuous across playback sessions`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(1_000, 1_000), 100, 10)
        tracker.add(instant(2_000, 2_000), 200, 20)

        assertEquals(800.0, requireNotNull(tracker.dataSourceReadThroughput()).value, 0.0)
        assertEquals(1.0, requireNotNull(tracker.processCpuPercent()).value, 0.0)
    }

    @Test
    fun `current media read bitrate resets at attribution and session boundaries`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(
            instant = instant(1_000, 1_000),
            dataSourceBytesTransferredTotal = 100,
            processCpuTimeMs = null,
            currentMediaSessionId = "session-a",
            currentMediaBytesRead = 100,
        )
        tracker.add(
            instant = instant(2_000, 2_000),
            dataSourceBytesTransferredTotal = 300,
            processCpuTimeMs = null,
            currentMediaSessionId = "session-a",
            currentMediaBytesRead = 300,
        )
        assertEquals(1_600.0, requireNotNull(tracker.currentMediaReadBitrate()).value, 0.0)

        tracker.add(
            instant = instant(3_000, 3_000),
            dataSourceBytesTransferredTotal = 500,
            processCpuTimeMs = null,
            currentMediaSessionId = "session-b",
            currentMediaBytesRead = 50,
        )
        assertNull(tracker.currentMediaReadBitrate())

        tracker.add(
            instant = instant(4_000, 4_000),
            dataSourceBytesTransferredTotal = 700,
            processCpuTimeMs = null,
            currentMediaSessionId = "session-b",
            currentMediaBytesRead = null,
        )
        tracker.add(
            instant = instant(5_000, 5_000),
            dataSourceBytesTransferredTotal = 900,
            processCpuTimeMs = null,
            currentMediaSessionId = "session-b",
            currentMediaBytesRead = 75,
        )
        assertNull(tracker.currentMediaReadBitrate())
    }

    @Test
    fun `counter rollback does not create a negative rate`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(instant(1_000, 1_000), 1_000, 100)
        tracker.add(instant(2_000, 2_000), 100, 10)

        assertNull(tracker.dataSourceReadThroughput())
        assertNull(tracker.processCpuPercent())
    }

    @Test
    fun `decoder buffer rates retain their counter windows`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(
            instant = instant(1_000, 1_000),
            dataSourceBytesTransferredTotal = 0,
            processCpuTimeMs = null,
            decoderSessionId = "session-a",
            decoderGeneration = 1,
            decoderQueuedInputBuffersTotal = 20,
            decoderRenderedOutputBuffersTotal = 10,
        )
        tracker.add(
            instant = instant(3_000, 3_000),
            dataSourceBytesTransferredTotal = 0,
            processCpuTimeMs = null,
            decoderSessionId = "session-a",
            decoderGeneration = 1,
            decoderQueuedInputBuffersTotal = 32,
            decoderRenderedOutputBuffersTotal = 18,
        )

        val input = requireNotNull(tracker.decoderInputBufferRate())
        val output = requireNotNull(tracker.decoderOutputBufferRate())

        assertEquals(6.0, input.value, 0.0)
        assertEquals(4.0, output.value, 0.0)
        assertEquals(2_000, input.window.durationMs)
    }

    @Test
    fun `decoder rates restart at playback session and decoder generation boundaries`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(
            instant = instant(1_000, 1_000),
            dataSourceBytesTransferredTotal = 100,
            processCpuTimeMs = null,
            decoderSessionId = "session-a",
            decoderGeneration = 1,
            decoderQueuedInputBuffersTotal = 100,
        )
        tracker.add(
            instant = instant(2_000, 2_000),
            dataSourceBytesTransferredTotal = 200,
            processCpuTimeMs = null,
            decoderSessionId = "session-b",
            decoderGeneration = 1,
            decoderQueuedInputBuffersTotal = 150,
        )

        assertNull(tracker.decoderInputBufferRate())
        assertEquals(800.0, requireNotNull(tracker.dataSourceReadThroughput()).value, 0.0)

        tracker.add(
            instant = instant(3_000, 3_000),
            dataSourceBytesTransferredTotal = 300,
            processCpuTimeMs = null,
            decoderSessionId = "session-b",
            decoderGeneration = 2,
            decoderQueuedInputBuffersTotal = 4,
        )
        assertNull(tracker.decoderInputBufferRate())
    }

    @Test
    fun `each rate uses its own source capture clock`() {
        val tracker = TelemetryRateTracker(windowMs = 5_000)
        tracker.add(
            instant = instant(1_000, 1_000),
            dataSourceBytesTransferredTotal = 0,
            processCpuTimeMs = 10,
            processCpuInstant = instant(1_500, 1_500),
            decoderSessionId = "session",
            decoderGeneration = 1,
            decoderInstant = instant(1_250, 1_250),
            decoderQueuedInputBuffersTotal = 0,
        )
        tracker.add(
            instant = instant(2_000, 2_000),
            dataSourceBytesTransferredTotal = 1_000,
            processCpuTimeMs = 210,
            processCpuInstant = instant(3_500, 3_500),
            decoderSessionId = "session",
            decoderGeneration = 1,
            decoderInstant = instant(3_250, 3_250),
            decoderQueuedInputBuffersTotal = 20,
        )

        assertEquals(1_000, requireNotNull(tracker.dataSourceReadThroughput()).window.durationMs)
        assertEquals(2_000, requireNotNull(tracker.processCpuPercent()).window.durationMs)
        assertEquals(2_000, requireNotNull(tracker.decoderInputBufferRate()).window.durationMs)
    }

    private fun instant(epoch: Long, elapsed: Long) = TelemetryInstant(
        epochMs = epoch,
        elapsedRealtimeMs = elapsed,
    )
}
