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
            observedAtElapsedRealtimeMs = 16_000,
            window = TelemetryWindow(
                startedAtEpochMs = 11_000,
                endedAtEpochMs = 6_000,
                startedAtElapsedRealtimeMs = 11_000,
                endedAtElapsedRealtimeMs = 16_000,
            ),
            calculationId = "rate.bytes_per_window",
            inputMetricIds = setOf(TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED),
            operands = mapOf(
                "bytes.delta" to 960_000.0,
                "window.seconds" to 5.0,
            ),
        )

        assertEquals(TelemetryConfidence.DERIVED, evidence.confidence)
        assertEquals(5_000, evidence.window.durationMs)
        assertEquals("media3.player", evidence.source.id.value)
        assertEquals("rate.bytes_per_window", evidence.calculationId)
        assertEquals(960_000.0, evidence.operands.getValue("bytes.delta"), 0.0)
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
        val metricId = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE
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
        val later = earlier.copy(
            sequence = 2,
            occurredAtEpochMs = 2_000,
            occurredAtElapsedRealtimeMs = 2_000,
        )

        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(capturedAtEpochMs = 2_000, recentEvents = listOf(later, earlier))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(capturedAtEpochMs = 1_500, recentEvents = listOf(earlier, later))
        }
    }

    @Test
    fun `monotonic time keeps windows valid when wall clock moves backwards`() {
        val window = TelemetryWindow(
            startedAtEpochMs = 20_000,
            endedAtEpochMs = 10_000,
            startedAtElapsedRealtimeMs = 1_000,
            endedAtElapsedRealtimeMs = 2_500,
        )

        assertEquals(1_500, window.durationMs)
    }

    @Test
    fun `metric schema rejects wrong sections units types and ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetric(
                id = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                section = TelemetrySection.PLAYBACK,
                evidence = measured(TelemetryReading.Integer(48_000, TelemetryUnit.HERTZ)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetric(
                id = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                section = TelemetrySection.SOURCE,
                evidence = measured(TelemetryReading.Integer(48_000, TelemetryUnit.MILLISECONDS)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetric(
                id = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                section = TelemetrySection.SOURCE,
                evidence = measured(TelemetryReading.Text("48 kHz")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetric(
                id = TelemetryMetricCatalog.PROCESSING_PLAYER_VOLUME,
                section = TelemetrySection.PROCESSING,
                evidence = measured(TelemetryReading.Decimal(101.0, TelemetryUnit.PERCENT)),
            )
        }
    }

    @Test
    fun `snapshot retains every input needed by a derived value`() {
        val sourceBytes = TelemetryMetric(
            id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED,
            section = TelemetrySection.PROCESS,
            evidence = measured(TelemetryReading.Integer(1_024, TelemetryUnit.BYTES)),
        )
        val bitrate = TelemetryMetric(
            id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
            section = TelemetrySection.PROCESS,
            evidence = TelemetryEvidence.Derived(
                reading = TelemetryReading.Decimal(8_192.0, TelemetryUnit.BITS_PER_SECOND),
                source = playerSource,
                observedAtEpochMs = 2_000,
                observedAtElapsedRealtimeMs = 2_000,
                window = TelemetryWindow(1_000, 2_000),
                calculationId = "rate.bytes_per_window",
                inputMetricIds = setOf(sourceBytes.id),
                operands = mapOf("bytes.delta" to 1_024.0, "window.seconds" to 1.0),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            TelemetrySnapshot(capturedAtEpochMs = 2_000, metrics = listOf(bitrate))
        }
        TelemetrySnapshot(capturedAtEpochMs = 2_000, metrics = listOf(sourceBytes, bitrate))
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
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryObservation(derivedWindowMs = 60_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryMetricSelection.Explicit(setOf(TelemetryMetricId("unknown.metric")))
        }
    }

    @Test
    fun `stable identifiers reject presentation labels and empty segments`() {
        assertThrows(IllegalArgumentException::class.java) { TelemetryMetricId("Sample rate") }
        assertThrows(IllegalArgumentException::class.java) { TelemetryMetricId("source..rate") }
        assertThrows(IllegalArgumentException::class.java) { TelemetrySourceId("media3") }
    }

    private fun measured(reading: TelemetryReading) = TelemetryEvidence.Measured(
        reading = reading,
        source = playerSource,
        observedAtEpochMs = 1_000,
    )
}
