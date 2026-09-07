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

    @Test fun endpointNamesArePrivateAndNewMetricsParticipateInProbeDemand() {
        assertEquals(TelemetryExportPolicy.REDACT_TEXT,
            TelemetryMetricCatalog.descriptor(TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES).exportPolicy)
        for (descriptor in TelemetryMetricCatalog.descriptors.filter { it.id.value.startsWith("route.bluetooth_") }) {
            assertTrue(descriptor.id in TelemetryMetricCatalog.outputProbeIds)
        }
    }
}
