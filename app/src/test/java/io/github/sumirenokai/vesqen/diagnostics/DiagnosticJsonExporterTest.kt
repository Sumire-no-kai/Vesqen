package io.github.sumirenokai.vesqen.diagnostics

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryWindow
import io.github.sumirenokai.vesqen.telemetry.UsbAudioInterfaceReading
import io.github.sumirenokai.vesqen.telemetry.UsbAudioOutputEndpointReading
import io.github.sumirenokai.vesqen.telemetry.UsbHostDeviceReading
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJsonExporterTest {
    @Test
    fun `export is deterministic complete and privacy filtered`() {
        val rawSession = "content://media/external/audio/secret-track"
        val source = TelemetryDataSource(
            id = TelemetrySourceId("adapter.media3"),
            detail = "C:\\Users\\private\\secret.flac IllegalStateException",
        )
        val bytesRead = TelemetryMetric(
            id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED,
            section = TelemetrySection.PROCESS,
            evidence = TelemetryEvidence.Measured(
                reading = TelemetryReading.Integer(1_024, TelemetryUnit.BYTES),
                source = source,
                observedAtEpochMs = 2_000,
                observedAtElapsedRealtimeMs = 200,
            ),
        )
        val metrics = listOf(
            TelemetryMetric(
                id = TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES,
                section = TelemetrySection.ROUTE,
                evidence = measuredText("QA Bluetooth alias 12:34:56:78:9A:BC", source),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
                section = TelemetrySection.USB,
                evidence = TelemetryEvidence.Measured(
                    reading = TelemetryReading.UsbInventory(
                        UsbInventoryReading(
                            hostDevices = listOf(
                                UsbHostDeviceReading(
                                    snapshotKey = "/dev/bus/usb/private-key",
                                    manufacturerName = "Private Manufacturer",
                                    productName = "Private DAC 18d1:4ee7",
                                    vendorId = 0x18d1,
                                    productId = 0x4ee7,
                                    permissionGranted = true,
                                    audioInterfaces = listOf(
                                        UsbAudioInterfaceReading(
                                            interfaceClass = 1,
                                            interfaceSubclass = 2,
                                            interfaceProtocol = 32,
                                        ),
                                    ),
                                ),
                            ),
                            audioOutputEndpoints = listOf(
                                UsbAudioOutputEndpointReading(
                                    snapshotKey = "private-endpoint-key",
                                    productName = "Private output name",
                                    type = "TYPE_SECRET_USB",
                                    sampleRatesHz = listOf(96_000, 48_000, 48_000),
                                    arbitrarySampleRate = false,
                                    channelCounts = listOf(2),
                                    arbitraryChannelCount = false,
                                    encodings = listOf("PCM_24BIT", "C:\\private\\encoding"),
                                    arbitraryEncoding = false,
                                ),
                            ),
                        ),
                    ),
                    source = source,
                    observedAtEpochMs = 2_000,
                    observedAtElapsedRealtimeMs = 200,
                ),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.PROCESS_SOC_MODEL,
                section = TelemetrySection.PROCESS,
                evidence = TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Text("Tensor \"G3\""),
                    source = source,
                    observedAtEpochMs = 2_000,
                    observedAtElapsedRealtimeMs = 200,
                    methodId = "platform.build_soc_model",
                ),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.ROUTE_ANTICIPATED_NAME,
                section = TelemetrySection.ROUTE,
                evidence = measuredText("Lee's private headphones", source),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.SOURCE_CODEC_MIME,
                section = TelemetrySection.SOURCE,
                evidence = measuredText("audio/flac", source),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.DECODER_NAME,
                section = TelemetrySection.DECODER,
                evidence = measuredText("Artist/Secret-track.flac", source),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.DECODER_PATH,
                section = TelemetrySection.DECODER,
                evidence = measuredText("C:\\private\\codec.dll", source),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.SOURCE_FILE_SIZE,
                section = TelemetrySection.SOURCE,
                evidence = TelemetryEvidence.Unavailable(
                    reason = TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
                    observedAtEpochMs = 2_000,
                    observedAtElapsedRealtimeMs = 200,
                    source = source,
                    detail = "content://private/path IllegalArgumentException",
                ),
            ),
            TelemetryMetric(
                id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
                section = TelemetrySection.PROCESS,
                evidence = TelemetryEvidence.Derived(
                    reading = TelemetryReading.Decimal(8_192.0, TelemetryUnit.BITS_PER_SECOND),
                    source = source,
                    observedAtEpochMs = 2_000,
                    observedAtElapsedRealtimeMs = 200,
                    window = TelemetryWindow(
                        startedAtEpochMs = 1_000,
                        endedAtEpochMs = 2_000,
                        startedAtElapsedRealtimeMs = 100,
                        endedAtElapsedRealtimeMs = 200,
                    ),
                    calculationId = "rate.bytes_to_bits",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED,
                    ),
                    operands = linkedMapOf(
                        "window.duration_ms" to 1_000.0,
                        "source.byte_delta" to 1_024.0,
                    ),
                ),
            ),
            bytesRead,
        )
        val recording = recording(
            snapshot = TelemetrySnapshot(
                capturedAtEpochMs = 2_000,
                capturedAtElapsedRealtimeMs = 200,
                playbackSessionId = rawSession,
                metrics = metrics,
            ),
            event = TelemetryEvent(
                sequence = 7,
                kind = TelemetryEventKind.ROUTE_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                occurredAtEpochMs = 1_900,
                occurredAtElapsedRealtimeMs = 190,
                code = "route.changed",
                playbackSessionId = rawSession,
                relatedMetricIds = linkedSetOf(
                    TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
                    TelemetryMetricCatalog.ROUTE_ANTICIPATED_NAME,
                ),
            ),
        )

        val first = CloseTrackingOutputStream()
        val second = ByteArrayOutputStream()
        DiagnosticJsonExporter.write(recording, first)
        DiagnosticJsonExporter.write(recording, second)
        val json = first.toString(StandardCharsets.UTF_8.name())

        assertEquals(json, second.toString(StandardCharsets.UTF_8.name()))
        assertFalse(first.closed)
        assertTrue(json.startsWith("{\"schemaVersion\":1,"))
        assertTrue(json.endsWith("\n"))
        assertTrue(json.contains("\"playbackSession\":\"session-1\""))
        assertTrue(json.contains("\"confidence\":\"derived\""))
        assertTrue(json.contains("\"calculationId\":\"rate.bytes_to_bits\""))
        assertTrue(json.contains("\"durationMs\":100"))
        assertTrue(json.contains("\"operands\":{\"source.byte_delta\":1024.0,\"window.duration_ms\":1000.0}"))
        assertTrue(json.contains("\"confidence\":\"estimated\""))
        assertTrue(json.contains("\"methodId\":\"platform.build_soc_model\""))
        assertTrue(json.contains("\"unavailableReason\":\"not_exposed_by_platform\""))
        assertTrue(json.contains("\"value\":\"audio/flac\""))
        assertTrue(json.contains("\"kind\":\"redacted\""))
        assertTrue(json.contains("\"kind\":\"usb_inventory\""))
        assertTrue(json.contains("\"hostDeviceCount\":1"))
        assertTrue(json.contains("\"deviceOrdinal\":1"))
        assertTrue(json.contains("\"vendorId\":6353"))
        assertTrue(json.contains("\"productId\":20199"))
        assertTrue(json.contains("\"permissionGranted\":true"))
        assertTrue(json.contains("\"type\":\"usb_other\""))
        assertTrue(json.contains("\"interfaceClass\":1"))
        assertTrue(json.contains("\"interfaceSubclass\":2"))
        assertTrue(json.contains("\"interfaceProtocol\":32"))
        assertTrue(json.contains("\"sampleRatesHz\":[48000,96000]"))
        assertTrue(json.contains("\"encodings\":[\"[redacted]\",\"PCM_24BIT\"]"))

        assertFalse(json.contains(rawSession))
        assertFalse(json.contains("Lee's private headphones"))
        assertFalse(json.contains("QA Bluetooth alias"))
        assertFalse(json.contains("12:34:56:78:9A:BC"))
        assertTrue(json.contains("route.bluetooth_connected_names"))
        assertFalse(json.contains("Private Manufacturer"))
        assertFalse(json.contains("Private DAC 18d1:4ee7"))
        assertFalse(json.contains("Private output name"))
        assertFalse(json.contains("private-endpoint-key"))
        assertFalse(json.contains("/dev/bus/usb/private-key"))
        assertFalse(json.contains("TYPE_SECRET_USB"))
        assertFalse(json.contains("18d1:4ee7"))
        assertFalse(json.contains("Tensor \\\"G3\\\""))
        assertFalse(json.contains("\"snapshotKey\""))
        assertFalse(json.contains("\"manufacturerName\""))
        assertFalse(json.contains("\"productName\""))
        assertFalse(json.contains("C:\\private\\codec.dll"))
        assertFalse(json.contains("Artist/Secret-track.flac"))
        assertFalse(json.contains("C:\\private\\encoding"))
        assertFalse(json.contains("C:\\Users\\private"))
        assertFalse(json.contains("content://private/path"))
        assertFalse(json.contains("IllegalArgumentException"))
        assertFalse(json.contains("IllegalStateException"))

        val decoderIndex = json.indexOf("\"id\":\"decoder.name\"")
        val playbackIndex = json.indexOf("\"id\":\"process.data_source_bytes_transferred_since_start\"")
        val sourceIndex = json.indexOf("\"id\":\"source.codec_mime\"")
        assertTrue(decoderIndex in 0 until playbackIndex)
        assertTrue(playbackIndex in 0 until sourceIndex)
    }

    @Test
    fun `usb ordinals remain stable across snapshots without exporting snapshot keys`() {
        val firstDevice = usbHostDevice(snapshotKey = "private-first-key", interfaceClass = 1)
        val secondDevice = usbHostDevice(snapshotKey = "private-second-key", interfaceClass = 2)
        val source = TelemetryDataSource(TelemetrySourceId("adapter.usb_manager"))
        fun metric(devices: List<UsbHostDeviceReading>) = TelemetryMetric(
            id = TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
            section = TelemetrySection.USB,
            evidence = TelemetryEvidence.Measured(
                reading = TelemetryReading.UsbInventory(
                    UsbInventoryReading(devices, audioOutputEndpoints = emptyList()),
                ),
                source = source,
                observedAtEpochMs = 2_000,
                observedAtElapsedRealtimeMs = 200,
            ),
        )
        val recording = DiagnosticRecording(
            id = "recording-usb-aliases",
            startedAt = DiagnosticTimestamp(1_000, 100),
            stoppedAt = DiagnosticTimestamp(3_000, 300),
            termination = DiagnosticRecordingTermination.USER_STOPPED,
            limits = DiagnosticRecordingLimits(),
            snapshots = listOf(
                TelemetrySnapshot(2_000, 200, metrics = listOf(metric(listOf(firstDevice, secondDevice)))),
                TelemetrySnapshot(2_500, 250, metrics = listOf(metric(listOf(secondDevice, firstDevice)))),
            ),
            events = emptyList(),
            droppedSnapshotCount = 0,
            droppedEventCount = 0,
            observedEventSequenceGapCount = 0,
        )
        val output = ByteArrayOutputStream()

        DiagnosticJsonExporter.write(recording, output)
        val json = output.toString(StandardCharsets.UTF_8.name())

        val firstOrder = "\"deviceOrdinal\":1,\"vendorId\":1,\"productId\":2," +
            "\"permissionGranted\":true," +
            "\"audioInterfaceCount\":1," +
            "\"audioInterfaces\":[{\"interfaceClass\":1"
        val secondOrder = "\"deviceOrdinal\":2,\"vendorId\":1,\"productId\":2," +
            "\"permissionGranted\":true," +
            "\"audioInterfaceCount\":1," +
            "\"audioInterfaces\":[{\"interfaceClass\":2"
        val firstSnapshotFirstDevice = json.indexOf(firstOrder)
        val firstSnapshotSecondDevice = json.indexOf(secondOrder)
        val secondSnapshotSecondDevice = json.indexOf(secondOrder, firstSnapshotSecondDevice + 1)
        val secondSnapshotFirstDevice = json.indexOf(firstOrder, firstSnapshotFirstDevice + 1)
        assertTrue(firstSnapshotFirstDevice in 0 until firstSnapshotSecondDevice)
        assertTrue(secondSnapshotSecondDevice in 0 until secondSnapshotFirstDevice)
        assertFalse(json.contains("private-first-key"))
        assertFalse(json.contains("private-second-key"))
    }

    private fun measuredText(value: String, source: TelemetryDataSource) = TelemetryEvidence.Measured(
        reading = TelemetryReading.Text(value),
        source = source,
        observedAtEpochMs = 2_000,
        observedAtElapsedRealtimeMs = 200,
    )

    private fun usbHostDevice(snapshotKey: String, interfaceClass: Int) = UsbHostDeviceReading(
        snapshotKey = snapshotKey,
        manufacturerName = "private manufacturer",
        productName = "private product",
        vendorId = 1,
        productId = 2,
        permissionGranted = true,
        audioInterfaces = listOf(UsbAudioInterfaceReading(interfaceClass, 2, 0)),
    )

    private fun recording(snapshot: TelemetrySnapshot, event: TelemetryEvent) = DiagnosticRecording(
        id = "recording-test",
        startedAt = DiagnosticTimestamp(epochMs = 1_000, elapsedRealtimeMs = 100),
        stoppedAt = DiagnosticTimestamp(epochMs = 3_000, elapsedRealtimeMs = 300),
        termination = DiagnosticRecordingTermination.USER_STOPPED,
        limits = DiagnosticRecordingLimits(),
        snapshots = listOf(snapshot),
        events = listOf(event),
        droppedSnapshotCount = 0,
        droppedEventCount = 0,
        observedEventSequenceGapCount = 0,
    )

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
