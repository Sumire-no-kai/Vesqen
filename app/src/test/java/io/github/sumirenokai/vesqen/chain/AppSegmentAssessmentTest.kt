package io.github.sumirenokai.vesqen.chain

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
        fun text(value: String) = measured(TelemetryReading.Text(value))
        fun precision(input: TelemetryEvidence, output: String, mime: String = "audio/flac") = assess(snapshot(mapOf(
            Metrics.DECODER_INPUT_MIME to text(mime),
            Metrics.DECODER_INPUT_PCM_ENCODING to input,
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to measured(TelemetryReading.Text(output)),
        ))).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }

        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-24"), "pcm-float").status)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-16"), "pcm-16").status)
        assertEquals(AppSegmentStatus.MODIFIED, precision(text("pcm-32"), "pcm-float").status)
        assertEquals(SegmentReason.PRECISION_LOSS, precision(text("pcm-24"), "pcm-16").reason)

        // Compressed input has no PCM width: only a float AudioTrack is sure to hold the codec output.
        val compressed = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(compressed, "pcm-float", mime = "audio/mpeg").status)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(compressed, "pcm-16", mime = "audio/mpeg").status)
        // Any other unavailability is missing evidence, not compressed input.
        val unreported = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT, CapturedAtMs)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(unreported, "pcm-float").status)
    }

    @Test
    fun `compressed input is judged against the PCM its decoder actually produced`() {
        val compressed = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs)
        fun text(value: String) = measured(TelemetryReading.Text(value))
        fun precision(decoded: TelemetryEvidence?, output: String) = assess(snapshot(buildMap {
            put(Metrics.DECODER_INPUT_MIME, text("audio/mpeg"))
            put(Metrics.DECODER_INPUT_PCM_ENCODING, compressed)
            put(Metrics.PLAYBACK_AUDIO_TRACK_ENCODING, text(output))
            decoded?.let { put(Metrics.DECODER_OUTPUT_ENCODING, it) }
        })).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }

        // AOSP's MP3 decoder only outputs 16-bit (#72); passing that on as it is keeps everything.
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-16"), "pcm-16").status)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("pcm-16"), "pcm-float").status)
        assertEquals(SegmentReason.PRECISION_LOSS, precision(text("pcm-float"), "pcm-16").reason)
        // Unmeasured decoder output: float still holds anything, 16-bit is missing evidence.
        assertEquals(AppSegmentStatus.UNCHANGED, precision(null, "pcm-float").status)
        val unmeasured = precision(null, "pcm-16")
        assertEquals(AppSegmentStatus.UNKNOWN, unmeasured.status)
        assertNull(unmeasured.reason)
        assertTrue(unmeasured.issues.any { it.metricId == Metrics.DECODER_OUTPUT_ENCODING })
    }

    @Test
    fun `a lossless stream without a stated width is unchanged only when float throughout`() {
        // Media3 states no PCM width for 20-bit FLAC or FLAC in MP4. On Android 9 the OMX decoder is
        // kept on 16-bit output, which cuts 20 bits to 16 even though the decoder and AudioTrack agree.
        val unstated = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs)
        fun text(value: String) = measured(TelemetryReading.Text(value))
        fun precision(decoded: String?, output: String) = assess(snapshot(buildMap {
            put(Metrics.DECODER_INPUT_MIME, text("audio/flac"))
            put(Metrics.DECODER_INPUT_PCM_ENCODING, unstated)
            put(Metrics.PLAYBACK_AUDIO_TRACK_ENCODING, text(output))
            decoded?.let { put(Metrics.DECODER_OUTPUT_ENCODING, text(it)) }
        })).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }

        assertEquals(AppSegmentStatus.UNCHANGED, precision("pcm-float", "pcm-float").status)
        val truncatedMaybe = precision("pcm-16", "pcm-16")
        assertEquals(AppSegmentStatus.UNKNOWN, truncatedMaybe.status)
        assertTrue(truncatedMaybe.issues.any { it.metricId == Metrics.DECODER_INPUT_PCM_ENCODING })
        assertEquals(AppSegmentStatus.UNKNOWN, precision("pcm-16", "pcm-float").status)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(null, "pcm-float").status)
        // The codec decides which rule applies, so its evidence is part of the check.
        assertTrue(Metrics.DECODER_INPUT_MIME in precision("pcm-float", "pcm-float").evidence)
    }

    @Test
    fun `an unknown codec without a stated width keeps the strict rule`() {
        val unstated = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_APPLICABLE, CapturedAtMs)
        fun text(value: String) = measured(TelemetryReading.Text(value))
        fun precision(mime: TelemetryEvidence, decoded: String, output: String) = assess(snapshot(mapOf(
            Metrics.DECODER_INPUT_MIME to mime,
            Metrics.DECODER_INPUT_PCM_ENCODING to unstated,
            Metrics.DECODER_OUTPUT_ENCODING to text(decoded),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to text(output),
        ))).checks.single { it.condition == AppSegmentCondition.PCM_PRECISION }

        val unmeasured = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT, CapturedAtMs)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(unmeasured, "pcm-16", "pcm-16").status)
        assertEquals(AppSegmentStatus.UNKNOWN, precision(text("audio/x-unknown"), "pcm-16", "pcm-16").status)
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("audio/x-unknown"), "pcm-float", "pcm-float").status)
        // A known lossy codec is still judged against what its decoder produced.
        assertEquals(AppSegmentStatus.UNCHANGED, precision(text("audio/mpeg"), "pcm-16", "pcm-16").status)
    }

    @Test
    fun `a measured decoder output rate tells AAC's SBR from resampling`() {
        fun hertz(value: Long) = measured(TelemetryReading.Integer(value, TelemetryUnit.HERTZ))
        fun sampleRate(decoded: Long?, output: Long) = assess(snapshot(buildMap {
            put(Metrics.DECODER_INPUT_MIME, measured(TelemetryReading.Text("audio/mp4a-latm")))
            put(Metrics.DECODER_INPUT_SAMPLE_RATE, hertz(24_000))
            put(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, hertz(output))
            decoded?.let { put(Metrics.DECODER_OUTPUT_SAMPLE_RATE, hertz(it)) }
        })).checks.single { it.condition == AppSegmentCondition.SAMPLE_RATE }

        assertEquals(AppSegmentStatus.UNCHANGED, sampleRate(48_000, 48_000).status)
        assertEquals(AppSegmentStatus.MODIFIED, sampleRate(48_000, 44_100).status)
        assertEquals(SegmentReason.DECODER_MAY_CHANGE_FORMAT, sampleRate(null, 48_000).reason)

        // A lossless decoder must keep the file's rate: a 96 kHz FLAC decoded at 96 kHz and played at
        // 48 kHz was resampled. When the decoder reports another rate than the container, the
        // container's number is in doubt, so neither verdict holds.
        fun flac(decoded: Long) = assess(snapshot(mapOf(
            Metrics.DECODER_INPUT_MIME to measured(TelemetryReading.Text("audio/flac")),
            Metrics.DECODER_INPUT_SAMPLE_RATE to hertz(96_000),
            Metrics.DECODER_OUTPUT_SAMPLE_RATE to hertz(decoded),
            Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to hertz(48_000),
        ))).checks.single { it.condition == AppSegmentCondition.SAMPLE_RATE }
        assertEquals(AppSegmentStatus.MODIFIED, flac(96_000).status)
        assertEquals(AppSegmentStatus.UNKNOWN, flac(48_000).status)
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
