package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TelemetrySnapshotTest {
    private val playerSource = TelemetryDataSource(
        id = TelemetrySourceId("media3.player"),
        detail = "Player analytics events",
    )

    @Test
    fun `derived evidence retains its raw source window and calculation`() {
        val evidence = TelemetryEvidence.Derived(
            reading = TelemetryReading.Integer(1_536_000, TelemetryUnit.BITS_PER_SECOND),
            source = playerSource,
            observedAtEpochMs = 6_000,
            window = TelemetryWindow(startedAtEpochMs = 1_000, endedAtEpochMs = 6_000),
            calculation = "compressed bytes read * 8 / window seconds",
        )

        assertEquals(TelemetryConfidence.DERIVED, evidence.confidence)
        assertEquals(5_000, evidence.window.durationMs)
        assertEquals("media3.player", evidence.source.id.value)
        assertEquals("compressed bytes read * 8 / window seconds", evidence.calculation)
    }

    @Test
    fun `unavailable evidence cannot retain a stale reading`() {
        val evidence = TelemetryEvidence.Unavailable(
            reason = TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            observedAtEpochMs = 10_000,
            source = TelemetryDataSource(TelemetrySourceId("android.decoder")),
        )

        assertEquals(TelemetryConfidence.UNAVAILABLE, evidence.confidence)
        assertNull(evidence.reading)
    }

    @Test
    fun `snapshot provides stable section lookup and rejects duplicate metric ids`() {
        val metricId = TelemetryMetricId("source.sample_rate")
        val metric = TelemetryMetric(
            id = metricId,
            section = TelemetrySection.SOURCE,
            evidence = TelemetryEvidence.Measured(
                reading = TelemetryReading.Integer(96_000, TelemetryUnit.HERTZ),
                source = TelemetryDataSource(TelemetrySourceId("media.metadata")),
                observedAtEpochMs = 20_000,
            ),
        )
        val snapshot = TelemetrySnapshot(
            capturedAtEpochMs = 20_000,
            playbackSessionId = "session-1",
            metrics = listOf(metric),
        )

        assertEquals(metric, snapshot.metric(metricId))
        assertEquals(listOf(metric), snapshot.metricsIn(TelemetrySection.SOURCE))
        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(
                capturedAtEpochMs = 20_000,
                metrics = listOf(metric, metric),
            )
        }
    }

    @Test
    fun `event history must be chronological and cannot claim a future event`() {
        val earlier = TelemetryEvent(
            sequence = 1,
            kind = TelemetryEventKind.FORMAT_CHANGED,
            severity = TelemetryEventSeverity.INFO,
            occurredAtEpochMs = 1_000,
            code = "playback.format_changed",
        )
        val later = earlier.copy(sequence = 2, occurredAtEpochMs = 2_000)

        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(capturedAtEpochMs = 2_000, recentEvents = listOf(later, earlier))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(capturedAtEpochMs = 1_500, recentEvents = listOf(earlier, later))
        }
    }

    @Test
    fun `observation exposes only supported refresh intervals and valid selections`() {
        assertEquals(
            listOf(250L, 500L, 1_000L, 2_000L, 5_000L),
            TelemetryRefreshInterval.entries.map(TelemetryRefreshInterval::milliseconds),
        )
        assertEquals(TelemetryRefreshInterval.ONE_SECOND, TelemetryObservation().refreshInterval)
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetricSelection.Explicit(emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryObservation(derivedWindowMs = 0)
        }
    }

    @Test
    fun `stable identifiers reject presentation labels and empty segments`() {
        assertThrows(IllegalArgumentException::class.java) { TelemetryMetricId("Sample rate") }
        assertThrows(IllegalArgumentException::class.java) { TelemetryMetricId("source..rate") }
        assertThrows(IllegalArgumentException::class.java) { TelemetrySourceId("media3") }
    }
}
