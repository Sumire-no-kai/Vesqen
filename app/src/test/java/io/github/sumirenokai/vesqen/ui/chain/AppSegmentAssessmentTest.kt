package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.CapturedAtMs
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.measured
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.snapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSegmentAssessmentTest {
    @Test
    fun `a steady neutral stream is unchanged even though its AudioTrack facts are old`() {
        val result = assess(snapshot())
        assertEquals(AppSegmentStatus.UNCHANGED, result.status)
        assertEquals(SourceCompression.LOSSLESS, result.sourceCompression)
        assertEquals(RouteKind.OTHER, result.route)
        assertNull(result.bluetooth)
        assertTrue(result.checks.all { it.status == AppSegmentStatus.UNCHANGED })
    }

    @Test
    fun `each processing change is reported on its own`() {
        val changes = mapOf(
            Metrics.PROCESSING_SPEED to percent(125.0),
            Metrics.PROCESSING_PITCH to percent(90.0),
            Metrics.PROCESSING_PLAYER_VOLUME to percent(50.0),
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
            val modified = result.checks.single { it.status == AppSegmentStatus.MODIFIED }
            assertTrue(id.value, modified.evidence.containsKey(id))
        }
        val all = assess(snapshot(changes.mapValues { measured(it.value) }))
        assertEquals(changes.size, all.checks.count { it.status == AppSegmentStatus.MODIFIED })
    }

    @Test
    fun `a stale or future snapshot cannot support any verdict`() {
        val stale = assessAppSegment(snapshot(), CapturedAtMs + 3_001, maxSnapshotAgeMs = 3_000)
        assertEquals(AppSegmentStatus.UNKNOWN, stale.status)
        assertTrue(stale.checks.all { it.status == AppSegmentStatus.UNKNOWN })
        assertTrue(stale.checks.flatMap { it.issues }.any { it.reason == SegmentReason.EXPIRED })

        val future = assessAppSegment(snapshot(), CapturedAtMs - 1, maxSnapshotAgeMs = 3_000)
        assertEquals(AppSegmentStatus.UNKNOWN, future.status)
        assertTrue(future.checks.flatMap { it.issues }.any { it.reason == SegmentReason.INVALID_TIME })
    }

    @Test
    fun `estimated, unavailable or missing evidence leaves the check unknown with its reason`() {
        val estimated = assess(snapshot(mapOf(Metrics.PROCESSING_SPEED to TelemetryEvidence.Estimated(
            reading = percent(100.0), source = source, observedAtEpochMs = CapturedAtMs, methodId = "test.guess",
        ))))
        assertUnknown(estimated, AppSegmentCondition.SPEED, SegmentReason.INSUFFICIENT_CONFIDENCE)

        val unavailable = assess(snapshot(mapOf(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to TelemetryEvidence.Unavailable(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK, CapturedAtMs,
        ))))
        assertUnknown(unavailable, AppSegmentCondition.SAMPLE_RATE, SegmentReason.UNAVAILABLE)

        val missing = assessAppSegment(
            TelemetrySnapshot(CapturedAtMs, metrics = snapshot().metrics.filterNot { it.id == Metrics.PROCESSING_PITCH }),
            CapturedAtMs,
            maxSnapshotAgeMs = 3_000,
        )
        assertUnknown(missing, AppSegmentCondition.PITCH, SegmentReason.MISSING)
    }

    @Test
    fun `a recent track change, seek or output re-open holds the verdict back until it settles`() {
        listOf(
            TelemetryEventKind.MEDIA_ITEM_CHANGED, TelemetryEventKind.FORMAT_CHANGED,
            TelemetryEventKind.DECODER_INITIALIZED, TelemetryEventKind.OUTPUT_INITIALIZED,
            TelemetryEventKind.SEEK_COMPLETED,
        ).forEach { kind ->
            val settling = assess(snapshot(recentEvents = listOf(event(kind, CapturedAtMs - 500))))
            val transition = settling.checks.single { it.condition == AppSegmentCondition.TRACK_TRANSITION }
            assertEquals(kind.name, SegmentReason.TRANSITION_IN_PROGRESS, transition.reason)
            assertEquals(kind.name, AppSegmentStatus.UNKNOWN, settling.status)
        }
        val settled = assess(snapshot(recentEvents = listOf(event(TelemetryEventKind.MEDIA_ITEM_CHANGED, CapturedAtMs - TransitionSettleMs))))
        assertEquals(AppSegmentStatus.UNCHANGED, settled.status)
        val unrelated = assess(snapshot(recentEvents = listOf(event(TelemetryEventKind.UNDERRUN, CapturedAtMs - 100))))
        assertEquals(AppSegmentStatus.UNCHANGED, unrelated.status)
    }

    @Test
    fun `precision follows the measured input width into the AudioTrack`() {
        fun precision(input: TelemetryEvidence, output: String) = assess(snapshot(mapOf(
            Metrics.DECODER_INPUT_PCM_ENCODING to input,
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to measured(TelemetryReading.Text(output)),
        ))).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }
        fun text(value: String) = measured(TelemetryReading.Text(value))

        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-24"), "pcm-float").status)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-16"), "pcm-16").status)
        assertEquals(AppSegmentStatus.MODIFIED, precision(text("pcm-32"), "pcm-float").status)
        assertEquals(SegmentReason.PRECISION_LOSS, precision(text("pcm-24"), "pcm-16").reason)

        // Compressed input has no PCM width: only a float AudioTrack is sure to hold the codec output.
        val compressed = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(compressed, "pcm-float").status)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(compressed, "pcm-16").status)
        // Any other unavailability is missing evidence, not compressed input.
        val unreported = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT, CapturedAtMs)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(unreported, "pcm-float").status)
    }

    @Test
    fun `pcm precision table`() {
        val widths = listOf("pcm-8", "pcm-16", "pcm-24", "pcm-32", "pcm-float")
        val kept = setOf(
            "pcm-8>pcm-8", "pcm-8>pcm-16", "pcm-8>pcm-24", "pcm-8>pcm-32", "pcm-8>pcm-float",
            "pcm-16>pcm-16", "pcm-16>pcm-24", "pcm-16>pcm-32", "pcm-16>pcm-float",
            "pcm-24>pcm-24", "pcm-24>pcm-32", "pcm-24>pcm-float",
            "pcm-32>pcm-32", "pcm-float>pcm-float",
        )
        widths.forEach { input ->
            widths.forEach { output -> assertEquals("$input>$output", "$input>$output" in kept, preservesPcmPrecision(input, output)) }
        }
        assertNull(preservesPcmPrecision("e-ac3", "pcm-float"))
    }

    @Test
    fun `only AAC may change rate or layout inside the codec`() {
        val rate = mapOf(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to measured(TelemetryReading.Integer(88_200, TelemetryUnit.HERTZ)))
        assertEquals(AppSegmentStatus.MODIFIED, assess(snapshot(rate)).status)

        val aac = rate + compressedInput("audio/mp4a-latm")
        val aacRate = assess(snapshot(aac)).checks.single { it.condition == AppSegmentCondition.SAMPLE_RATE }
        assertEquals(AppSegmentStatus.UNKNOWN, aacRate.status)
        assertEquals(SegmentReason.DECODER_MAY_CHANGE_FORMAT, aacRate.reason)

        val opus = rate + compressedInput("audio/opus")
        assertEquals(AppSegmentStatus.MODIFIED, assess(snapshot(opus)).status)
    }

    @Test
    fun `source compression comes from the decoder input and never gates the verdict`() {
        listOf("audio/flac", "audio/alac", "audio/raw").forEach {
            assertEquals(it, SourceCompression.LOSSLESS, assess(snapshot(mapOf(Metrics.DECODER_INPUT_MIME to measured(TelemetryReading.Text(it))))).sourceCompression)
        }
        listOf("audio/mpeg", "audio/opus", "audio/vorbis").forEach {
            val lossy = assess(snapshot(compressedInput(it)))
            assertEquals(it, SourceCompression.LOSSY, lossy.sourceCompression)
            assertEquals(it, AppSegmentStatus.UNCHANGED, lossy.status)
        }
        val unknownCodec = assess(snapshot(mapOf(Metrics.DECODER_INPUT_MIME to measured(TelemetryReading.Text("audio/x-unknown")))))
        assertEquals(SourceCompression.UNKNOWN, unknownCodec.sourceCompression)
        assertEquals(AppSegmentStatus.UNCHANGED, unknownCodec.status)
    }

    @Test
    fun `bluetooth adds the system segment, other routes do not, and an unknown route says so`() {
        fun route(type: String) = assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text(type)))))
        val bluetooth = route("bluetooth")
        assertEquals(RouteKind.BLUETOOTH, bluetooth.route)
        assertNotNull(bluetooth.bluetooth)
        assertNull(bluetooth.bluetooth!!.reportedCodecEvidence)
        assertEquals(BluetoothSegmentStatus.LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC, bluetooth.bluetooth!!.status)
        listOf("phone_speaker", "wired_or_usb", "other").forEach {
            assertEquals(it, RouteKind.OTHER, route(it).route)
            assertNull(it, route(it).bluetooth)
        }
        val unknown = assess(snapshot(mapOf(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to TelemetryEvidence.Unavailable(
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION, CapturedAtMs,
        ))))
        assertEquals(RouteKind.UNKNOWN, unknown.route)
        assertNull(unknown.bluetooth)
        assertTrue(unknown.routeIssues.isNotEmpty())
        // The route is the system's side; it never changes the Vesqen-segment verdict.
        assertEquals(AppSegmentStatus.UNCHANGED, unknown.status)
    }

    @Test
    fun `the exported metric list covers everything the assessment reads`() {
        val read = assess(snapshot()).let { result ->
            result.checks.flatMap { it.evidence.keys } + result.routeEvidence.keys
        }.toSet()
        assertTrue((read - AppSegmentMetricIds.toSet()).toString(), AppSegmentMetricIds.containsAll(read))
    }

    private val source = TelemetryDataSource(TelemetrySourceId("test.app_segment"))

    private fun assess(snapshot: TelemetrySnapshot) = assessAppSegment(snapshot, CapturedAtMs + 100, maxSnapshotAgeMs = 3_000)

    private fun percent(value: Double) = TelemetryReading.Decimal(value, TelemetryUnit.PERCENT)

    private fun compressedInput(mime: String): Map<TelemetryMetricId, TelemetryEvidence> = mapOf(
        Metrics.DECODER_INPUT_MIME to measured(TelemetryReading.Text(mime)),
        Metrics.DECODER_INPUT_PCM_ENCODING to TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs),
    )

    private fun event(kind: TelemetryEventKind, atMs: Long) =
        TelemetryEvent(sequence = atMs, kind = kind, severity = TelemetryEventSeverity.INFO, occurredAtEpochMs = atMs, code = "test.event")

    private fun assertUnknown(result: AppSegmentAssessment, condition: AppSegmentCondition, reason: SegmentReason) {
        val check = result.checks.single { it.condition == condition }
        assertEquals(AppSegmentStatus.UNKNOWN, check.status)
        assertTrue(check.issues.toString(), check.issues.any { it.reason == reason })
        assertEquals(AppSegmentStatus.UNKNOWN, result.status)
    }
}
