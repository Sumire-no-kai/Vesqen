package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.telemetry.*
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import org.junit.Assert.*
import org.junit.Test

class DeviceReportGeneratorTest {
    @Test fun `all option combinations isolate groups and filenames while retaining basic information`() {
        for (mask in 0 until 32) {
            val options = DeviceReportOptions(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0, mask and 16 != 0)
            val report = DeviceReportGenerator.generate(reportData(), options).previewText
            assertTrue(report.contains("\"basic\""))
            assertEquals(options.audioCapabilities, report.contains("\"audioCapabilities\""))
            assertEquals(options.chainEvidence, report.contains("\"chainEvidence\""))
            assertEquals(options.chainEvidence, report.contains("\"appSegment\""))
            assertEquals(options.recentErrors, report.contains("\"recentErrors\""))
            assertEquals(options.failedTrackFormats, report.contains("\"failedTrackFormats\""))
            assertEquals(options.failedTrackFormats && options.includeFileNames, report.contains("private-track.flac"))
            assertFalse(report.contains("private-folder"))
        }
        assertEquals(DeviceReportOptions(false, false, false, false, false), DeviceReportOptions())
    }

    @Test fun `capabilities alone cannot export current source or process data`() {
        val telemetry = TelemetrySnapshot(200, metrics = listOf(
            textMetric(Metrics.SOURCE_CONTAINER, TelemetrySection.SOURCE, "flac"),
            textMetric(Metrics.ROUTE_SELECTED_SYSTEM_TYPE, TelemetrySection.ROUTE, "bluetooth_a2dp"),
            textMetric(Metrics.PROCESS_SOC_MODEL, TelemetrySection.PROCESS, "private-track.flac"),
        ))
        val report = DeviceReportGenerator.generate(reportData().copy(telemetry = telemetry), DeviceReportOptions(audioCapabilities = true)).previewText
        assertTrue(report.contains("bluetooth_a2dp"))
        assertFalse(report.contains("source.container"))
        assertFalse(report.contains("process.soc_model"))
    }

    @Test fun `raw paths names exception details and event history never pass the export boundary`() {
        val secrets = listOf("private-track.flac", "private-folder", "Secret Headphones", "12:34:56:78:9A:BC", "hidden.example")
        val detail = "Failed to read /private-folder/private-track.flac C:\\private-folder\\private-track.flac content://hidden.example/music Secret Headphones 12:34:56:78:9A:BC"
        val source = TelemetryDataSource(TelemetrySourceId("private-track.flac"), detail)
        val telemetry = TelemetrySnapshot(200, playbackSessionId = detail, metrics = listOf(
            textMetric(Metrics.SOURCE_CONTAINER, TelemetrySection.SOURCE, detail, source),
            textMetric(Metrics.ROUTE_SELECTED_SYSTEM_NAME, TelemetrySection.ROUTE, "Secret Headphones", source),
            textMetric(Metrics.ROUTE_BLUETOOTH_CONNECTED_NAMES, TelemetrySection.ROUTE, detail, source),
            textMetric(Metrics.PLAYBACK_LAST_ERROR_CODE, TelemetrySection.PLAYBACK, detail, source),
            TelemetryMetric(Metrics.PROCESS_SOC_MODEL, TelemetrySection.PROCESS, TelemetryEvidence.Estimated(
                TelemetryReading.Text(detail), source, 100, "private-track.flac")),
            TelemetryMetric(Metrics.DECODER_OUTPUT_ENCODING, TelemetrySection.DECODER, TelemetryEvidence.Unavailable(
                TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM, 100, source, detail)),
            TelemetryMetric(Metrics.USB_DEVICE_INVENTORY, TelemetrySection.USB, TelemetryEvidence.Measured(
                TelemetryReading.UsbInventory(UsbInventoryReading(
                    listOf(UsbHostDeviceReading(detail, detail, detail, 1, 2, true, listOf(UsbAudioInterfaceReading(1, 2, 3)))),
                    listOf(UsbAudioOutputEndpointReading(detail, detail, detail, listOf(48000), false, listOf(2), false, listOf(detail), false)),
                )), source, 100)),
        ), recentEvents = listOf(TelemetryEvent(1, TelemetryEventKind.ERROR, TelemetryEventSeverity.ERROR, 100, code = "private-track.flac", playbackSessionId = detail)))
        val data = reportData().copy(telemetry = telemetry, basic = reportData().basic.copy(model = detail), history = ErrorHistorySnapshot(listOf(
            error().copy(format = FailedTrackFormat(detail, detail, 48000, 24, 2)),
        )))
        val report = DeviceReportGenerator.generate(data, DeviceReportOptions(true, true, true, true, false)).previewText
        secrets.forEach { assertFalse("Leaked $it", report.contains(it)) }
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("recentEvents"))
        assertFalse(report.contains("playbackSessionId"))
        assertTrue(report.contains("UNREVIEWED_OR_PRIVATE_TEXT"))
        assertTrue(report.contains("NOT_EXPOSED_BY_PLATFORM"))
        assertTrue(report.contains("\"sourceRedacted\":true"))
        assertTrue(report.contains("\"privacyFiltered\":true"))
    }

    @Test fun `filename opt in allows a basename only and still rejects identifiers`() {
        val inputs = listOf("/private-folder/private-track.flac", "C:\\private-folder\\private-track.flac", "content://provider/private-folder/private-track.flac")
        inputs.forEach { input ->
            val report = DeviceReportGenerator.generate(reportData().copy(history = ErrorHistorySnapshot(listOf(error().copy(fileName = input)))), DeviceReportOptions(failedTrackFormats = true, includeFileNames = true)).previewText
            assertTrue(report.contains("private-track.flac"))
            assertFalse(report.contains("private-folder"))
            assertFalse(report.contains("provider"))
        }
        listOf("12:34:56:78:9A:BC.flac", "%2Fprivate%2Ftrack.flac", "line\nname.flac", ".", "..").forEach {
            assertNull(DeviceReportPrivacy.fileName(it))
        }
        val data = reportData().copy(telemetry = TelemetrySnapshot(200, metrics = listOf(
            textMetric(Metrics.ROUTE_BLUETOOTH_CONNECTED_NAMES, TelemetrySection.ROUTE, "bluetooth_a2dp: Secret Headphones"),
        )), history = ErrorHistorySnapshot(listOf(error().copy(fileName = "Secret Headphones.flac"))))
        assertFalse(DeviceReportGenerator.generate(data, DeviceReportOptions(true, true, true, true, true)).previewText.contains("Secret Headphones"))
    }

    @Test fun `route names never null the model or ordinary file names`() {
        // AOSP names the built-in speaker route after Build.MODEL; "none" means no Bluetooth device.
        val data = reportData().copy(
            basic = reportData().basic.copy(model = "Nothing Phone (2)"),
            telemetry = TelemetrySnapshot(200, metrics = listOf(
                textMetric(Metrics.ROUTE_SELECTED_SYSTEM_NAME, TelemetrySection.ROUTE, "Phone"),
                textMetric(Metrics.ROUTE_ANTICIPATED_NAME, TelemetrySection.ROUTE, "Nothing Phone (2)"),
                textMetric(Metrics.ROUTE_BLUETOOTH_CONNECTED_NAMES, TelemetrySection.ROUTE, "none"),
            )),
            history = ErrorHistorySnapshot(listOf(error().copy(fileName = "Saxophone.flac"))),
        )
        val report = DeviceReportGenerator.generate(data, DeviceReportOptions(true, true, true, true, true)).previewText
        assertTrue(report.contains("\"model\":\"Nothing Phone (2)\""))
        assertTrue(report.contains("Saxophone.flac"))
    }

    @Test fun `all catalog text fields reject filenames paths and MAC addresses`() {
        val sensitive = "error /private-folder/private-track.flac C:\\private-folder\\private-track.flac 12:34:56:78:9A:BC"
        // Constructing invalid reading types is rejected by the catalog. Only the text entries pass.
        val textMetrics = Metrics.allIds.mapNotNull { id ->
            TelemetrySection.entries.firstNotNullOfOrNull { section ->
                try { textMetric(id, section, sensitive) } catch (_: IllegalArgumentException) { null }
            }
        }
        assertTrue(textMetrics.size > 10)
        val data = reportData().copy(telemetry = TelemetrySnapshot(200, metrics = textMetrics))
        val report = DeviceReportGenerator.generate(data, DeviceReportOptions(chainEvidence = true)).previewText
        listOf("private-folder", "private-track", "12:34", "C:").forEach { assertFalse(report.contains(it)) }
    }

    @Test fun `evidence confidence provenance time dependencies and unavailable reasons survive export`() {
        val source = TelemetryDataSource(TelemetrySourceId("media3.data_source"), "private-track.flac")
        val input = TelemetryMetric(Metrics.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED, TelemetrySection.PROCESS,
            TelemetryEvidence.Measured(TelemetryReading.Integer(4096, TelemetryUnit.BYTES), source, 100, 80))
        val derived = TelemetryMetric(Metrics.PROCESS_DATA_SOURCE_READ_THROUGHPUT, TelemetrySection.PROCESS,
            TelemetryEvidence.Derived(TelemetryReading.Decimal(16384.0, TelemetryUnit.BITS_PER_SECOND), source, 110,
                TelemetryWindow(90, 110, 60, 100), "rate.data_source_bytes_per_window", setOf(input.id), mapOf("bytes.delta" to 4096.0, "window.seconds" to 2.0), 100))
        val unavailable = TelemetryMetric(Metrics.DECODER_OUTPUT_ENCODING, TelemetrySection.DECODER,
            TelemetryEvidence.Unavailable(TelemetryUnavailableReason.WARMING_UP, 120, source, "private-track.flac", 110))
        val report = DeviceReportGenerator.generate(reportData().copy(telemetry = TelemetrySnapshot(200, metrics = listOf(input, derived, unavailable))), DeviceReportOptions(chainEvidence = true)).previewText
        listOf("MEASURED", "DERIVED", "UNAVAILABLE", "WARMING_UP", "media3.data_source", "rate.data_source_bytes_per_window", "bytes.delta", "4096.0", "\"observedAtEpochMs\":110", "\"observedAtElapsedRealtimeMs\":100", "\"endedAtElapsedRealtimeMs\":100", "inputMetricIds").forEach { assertTrue("Missing $it", report.contains(it)) }
        assertFalse(report.contains("private-track.flac"))
    }

    @Test fun `capability encodings and preferred profile keep reviewed format information`() {
        val source = TelemetryDataSource(TelemetrySourceId("android.usb_public_api"))
        val telemetry = TelemetrySnapshot(200, metrics = listOf(
            textMetric(Metrics.ROUTE_REQUEST_FORMAT_DIRECT_MODES, TelemetrySection.ROUTE, "offload,bitstream"),
            textMetric(Metrics.ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE, TelemetrySection.ROUTE, "bit_perfect_capability,96000hz,pcm_24,mask_0xc"),
            TelemetryMetric(Metrics.USB_DEVICE_INVENTORY, TelemetrySection.USB, TelemetryEvidence.Measured(
                TelemetryReading.UsbInventory(UsbInventoryReading(emptyList(), listOf(
                    UsbAudioOutputEndpointReading("private endpoint", "Private DAC", "usb_device", listOf(96000), false,
                        listOf(2), false, listOf("pcm_24", "pcm_float", "encoding_99"), false),
                ))), source, 100)),
        ))
        val report = DeviceReportGenerator.generate(reportData().copy(telemetry = telemetry), DeviceReportOptions(audioCapabilities = true)).previewText
        listOf("offload,bitstream", "bit_perfect_capability,96000hz,pcm_24,mask_0xc", "pcm_float", "encoding_99").forEach { assertTrue(report.contains(it)) }
        assertFalse(report.contains("Private DAC"))
        assertFalse(report.contains("private endpoint"))
    }

    @Test fun `missing observation and unavailable histories are explicit`() {
        val report = DeviceReportGenerator.generate(reportData().copy(history = ErrorHistorySnapshot(availability = ErrorHistoryAvailability.STORAGE_UNAVAILABLE, exitHistory = ExitHistoryAvailability.UNSUPPORTED_ANDROID_VERSION)), DeviceReportOptions(true, true, true, true)).previewText
        assertTrue(report.contains("NOT_SAMPLED"))
        assertTrue(report.contains("STORAGE_UNAVAILABLE"))
        assertTrue(report.contains("UNSUPPORTED_ANDROID_VERSION"))
    }

    private fun textMetric(id: TelemetryMetricId, section: TelemetrySection, text: String, source: TelemetryDataSource = TelemetryDataSource(TelemetrySourceId("android.audio_route"))) =
        TelemetryMetric(id, section, TelemetryEvidence.Measured(TelemetryReading.Text(text), source, 100))
}

internal fun error(time: Long = 100) = ReportErrorEvent(ReportErrorKind.PLAYBACK, time, 50, platformCode = 2000,
    format = FailedTrackFormat("flac", "audio/flac", 48000, 24, 2), fileName = "/private-folder/private-track.flac")

internal fun reportData() = DeviceReportData(DeviceReportBasic("0.1.0", 1, "debug", "Manufacturer", "Model", "16", 36, "Build-1"), 200,
    history = ErrorHistorySnapshot(listOf(error())))
