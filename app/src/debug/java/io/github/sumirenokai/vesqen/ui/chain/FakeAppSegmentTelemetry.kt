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

/** Synthetic evidence for Debug previews/tests only. It deliberately cannot prove absence of ducking. */
internal object FakeAppSegmentTelemetry {
    private val source = TelemetryDataSource(TelemetrySourceId("debug.app_segment"))
    private fun decimal(value: Double) = TelemetryReading.Decimal(value, TelemetryUnit.PERCENT)
    private fun measured(reading: TelemetryReading) = TelemetryEvidence.Measured(reading, source, 1_000)
    fun snapshot(overrides: Map<TelemetryMetricId, TelemetryEvidence> = emptyMap()): TelemetrySnapshot {
        val readings = linkedMapOf<TelemetryMetricId, TelemetryReading>(
            Metrics.PROCESSING_SPEED to decimal(100.0), Metrics.PROCESSING_PITCH to decimal(100.0),
            Metrics.PROCESSING_PLAYER_VOLUME to decimal(100.0),
            Metrics.DECODER_OUTPUT_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
            Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
            Metrics.DECODER_OUTPUT_ENCODING to TelemetryReading.Text("pcm-16"),
            Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to TelemetryReading.Text("pcm-16"),
            Metrics.DECODER_OUTPUT_CHANNEL_CONFIG to TelemetryReading.Text("0xc"),
            Metrics.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK to TelemetryReading.Text("0xc"),
            Metrics.SOURCE_CODEC_MIME to TelemetryReading.Text("audio/flac"),
            Metrics.ROUTE_SELECTED_SYSTEM_TYPE to TelemetryReading.Text("built_in_speaker"),
        )
        listOf(Metrics.PROCESSING_SKIP_SILENCE, Metrics.PROCESSING_REPLAY_GAIN_ACTIVE,
            Metrics.PROCESSING_EQUALIZER_ACTIVE, Metrics.PROCESSING_LOUDNESS_ACTIVE,
            Metrics.PROCESSING_CROSSFADE_ACTIVE, Metrics.PROCESSING_APP_DSP_ACTIVE).forEach { readings[it] = TelemetryReading.Flag(false) }
        return TelemetrySnapshot(1_000, playbackSessionId = "test-session", metrics = readings.map { (id, reading) ->
            TelemetryMetric(id, Metrics.descriptor(id).section, overrides[id] ?: measured(reading))
        })
    }
}
