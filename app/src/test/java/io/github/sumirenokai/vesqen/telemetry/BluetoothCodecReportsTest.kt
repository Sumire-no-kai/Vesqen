package io.github.sumirenokai.vesqen.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCodecReportsTest {
    private val at = TelemetryInstant(epochMs = 1_000, elapsedRealtimeMs = 500)

    @Test fun ldacQualityKeepsTheAdaptiveValueAmbiguous() {
        assertEquals(LdacQuality.KBPS_990, LdacQuality.fromCodecSpecific1(1000))
        assertEquals(LdacQuality.KBPS_660, LdacQuality.fromCodecSpecific1(1001))
        assertEquals(LdacQuality.KBPS_330, LdacQuality.fromCodecSpecific1(1002))
        // The iQOO 9 reported 990 kbps as 1003 (#73), so 1003 never claims a bit rate.
        assertEquals(LdacQuality.ADAPTIVE_OR_990, LdacQuality.fromCodecSpecific1(1003))
        assertNull(LdacQuality.ADAPTIVE_OR_990.nominalBitsPerSecond)
        assertNull(LdacQuality.fromCodecSpecific1(0))
    }

    @Test fun currentConfigFieldsDecodeOnlySingleBits() {
        // The iQOO 9 report for WF-1000XM4: LDAC, 96 kHz (0x8), 32-bit (0x4), stereo (0x2), 990 kbps.
        val ldac = BluetoothCodecFields.settings(isLdac = true, sampleRate = 0x8, bits = 0x4, channelMode = 0x2, codecSpecific1 = 1000)
        assertEquals(BluetoothCodecSettings(96_000, 32, 2, LdacQuality.KBPS_990), ldac)
        // A capability mask (several bits) is not a current value.
        val capabilities = BluetoothCodecFields.settings(isLdac = true, sampleRate = 0xf, bits = 0x7, channelMode = 0x3, codecSpecific1 = 0)
        assertTrue(capabilities.isEmpty)
        // AAC's codecSpecific1 (133 on the iQOO) is vendor data, not an LDAC tier.
        val aac = BluetoothCodecFields.settings(isLdac = false, sampleRate = 0x1, bits = 0x1, channelMode = 0x2, codecSpecific1 = 133)
        assertEquals(BluetoothCodecSettings(44_100, 16, 2, null), aac)
    }

    @Test fun standardCodecNamesCoverTheAospTypes() {
        assertEquals("SBC", BluetoothCodecFields.standardCodecName(0))
        assertEquals("AAC", BluetoothCodecFields.standardCodecName(1))
        assertEquals("LDAC", BluetoothCodecFields.standardCodecName(4))
        assertEquals("Opus", BluetoothCodecFields.standardCodecName(6))
        // A vendor type has no name before API 35.
        assertNull(BluetoothCodecFields.standardCodecName(1_000_000))
    }

    @Test fun settingsTokenRoundTrips() {
        val settings = BluetoothCodecSettings(96_000, 32, 2, LdacQuality.ADAPTIVE_OR_990)
        assertEquals("96000hz,32bit,stereo,ldac_adaptive_or_990kbps", settings.token())
        assertEquals(settings, BluetoothCodecSettings.parse(requireNotNull(settings.token())))
        assertEquals(BluetoothCodecSettings(44_100, null, 1, null), BluetoothCodecSettings.parse("44100hz,mono"))
        assertNull(BluetoothCodecSettings(null, null, null, null).token())
    }

    @Test fun aReportIsDroppedOnceItMayDescribeAnotherDevice() {
        val state = BluetoothCodecState()
        val report = BluetoothCodecReport("LDAC", BluetoothCodecSettings(96_000, 32, 2, LdacQuality.KBPS_660), at)
        state.onCodecReported("A", report)
        state.onDisconnected("B")
        state.onActiveDeviceChanged("A")
        assertEquals(report, state.current)
        state.onActiveDeviceChanged("B")
        assertNull(state.current)

        state.onCodecReported("A", report)
        state.onDisconnected("A")
        assertNull(state.current)

        state.onCodecReported("A", report)
        state.onActiveDeviceChanged(null)
        assertNull(state.current)

        state.onCodecReported("A", report)
        state.clear()
        assertNull(state.current)
    }

    @Test fun theCodecIsShownOnlyForABluetoothRouteWithPermissionAndAReport() {
        val report = BluetoothCodecReport("SBC", BluetoothCodecSettings(44_100, 16, 2, null), at)
        val bluetooth = TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
        assertEquals(TelemetryUnavailableReason.NOT_APPLICABLE,
            bluetoothCodecUnavailableReason(TelemetryUnavailableReason.NOT_APPLICABLE, true, true, report))
        assertEquals(TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
            bluetoothCodecUnavailableReason(bluetooth, supported = false, permissionGranted = true, report = report))
        assertEquals(TelemetryUnavailableReason.PERMISSION_NOT_GRANTED,
            bluetoothCodecUnavailableReason(bluetooth, supported = true, permissionGranted = false, report = report))
        assertEquals(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT,
            bluetoothCodecUnavailableReason(bluetooth, supported = true, permissionGranted = true, report = null))
        assertNull(bluetoothCodecUnavailableReason(bluetooth, supported = true, permissionGranted = true, report = report))
    }
}
