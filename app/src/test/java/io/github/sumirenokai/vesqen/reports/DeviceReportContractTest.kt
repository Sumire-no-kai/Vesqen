package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.audio.AudioOutputType
import io.github.sumirenokai.vesqen.library.M1_AUDIO_FORMAT_MATRIX
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.playback.telemetryLabel
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryWindow
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** The server validates reports against contracts/device-report; these files must match the app. */
class DeviceReportContractTest {
    @Test fun `checked-in vocabulary matches the app`() =
        assertContract("vocabulary.json", DeviceReportContract.encode(DeviceReportContract.vocabulary()))

    @Test fun `checked-in sample report matches the generator`() =
        assertContract("sample-report.json", DeviceReportGenerator.generate(sampleData(), DeviceReportOptions(true, true, true, true, true)).previewText)

    @Test fun `values the app produces pass the report filter`() {
        val produced = buildList {
            AudioOutputType.entries.forEach { add(text(Metrics.ROUTE_SELECTED_SYSTEM_TYPE, it.name.lowercase())) }
            OutputDeclaration.entries.forEach { add(text(Metrics.ROUTE_OUTPUT_DECLARATION, it.telemetryLabel)) }
            M1_AUDIO_FORMAT_MATRIX.forEach { format ->
                add(text(Metrics.SOURCE_CODEC_LABEL, format.displayName))
                format.mimeHints.forEach { add(text(Metrics.SOURCE_CODEC_MIME, it)) }
            }
            listOf("pcm-16", "pcm-float", "e-ac3", "dts-hd", "encoding-1234").forEach {
                add(text(Metrics.PLAYBACK_AUDIO_TRACK_ENCODING, it))
            }
        }
        produced.forEach { metric ->
            val report = DeviceReportGenerator.generate(
                reportData().copy(telemetry = TelemetrySnapshot(200, metrics = listOf(metric))),
                DeviceReportOptions(chainEvidence = true),
            ).previewText
            assertFalse("${metric.id.value}: $report", report.contains("UNREVIEWED_OR_PRIVATE_TEXT"))
        }
        val estimated = DeviceReportGenerator.generate(
            reportData().copy(telemetry = TelemetrySnapshot(200, metrics = listOf(TelemetryMetric(
                Metrics.SOURCE_CODEC_LABEL, Metrics.descriptor(Metrics.SOURCE_CODEC_LABEL).section,
                TelemetryEvidence.Estimated(TelemetryReading.Text("FLAC"), source("library.metadata"), 100, "library.cached_metadata"),
            )))),
            DeviceReportOptions(chainEvidence = true),
        ).previewText
        assertFalse(estimated, estimated.contains("\"methodRedacted\":true"))
    }

    private fun assertContract(name: String, expected: String) {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val checkedIn = File(root, "contracts/device-report/$name")
        if (!checkedIn.exists() || checkedIn.readText() != expected) {
            val generated = File(root, "app/build/contracts/device-report/$name").apply {
                parentFile.mkdirs()
                writeText(expected)
            }
            fail("contracts/device-report/$name is out of date. Review and copy $generated over it.")
        }
    }

    private fun source(id: String) = TelemetryDataSource(TelemetrySourceId(id))

    private fun text(id: TelemetryMetricId, value: String) = TelemetryMetric(
        id, Metrics.descriptor(id).section, TelemetryEvidence.Measured(TelemetryReading.Text(value), source("android.audio_route"), 100),
    )

    /** One of each group and evidence kind, so the server's tests see every shape a report can take. */
    private fun sampleData(): DeviceReportData {
        fun measured(id: TelemetryMetricId, reading: TelemetryReading, from: String) =
            TelemetryMetric(id, Metrics.descriptor(id).section, TelemetryEvidence.Measured(reading, source(from), 1_000, 900))
        val metrics = listOf(
            measured(Metrics.ROUTE_SELECTED_SYSTEM_TYPE, TelemetryReading.Text("phone_speaker"), "android.system_media_route"),
            measured(Metrics.ROUTE_OUTPUT_DECLARATION, TelemetryReading.Text("SYSTEM MIXED"), "vesqen.output_coordinator"),
            measured(Metrics.ROUTE_SELECTED_SYSTEM_NAME, TelemetryReading.Text("Phone"), "android.system_media_route"),
            measured(Metrics.PLAYBACK_AUDIO_TRACK_ENCODING, TelemetryReading.Text("pcm-float"), "media3.audio_track"),
            measured(Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ), "media3.audio_track"),
            measured(Metrics.PROCESSING_SKIP_SILENCE, TelemetryReading.Flag(false), "media3.player"),
            measured(Metrics.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED, TelemetryReading.Integer(
                1_764_000, Metrics.descriptor(Metrics.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED).unit!!), "media3.data_source"),
            TelemetryMetric(Metrics.SOURCE_CODEC_LABEL, Metrics.descriptor(Metrics.SOURCE_CODEC_LABEL).section,
                TelemetryEvidence.Estimated(TelemetryReading.Text("FLAC"), source("library.metadata"), 1_000, "library.cached_metadata", observedAtElapsedRealtimeMs = 900)),
            TelemetryMetric(Metrics.PROCESS_DATA_SOURCE_READ_THROUGHPUT, Metrics.descriptor(Metrics.PROCESS_DATA_SOURCE_READ_THROUGHPUT).section,
                TelemetryEvidence.Derived(
                    TelemetryReading.Decimal(176_400.0, Metrics.descriptor(Metrics.PROCESS_DATA_SOURCE_READ_THROUGHPUT).unit!!),
                    source("media3.data_source"), 1_000, TelemetryWindow(0, 1_000, 0, 900),
                    "rate.data_source_bytes_per_window", setOf(Metrics.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED),
                    mapOf("bytes.delta" to 176_400.0, "window.seconds" to 1.0),
                    observedAtElapsedRealtimeMs = 900,
                )),
            TelemetryMetric(Metrics.DECODER_OUTPUT_ENCODING, Metrics.descriptor(Metrics.DECODER_OUTPUT_ENCODING).section,
                TelemetryEvidence.Unavailable(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM, 1_000, observedAtElapsedRealtimeMs = 900)),
        )
        val history = ErrorHistorySnapshot(listOf(
            ReportErrorEvent(ReportErrorKind.PLAYBACK, 500, 400, platformCode = 4001,
                format = FailedTrackFormat("flac", "audio/flac", 96_000, 24, 2, codecLabel = "FLAC"), fileName = "Track 01.flac"),
            ReportErrorEvent(ReportErrorKind.STRICT_OUTPUT, 600, 500,
                strictFailure = UsbOutputFailure.entries.first(), strictOrigin = UsbOutputFailureOrigin.entries.first(),
                format = FailedTrackFormat(sampleRateHz = 96_000, bitDepth = 24, channelCount = 2, source = ErrorFormatSource.STRICT_OUTPUT_REQUEST)),
            ReportErrorEvent(ReportErrorKind.PROCESS_EXIT, 700, platformCode = 4),
        ))
        return DeviceReportData(
            DeviceReportBasic("1.0.0-beta.2", 9, "release", "vivo", "V2171A", "15", 35, "PD2171_A_15.0.20.1"),
            generatedAtEpochMs = 1_000,
            telemetry = TelemetrySnapshot(1_000, 900, "sample-session", metrics),
            history = history,
        )
    }
}
