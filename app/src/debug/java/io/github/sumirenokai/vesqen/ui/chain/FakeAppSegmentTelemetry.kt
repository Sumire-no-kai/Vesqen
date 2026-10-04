package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics

/**
 * Synthetic evidence shaped like a steady 16-bit FLAC on the phone speaker: Media3 decodes to float
 * (Vesqen enables float output), and the AudioTrack facts were stamped when the track opened,
 * long before the snapshot. Debug and tests only.
 */
internal object FakeAppSegmentTelemetry {
    const val CapturedAtMs = 60_000L
    private const val TrackOpenedAtMs = 1_000L
    private val source = TelemetryDataSource(TelemetrySourceId("debug.app_segment"))

    fun measured(reading: TelemetryReading, observedAtMs: Long = CapturedAtMs) =
        TelemetryEvidence.Measured(reading, source, observedAtMs)

    fun snapshot(
        overrides: Map<TelemetryMetricId, TelemetryEvidence> = emptyMap(),
        recentEvents: List<TelemetryEvent> = emptyList(),
    ): TelemetrySnapshot {
        fun percent(value: Double) = TelemetryReading.Decimal(value, TelemetryUnit.PERCENT)
        val evidence = linkedMapOf<TelemetryMetricId, TelemetryEvidence>(
            Metrics.PROCESSING_SPEED to measured(percent(100.0)),
            Metrics.PROCESSING_PITCH to measured(percent(100.0)),
            Metrics.PROCESSING_PLAYER_VOLUME to measured(percent(100.0)),
            Metrics.DECODER_INPUT_MIME to measured(TelemetryReading.Text("audio/flac"), TrackOpenedAtMs),
            Metrics.DECODER_INPUT_SAMPLE_RATE to measured(TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ), TrackOpenedAtMs),
            Metrics.DECODER_INPUT_CHANNEL_COUNT to measured(TelemetryReading.Integer(2, TelemetryUnit.COUNT), TrackOpenedAtMs),
            Metrics.DECODER_INPUT_PCM_ENCODING to measured(TelemetryReading.Text("pcm-16"), TrackOpenedAtMs),
            Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to measured(TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ), TrackOpenedAtMs),
            Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK to measured(TelemetryReading.Text("0xc"), TrackOpenedAtMs),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to measured(TelemetryReading.Text("pcm-float"), TrackOpenedAtMs),
            Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text("phone_speaker")),
        )
        listOf(
            Metrics.PROCESSING_SKIP_SILENCE, Metrics.PROCESSING_REPLAY_GAIN_ACTIVE,
            Metrics.PROCESSING_EQUALIZER_ACTIVE, Metrics.PROCESSING_LOUDNESS_ACTIVE,
            Metrics.PROCESSING_CROSSFADE_ACTIVE, Metrics.PROCESSING_APP_DSP_ACTIVE,
        ).forEach { evidence[it] = measured(TelemetryReading.Flag(false)) }
        evidence.putAll(overrides)
        return TelemetrySnapshot(
            capturedAtEpochMs = CapturedAtMs,
            playbackSessionId = "debug-session",
            metrics = evidence.map { (id, value) -> TelemetryMetric(id, Metrics.descriptor(id).section, value) },
            recentEvents = recentEvents,
        )
    }
}
