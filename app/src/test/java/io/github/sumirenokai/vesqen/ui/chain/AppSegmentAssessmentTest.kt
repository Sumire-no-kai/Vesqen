package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSegmentAssessmentTest {
    @Test
    fun `each processing change is reported independently and all failures are retained`() {
        val changes = mapOf(
            Metrics.PROCESSING_SPEED to decimal(125.0),
            Metrics.PROCESSING_PITCH to decimal(90.0),
            Metrics.PROCESSING_PLAYER_VOLUME to decimal(50.0),
            Metrics.PROCESSING_SKIP_SILENCE to TelemetryReading.Flag(true),
            Metrics.PROCESSING_REPLAY_GAIN_ACTIVE to TelemetryReading.Flag(true),
            Metrics.PROCESSING_EQUALIZER_ACTIVE to TelemetryReading.Flag(true),
            Metrics.PROCESSING_LOUDNESS_ACTIVE to TelemetryReading.Flag(true),
            Metrics.PROCESSING_CROSSFADE_ACTIVE to TelemetryReading.Flag(true),
            Metrics.PROCESSING_APP_DSP_ACTIVE to TelemetryReading.Flag(true),
            Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to TelemetryReading.Integer(48_000, TelemetryUnit.HERTZ),
            Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK to TelemetryReading.Text("0x4"),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to TelemetryReading.Text("pcm-8"),
        )
        changes.forEach { (id, reading) ->
            val result = assess(snapshot(mapOf(id to measured(reading))))
            assertEquals(id.value, AppSegmentStatus.MODIFIED, result.status)
            assertEquals(id.value, 1, result.checks.count { it.status == AppSegmentStatus.MODIFIED })
            assertTrue(result.checks.single { it.status == AppSegmentStatus.MODIFIED }.evidence.containsKey(id))
        }
        assertEquals(changes.size, assess(snapshot(changes.mapValues { measured(it.value) })).checks.count {
            it.status == AppSegmentStatus.MODIFIED
        })
    }

    @Test
    fun `lossless and lossy codecs stay distinct without claiming containers are lossless`() {
        listOf("audio/flac", "audio/alac", "audio/raw").forEach { mime ->
            assertEquals(SourceCompression.LOSSLESS, withMime(mime).sourceCompression)
        }
        listOf("audio/mpeg", "audio/mp4a-latm", "audio/opus", "audio/vorbis").forEach { mime ->
            assertEquals(SourceCompression.LOSSY, withMime(mime).sourceCompression)
        }
        listOf("audio/wav", "audio/aiff", "unknown").forEach { mime ->
            assertEquals(SourceCompression.UNKNOWN, withMime(mime).sourceCompression)
        }
    }

    @Test
    fun `precision rules cover every supported PCM pair`() {
        val formats = listOf("pcm-8", "pcm-16", "pcm-24", "pcm-32", "pcm-float")
        val expected = listOf(
            listOf(true, true, true, true, true),
            listOf(false, true, true, true, true),
            listOf(false, false, true, true, true),
            listOf(false, false, false, true, false),
            listOf(false, false, false, false, true),
        )
        formats.forEachIndexed { i, input -> formats.forEachIndexed { j, output ->
            val result = assess(snapshot(mapOf(
                Metrics.DECODER_OUTPUT_ENCODING to measured(TelemetryReading.Text(input)),
                Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to measured(TelemetryReading.Text(output)),
            ))).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }
            assertEquals("$input -> $output", if (expected[i][j]) AppSegmentStatus.UNCHANGED else AppSegmentStatus.MODIFIED, result.status)
        } }
        assertNull(preservesPcmPrecision("aac", "aac"))
    }

    @Test
    fun `every missing stale or estimated operand prevents a neutral check`() {
        snapshot().metrics.filter { it.id != Metrics.ROUTE_SELECTED_SYSTEM_TYPE }.forEach { metric ->
            val missing = assess(snapshot().copy(metrics = snapshot().metrics.filter { it.id != metric.id }))
            assertTrue(metric.id.value, missing.checks.flatMap { it.issues }.contains(SegmentEvidenceIssue(metric.id, SegmentReason.MISSING)))
            val stale = assess(snapshot(mapOf(metric.id to measured(metric.evidence.reading!!, 899))))
            assertTrue(metric.id.value, stale.checks.flatMap { it.issues }.contains(SegmentEvidenceIssue(metric.id, SegmentReason.EXPIRED)))
            val estimated = TelemetryEvidence.Estimated(metric.evidence.reading!!, source, 1_000, "test.estimate")
            val uncertain = assess(snapshot(mapOf(metric.id to estimated)))
            assertTrue(metric.id.value, uncertain.checks.flatMap { it.issues }.contains(SegmentEvidenceIssue(metric.id, SegmentReason.INSUFFICIENT_CONFIDENCE)))
            assertEquals(AppSegmentStatus.UNKNOWN, uncertain.status)
        }
    }

    @Test
    fun `age uses monotonic observation time and rejects future evaluation`() {
        val boundary = measured(decimal(100.0), 900).copy(observedAtEpochMs = 1)
        assertEquals(AppSegmentStatus.UNCHANGED, assess(snapshot(mapOf(Metrics.PROCESSING_SPEED to boundary))).checks.first().status)
        assertTrue(assessAppSegment(snapshot(), 999, 100).checks.first().issues.any { it.reason == SegmentReason.INVALID_TIME })
        assertTrue(assessAppSegment(snapshot(), 1_101, 100).checks.first().issues.any { it.reason == SegmentReason.EXPIRED })
    }

    @Test
    fun `temporary unavailable reasons and original evidence survive without string parsing`() {
        listOf(TelemetryUnavailableReason.WARMING_UP, TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE).forEach { reason ->
            val evidence = TelemetryEvidence.Unavailable(reason, 1_000, source, "diagnostic detail")
            val result = assess(snapshot(mapOf(Metrics.DECODER_OUTPUT_ENCODING to evidence)))
            assertEquals(AppSegmentStatus.UNKNOWN, result.status)
            assertSame(evidence, result.checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }.evidence[Metrics.DECODER_OUTPUT_ENCODING])
        }
    }

    @Test
    fun `neutral settings cannot attest focus gain or track transition and fades`() {
        val result = assess(snapshot())
        assertEquals(AppSegmentStatus.UNKNOWN, result.status)
        assertEquals(listOf(SegmentReason.FOCUS_GAIN_NOT_OBSERVABLE, SegmentReason.TRACK_TRANSITION_NOT_OBSERVABLE),
            result.checks.filter { it.status == AppSegmentStatus.UNKNOWN }.map { it.reason })
        assertTrue(result.checks.filter { it.condition !in setOf(AppSegmentCondition.FOCUS_GAIN, AppSegmentCondition.TRACK_TRANSITION) }
            .all { it.status == AppSegmentStatus.UNCHANGED })
    }

    @Test
    fun `bluetooth second segment follows only reliable fresh selected route`() {
        listOf("bluetooth", "bluetooth_a2dp", "bluetooth_sco", "ble_headset", "ble_speaker", "ble_broadcast", "hearing_aid").forEach { type ->
            val result = assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text(type)))))
            assertEquals(BluetoothSegmentStatus.LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC, result.bluetooth?.status)
            assertNull(result.bluetooth?.reportedCodecEvidence)
        }
        listOf("built_in_speaker", "wired_headphones", "usb_device", "unknown").forEach { type ->
            assertNull(assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text(type))))).bluetooth)
        }
        val stale = assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text("bluetooth_a2dp"), 899))))
        assertNull(stale.bluetooth)
        assertEquals(SegmentReason.EXPIRED, stale.routeIssues.single().reason)
        val missing = assess(snapshot().copy(metrics = snapshot().metrics.filter { it.id != Metrics.ROUTE_SELECTED_SYSTEM_TYPE }))
        assertNull(missing.bluetooth)
        assertEquals(SegmentReason.MISSING, missing.routeIssues.single().reason)
    }

    @Test
    fun `derived evidence keeps dependencies and cannot upgrade an estimated operand`() {
        val id = Metrics.PROCESSING_SPEED
        val operand = Metrics.PROCESSING_PITCH
        val derived = TelemetryEvidence.Derived(decimal(100.0), source, 1_000,
            TelemetryWindow(900, 1_000), "test.identity", setOf(operand), mapOf("input.value" to 100.0))
        val good = assess(snapshot(mapOf(id to derived))).checks.first()
        assertSame(derived, good.evidence[id])
        assertEquals(AppSegmentStatus.UNCHANGED, good.status)
        assertTrue(good.evidence.containsKey(operand))
        val bad = assess(snapshot(mapOf(id to derived, operand to TelemetryEvidence.Estimated(decimal(100.0), source, 1_000, "test.estimate")))).checks.first()
        assertEquals(AppSegmentStatus.UNKNOWN, bad.status)
        val cycle = derived.copy(inputMetricIds = setOf(id))
        assertTrue(assess(snapshot(mapOf(id to cycle))).checks.first().issues.any { it.reason == SegmentReason.DEPENDENCY_CYCLE })
    }

    @Test
    fun `unrecognized PCM or channel strings never pass by equality`() {
        val result = assess(snapshot(mapOf(
            Metrics.DECODER_OUTPUT_ENCODING to measured(TelemetryReading.Text("unknown")),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to measured(TelemetryReading.Text("unknown")),
            Metrics.DECODER_OUTPUT_CHANNEL_CONFIG to measured(TelemetryReading.Text("stereo")),
            Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK to measured(TelemetryReading.Text("stereo")),
        )))
        assertEquals(2, result.checks.count { it.reason == SegmentReason.UNSUPPORTED_VALUE })
    }

    @Test
    fun `source and output declarations cannot substitute decoder evidence or select bluetooth`() {
        val original = snapshot()
        val extra = mapOf(
            Metrics.ROUTE_OUTPUT_DECLARATION to TelemetryReading.Text("SYSTEM MIXED"),
            Metrics.ROUTE_CONNECTED_TYPES to TelemetryReading.Text("bluetooth_a2dp"),
            Metrics.ROUTE_ANTICIPATED_TYPE to TelemetryReading.Text("bluetooth_a2dp"),
            Metrics.SOURCE_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
        ).map { (id, value) -> TelemetryMetric(id, Metrics.descriptor(id).section, measured(value)) }
        val input = original.copy(metrics = original.metrics.filter { it.id != Metrics.DECODER_OUTPUT_SAMPLE_RATE } + extra)
        val result = assess(input)
        assertNull(result.bluetooth)
        assertEquals(AppSegmentStatus.UNKNOWN, result.checks.single { it.condition == AppSegmentCondition.SAMPLE_RATE }.status)
        assertEquals(extra.first(), input.metric(Metrics.ROUTE_OUTPUT_DECLARATION))
        val estimated = TelemetryEvidence.Estimated(TelemetryReading.Text("bluetooth_a2dp"), source, 1_000, "test.estimate")
        val uncertain = assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to estimated)))
        assertNull(uncertain.bluetooth)
        assertEquals(SegmentReason.INSUFFICIENT_CONFIDENCE, uncertain.routeIssues.single().reason)
    }

    private fun withMime(mime: String) = assess(snapshot(mapOf(Metrics.SOURCE_CODEC_MIME to measured(TelemetryReading.Text(mime)))))
    private fun assess(snapshot: TelemetrySnapshot) = assessAppSegment(snapshot, 1_000, 100)
    private fun decimal(value: Double) = TelemetryReading.Decimal(value, TelemetryUnit.PERCENT)
    private val source = TelemetryDataSource(TelemetrySourceId("test.app_segment"))
    private fun measured(reading: TelemetryReading, time: Long = 1_000) = TelemetryEvidence.Measured(reading, source, time)
    private fun snapshot(overrides: Map<TelemetryMetricId, TelemetryEvidence> = emptyMap()) =
        FakeAppSegmentTelemetry.snapshot(overrides)
}
