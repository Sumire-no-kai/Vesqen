package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.chain.AppSegmentCondition
import io.github.sumirenokai.vesqen.chain.assessAppSegment
import io.github.sumirenokai.vesqen.telemetry.*
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import org.junit.Assert.*
import org.junit.Test

class DeviceReportAppSegmentTest {
    @Test fun `report projects every assessment field and references the same evidence`() {
        for (route in listOf("bluetooth_a2dp", "phone_speaker", "wired_or_usb")) {
            for (mime in listOf("audio/flac", "audio/mpeg")) {
                val snapshot = snapshot(route, mime)
                val expected = assessAppSegment(snapshot, snapshot.capturedAtElapsedRealtimeMs, 0)
                // Generating much later must not turn a captured assessment into stale evidence.
                val document = DeviceReportPrivacy.document(reportData().copy(
                    generatedAtEpochMs = 900_000, telemetry = snapshot,
                ), DeviceReportOptions(chainEvidence = true))
                val segment = document.getValue("appSegment") as Map<*, *>
                assertEquals(expected.status.name, segment["status"])
                assertEquals(expected.sourceCompression.name, segment["sourceCompression"])
                assertEquals(expected.route.name, segment["route"])
                assertEquals(expected.bluetooth?.status?.name, (segment["bluetooth"] as? Map<*, *>)?.get("status"))
                val checks = segment["checks"] as List<*>
                expected.checks.zip(checks).forEach { (check, raw) ->
                    val actual = raw as Map<*, *>
                    assertEquals(check.condition.name, actual["condition"])
                    assertEquals(check.status.name, actual["status"])
                    assertEquals(check.reason?.name, actual["reason"])
                    assertEquals(check.evidence.keys.map { it.value }.sorted(), actual["metricIds"])
                    assertEquals(check.issues.map { mapOf("metricId" to it.metricId.value, "reason" to it.reason.name) }, actual["issues"])
                }
                val metrics = (document["chainEvidence"] as Map<*, *>)["metrics"] as List<*>
                val declaration = metrics.map { it as Map<*, *> }.single { it["id"] == Metrics.ROUTE_OUTPUT_DECLARATION.value }
                assertEquals(mapOf("value" to "SYSTEM MIXED"), declaration["reading"])
                assertEquals("MEASURED", declaration["confidence"])
                assertEquals(1_000L, declaration["observedAtElapsedRealtimeMs"])
            }
        }
    }

    @Test fun `modified unavailable estimated missing and transition reasons survive without event text`() {
        val baseline = snapshot()
        val variants = listOf(
            baseline.copy(metrics = baseline.metrics.map { if (it.id == Metrics.PROCESSING_SPEED) metric(it.id, TelemetryReading.Decimal(125.0, TelemetryUnit.PERCENT)) else it }) to "VALUE_CHANGED",
            baseline.copy(metrics = baseline.metrics.filterNot { it.id == Metrics.PROCESSING_SPEED }) to "MISSING",
            baseline.copy(metrics = baseline.metrics.map { if (it.id == Metrics.PROCESSING_SPEED) it.copy(evidence = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.WARMING_UP, 1_000)) else it }) to "UNAVAILABLE",
            baseline.copy(metrics = baseline.metrics.map { if (it.id == Metrics.PROCESSING_SPEED) it.copy(evidence = TelemetryEvidence.Estimated(it.evidence.reading!!, source, 1_000, "library.cached_metadata")) else it }) to "INSUFFICIENT_CONFIDENCE",
            baseline.copy(recentEvents = listOf(TelemetryEvent(1, TelemetryEventKind.SEEK_COMPLETED,
                TelemetryEventSeverity.INFO, 59_900, code = "test.seek", playbackSessionId = "/private/secret.flac"))) to "TRANSITION_IN_PROGRESS",
        )
        variants.forEach { (snapshot, reason) ->
            val report = DeviceReportGenerator.generate(reportData().copy(telemetry = snapshot), DeviceReportOptions(chainEvidence = true))
            assertTrue(report.previewText.contains("\"reason\":\"$reason\""))
            assertFalse(report.previewText.contains("secret.flac"))
            assertArrayEquals(report.previewText.toByteArray(Charsets.UTF_8), report.copyBytes())
        }
    }

    @Test fun `absent snapshot explicitly leaves every condition and route unknown`() {
        val document = DeviceReportPrivacy.document(reportData(), DeviceReportOptions(chainEvidence = true))
        val segment = document["appSegment"] as Map<*, *>
        assertEquals("UNKNOWN", segment["status"])
        assertEquals("UNKNOWN", segment["sourceCompression"])
        assertEquals("UNKNOWN", segment["route"])
        assertNull(segment["bluetooth"])
        val checks = segment["checks"] as List<*>
        assertEquals(AppSegmentCondition.entries.size, checks.size)
        checks.forEach { raw ->
            val check = raw as Map<*, *>
            assertEquals("UNKNOWN", check["status"])
            assertEquals("MISSING", check["reason"])
        }
        assertEquals(listOf(mapOf("metricId" to Metrics.ROUTE_SELECTED_SYSTEM_TYPE.value, "reason" to "MISSING")), segment["routeIssues"])
        assertEquals(mapOf("unavailableReason" to "NOT_SAMPLED"), document["chainEvidence"])
    }

    private val source = TelemetryDataSource(TelemetrySourceId("media3.player"))
    private fun metric(id: TelemetryMetricId, reading: TelemetryReading) =
        TelemetryMetric(id, Metrics.descriptor(id).section, TelemetryEvidence.Measured(reading, source, 1_000))

    private fun snapshot(route: String = "phone_speaker", mime: String = "audio/flac"): TelemetrySnapshot {
        val readings = linkedMapOf<TelemetryMetricId, TelemetryReading>(
            Metrics.ROUTE_SELECTED_SYSTEM_TYPE to TelemetryReading.Text(route),
            Metrics.ROUTE_OUTPUT_DECLARATION to TelemetryReading.Text("SYSTEM MIXED"),
            Metrics.DECODER_INPUT_MIME to TelemetryReading.Text(mime),
            Metrics.DECODER_INPUT_PCM_ENCODING to TelemetryReading.Text("pcm-16"),
            Metrics.DECODER_INPUT_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
            Metrics.DECODER_INPUT_CHANNEL_COUNT to TelemetryReading.Integer(2, TelemetryUnit.COUNT),
            Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
            Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK to TelemetryReading.Text("0xc"),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to TelemetryReading.Text("pcm-float"),
        )
        listOf(Metrics.PROCESSING_SPEED, Metrics.PROCESSING_PITCH, Metrics.PROCESSING_PLAYER_VOLUME).forEach {
            readings[it] = TelemetryReading.Decimal(100.0, TelemetryUnit.PERCENT)
        }
        listOf(Metrics.PROCESSING_SKIP_SILENCE, Metrics.PROCESSING_REPLAY_GAIN_ACTIVE, Metrics.PROCESSING_EQUALIZER_ACTIVE,
            Metrics.PROCESSING_LOUDNESS_ACTIVE, Metrics.PROCESSING_CROSSFADE_ACTIVE, Metrics.PROCESSING_APP_DSP_ACTIVE).forEach {
            readings[it] = TelemetryReading.Flag(false)
        }
        return TelemetrySnapshot(60_000, metrics = readings.map { (id, reading) -> metric(id, reading) })
    }
}
