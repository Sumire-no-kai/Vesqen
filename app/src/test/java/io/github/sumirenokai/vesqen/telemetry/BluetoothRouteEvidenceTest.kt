package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRouteEvidenceTest {
    @Test fun selectedSpeakerOverridesAnticipatedBluetooth() {
        assertEquals(TelemetryUnavailableReason.NOT_APPLICABLE,
            bluetoothTransportUnavailableReason("phone_speaker", "bluetooth_a2dp"))
    }

    @Test fun bluetoothDoesNotInventNegotiatedParameters() {
        for (type in listOf("bluetooth", "bluetooth_a2dp", "bluetooth_sco", "ble_headset", "ble_speaker", "ble_broadcast", "hearing_aid")) {
            assertTrue(isBluetoothRouteType(type))
            assertEquals(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
                bluetoothTransportUnavailableReason(type, "built_in_speaker"))
        }
        assertFalse(isBluetoothRouteType("usb_headset"))
    }

    @Test fun unknownRoutesRemainUnknownAndCanUseSpecificPrediction() {
        assertEquals(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            bluetoothTransportUnavailableReason("other", "type_999"))
        assertEquals(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            bluetoothTransportUnavailableReason(null, "ble_headset"))
    }

    @Test fun codecReportsApplyOnlyToClassicBluetooth() {
        // A coarse "bluetooth" selected route with an A2DP device, or no specific prediction, can get a report.
        assertEquals(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM, bluetoothCodecRouteReason("bluetooth", "bluetooth_a2dp"))
        assertEquals(TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM, bluetoothCodecRouteReason("bluetooth", null))
        // LE Audio, hearing aids and calls never get one, so nobody is told to reconnect.
        for (type in listOf("ble_headset", "ble_speaker", "ble_broadcast", "hearing_aid", "bluetooth_sco")) {
            assertEquals(TelemetryUnavailableReason.NOT_APPLICABLE, bluetoothCodecRouteReason("bluetooth", type))
            assertEquals(TelemetryUnavailableReason.NOT_APPLICABLE, bluetoothCodecRouteReason(null, type))
        }
        // Other routes keep the transport reason.
        assertEquals(TelemetryUnavailableReason.NOT_APPLICABLE, bluetoothCodecRouteReason("phone_speaker", "bluetooth_a2dp"))
        assertEquals(TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE, bluetoothCodecRouteReason(null, null))
    }

    @Test fun endpointNamesArePrivateAndNewMetricsParticipateInProbeDemand() {
        assertEquals(TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES).exportPolicy)
        for (descriptor in TelemetryMetricCatalog.descriptors.filter { it.id.value.startsWith("route.bluetooth_") }) {
            assertTrue(descriptor.id in TelemetryMetricCatalog.outputProbeIds)
        }
    }
}
