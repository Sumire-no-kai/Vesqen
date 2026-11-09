package io.github.sumirenokai.vesqen.telemetry

import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMetricCatalogTest {
    @Test
    fun `own USB output readings and the resampling inference stay on the device`() {
        val local = TelemetryMetricCatalog.allIds.filter { it.value.startsWith("usb.direct_") } + TelemetryMetricCatalog.ROUTE_SYSTEM_RESAMPLING
        assertEquals(12, local.size)
        local.forEach { id ->
            assertEquals(id.value, TelemetryExportPolicy.LOCAL_ONLY, TelemetryMetricCatalog.descriptor(id).exportPolicy)
            assertFalse(id.value, id in TelemetryMetricCatalog.exportableIds)
        }
        assertEquals(TelemetryMetricCatalog.allIds - local.toSet(), TelemetryMetricCatalog.exportableIds)
        // Only the hardware volume uses decibels, so the report vocabulary does not list them.
        assertEquals(setOf(TelemetryUnit.DECIBELS), TelemetryMetricCatalog.localOnlyUnits)
    }

    @Test
    fun `the system is inferred to resample only a rate the USB device does not declare`() {
        fun endpoint(vararg rates: Int, arbitrary: Boolean = false) = UsbAudioOutputEndpointReading(
            snapshotKey = "usb-1", productName = null, type = "usb_device",
            sampleRatesHz = rates.toList(), arbitrarySampleRate = arbitrary,
            channelCounts = listOf(2), arbitraryChannelCount = false, encodings = listOf("pcm_16bit"), arbitraryEncoding = false,
        )
        val jbl = listOf(endpoint(48_000))
        assertEquals("resampled", systemResamplingToken(192_000, jbl))
        assertEquals("resampled", systemResamplingToken(44_100, jbl))
        // A declared rate does not prove Android keeps it, so the answer stays open.
        assertEquals("undetermined", systemResamplingToken(48_000, jbl))
        assertEquals("undetermined", systemResamplingToken(192_000, listOf(endpoint(arbitrary = true))))
        assertEquals("undetermined", systemResamplingToken(192_000, emptyList()))
    }

    @Test
    fun `the resampling inference applies when Android routes media to a USB device`() {
        val jbl = UsbAudioOutputEndpointReading(
            snapshotKey = "usb-1", productName = null, type = "usb_headset",
            sampleRatesHz = listOf(48_000), arbitrarySampleRate = false,
            channelCounts = listOf(2), arbitraryChannelCount = false, encodings = listOf("pcm_16bit"), arbitraryEncoding = false,
        )
        // As the iQOO 9 (Android 15) reports a USB DAC: the selected route's type is only "wired or USB".
        fun output(mediaDevice: String?, routeReason: TelemetryUnavailableReason? = null) = OutputTelemetrySnapshot(
            observedAt = TelemetryInstant(epochMs = 1, elapsedRealtimeMs = 1),
            selectedSystemRouteName = "USB", selectedSystemRouteType = "wired_or_usb", selectedSystemRouteTypeUnavailableReason = null,
            anticipatedRouteName = null, anticipatedRouteType = mediaDevice,
            connectedRouteTypes = null, connectedRouteTypesUnavailableReason = null, routeUnavailableReason = routeReason,
            directSupported = null, directModes = null, directUnavailableReason = null,
            mixerProfileCount = null, preferredMixerProfile = null, mixerUnavailableReason = null,
            systemMusicVolumePercent = null, systemMusicMuted = null, usbHostSupported = true,
            usbAudioDeviceCount = 1, usbAudioDeviceCountUnavailableReason = null,
            usbInventory = UsbInventoryReading(hostDevices = emptyList(), audioOutputEndpoints = listOf(jbl)),
            usbInventoryUnavailableReason = null,
            bluetoothConnectedNames = null, bluetoothConnectedTypes = null, bluetoothUnavailableReason = null,
        )
        assertEquals(null, systemResamplingUnavailableReason(UsbOutputPhase.SYSTEM, output("usb_headset"), 192_000))
        assertEquals(null, systemResamplingUnavailableReason(UsbOutputPhase.SYSTEM, output("usb_device"), 192_000))
        assertEquals(
            TelemetryUnavailableReason.NOT_APPLICABLE,
            systemResamplingUnavailableReason(UsbOutputPhase.SYSTEM, output("wired_headphones"), 192_000),
        )
        assertEquals(
            TelemetryUnavailableReason.NOT_APPLICABLE,
            systemResamplingUnavailableReason(UsbOutputPhase.ACTIVE, output("usb_headset"), 192_000),
        )
        // Before Android 13 Android does not name the media device, and the reason says so.
        assertEquals(
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
            systemResamplingUnavailableReason(
                UsbOutputPhase.SYSTEM, output(null, TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION), 192_000,
            ),
        )
    }

    @Test
    fun `probe demand sets contain only known metrics`() {
        assertTrue(TelemetryMetricCatalog.allIds.containsAll(TelemetryMetricCatalog.systemProbeIds))
        assertTrue(TelemetryMetricCatalog.allIds.containsAll(TelemetryMetricCatalog.outputProbeIds))
    }

    @Test
    fun `process data source counters do not activate Android system polling`() {
        assertFalse(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED in
                TelemetryMetricCatalog.systemProbeIds,
        )
        assertFalse(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT in
                TelemetryMetricCatalog.systemProbeIds,
        )
        assertFalse(
            TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE in
                TelemetryMetricCatalog.systemProbeIds,
        )
    }

    @Test
    fun `static output declaration does not activate route or USB polling`() {
        assertFalse(TelemetryMetricCatalog.ROUTE_OUTPUT_DECLARATION in TelemetryMetricCatalog.outputProbeIds)
        assertFalse(TelemetryMetricCatalog.ROUTE_LAST_STRATEGY_DECISION in TelemetryMetricCatalog.outputProbeIds)
        assertTrue(TelemetryMetricCatalog.USB_DEVICE_INVENTORY in TelemetryMetricCatalog.outputProbeIds)
    }

    @Test
    fun `new text descriptors fail closed while explicit safe text stays exportable`() {
        val newText = TelemetryMetricDescriptor(
            id = TelemetryMetricId("test.future_text"),
            section = TelemetrySection.PROCESS,
            valueKind = TelemetryValueKind.TEXT,
        )

        assertEquals(TelemetryExportPolicy.REDACT_TEXT, newText.exportPolicy)
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.PROCESS_SOC_MODEL).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.INCLUDE,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.SOURCE_CODEC_MIME).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.INCLUDE,
            TelemetryMetricCatalog.descriptor(
                TelemetryMetricCatalog.ROUTE_EXTERNAL_VERIFICATION_RECORD,
            ).exportPolicy,
        )
        assertEquals(
            TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(
                TelemetryMetricCatalog.ROUTE_EXTERNAL_VERIFICATION_EVIDENCE,
            ).exportPolicy,
        )
    }
}
