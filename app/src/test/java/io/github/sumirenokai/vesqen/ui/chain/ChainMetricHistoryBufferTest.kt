package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryValueKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ChainMetricHistoryBufferTest {
    private val descriptor = TelemetryMetricCatalog.descriptors.first {
        it.chartable && it.valueKind == TelemetryValueKind.DECIMAL
    }
    private val source = TelemetryDataSource(TelemetrySourceId("test.measurement"))

    @Test
    fun `history is bounded and replaces duplicate observation timestamps`() {
        val history = ChainMetricHistoryBuffer(maximumPoints = 3)

        history.add(snapshot(session = "one", elapsed = 1, value = 1.0))
        history.add(snapshot(session = "one", elapsed = 2, value = 2.0))
        history.add(snapshot(session = "one", elapsed = 2, value = 20.0))
        history.add(snapshot(session = "one", elapsed = 3, value = 3.0))
        history.add(snapshot(session = "one", elapsed = 4, value = 4.0))

        assertEquals(listOf(20.0, 3.0, 4.0), history.points(descriptor.id).map { it.value })
    }

    @Test
    fun `a new playback session clears old chart points`() {
        val history = ChainMetricHistoryBuffer(maximumPoints = 3)

        history.add(snapshot(session = "one", elapsed = 1, value = 1.0))
        history.add(snapshot(session = "two", elapsed = 2, value = 2.0))

        assertEquals(listOf(2.0), history.points(descriptor.id).map { it.value })
    }

    @Test
    fun `a backwards monotonic snapshot clears the current chart history`() {
        val history = ChainMetricHistoryBuffer(maximumPoints = 5)

        history.add(snapshot(session = "one", elapsed = 2_000, value = 2.0))
        history.add(snapshot(session = "one", elapsed = 3_000, value = 3.0))
        history.add(snapshot(session = "one", elapsed = 1_000, value = 1.0))

        assertEquals(listOf(1.0), history.points(descriptor.id).map { it.value })
    }

    @Test
    fun `history retention uses monotonic duration instead of sample count`() {
        val history = ChainMetricHistoryBuffer(maximumDurationMs = 1_000, maximumPoints = 100)

        history.add(snapshot(session = "one", elapsed = 100, value = 1.0))
        history.add(snapshot(session = "one", elapsed = 900, value = 2.0))
        history.add(snapshot(session = "one", elapsed = 2_000, value = 3.0))

        assertEquals(listOf(3.0), history.points(descriptor.id).map { it.value })
    }

    @Test
    fun `shortening retention immediately crops existing points by monotonic time`() {
        val history = ChainMetricHistoryBuffer(maximumDurationMs = 10_000)
        history.add(snapshot(session = "one", elapsed = 1_000, value = 1.0))
        history.add(snapshot(session = "one", elapsed = 4_000, value = 2.0))
        history.add(snapshot(session = "one", elapsed = 5_000, value = 3.0))

        history.updateRetentionDuration(1_000)

        assertEquals(listOf(2.0, 3.0), history.points(descriptor.id).map { it.value })
    }

    @Test
    fun `chart timing is proportional and gaps or evidence changes split lines`() {
        val points = listOf(
            point(elapsed = 1_000, sourceId = "test.one"),
            point(elapsed = 1_250, sourceId = "test.one"),
            point(elapsed = 2_000, sourceId = "test.one"),
            point(elapsed = 2_250, sourceId = "test.two"),
        )

        assertEquals(0.2f, elapsedChartFraction(1_250, 1_000, 2_250), 0.001f)
        assertEquals(0.8f, elapsedChartFraction(2_000, 1_000, 2_250), 0.001f)
        assertEquals(listOf(2, 1, 1), segmentChartHistory(points, expectedCadenceMs = 250).map { it.size })
    }

    private fun point(elapsed: Long, sourceId: String) = ChainHistoryPoint(
        elapsedRealtimeMs = elapsed,
        value = elapsed.toDouble(),
        confidence = TelemetryConfidence.MEASURED,
        sourceId = sourceId,
        windowDurationMs = null,
    )

    private fun snapshot(session: String, elapsed: Long, value: Double): TelemetrySnapshot = TelemetrySnapshot(
        capturedAtEpochMs = elapsed,
        capturedAtElapsedRealtimeMs = elapsed,
        playbackSessionId = session,
        metrics = listOf(
            TelemetryMetric(
                id = descriptor.id,
                section = descriptor.section,
                evidence = TelemetryEvidence.Measured(
                    reading = TelemetryReading.Decimal(value, requireNotNull(descriptor.unit)),
                    source = source,
                    observedAtEpochMs = elapsed,
                    observedAtElapsedRealtimeMs = elapsed,
                ),
            ),
        ),
    )
}
