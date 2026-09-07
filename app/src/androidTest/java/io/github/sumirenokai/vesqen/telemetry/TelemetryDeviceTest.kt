package io.github.sumirenokai.vesqen.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sumirenokai.vesqen.VesqenApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelemetryDeviceTest {
    @Test
    fun productionAdapterEmitsHonestDeviceSnapshotAndStopsAfterCollection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val telemetry = (context as VesqenApplication).telemetryRuntime
        val selected = setOf(
            TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
            TelemetryMetricCatalog.DECODER_NAME,
            TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE,
            TelemetryMetricCatalog.PROCESS_CPU_PERCENT,
            TelemetryMetricCatalog.PROCESS_GC_COUNT,
            TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_TYPE,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_TYPES,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CODEC,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_TRANSPORT_BITRATE,
            TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED,
            TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
        )

        val snapshot = withTimeout(10_000) {
            telemetry.observe(
                TelemetryObservation(
                    refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
                    derivedWindowMs = 2_000,
                    selection = TelemetryMetricSelection.Explicit(selected),
                ),
            ).first()
        }

        assertEquals(selected, snapshot.metrics.mapTo(mutableSetOf(), TelemetryMetric::id))
        assertTrue(snapshot.metric(TelemetryMetricCatalog.ROUTE_BLUETOOTH_CODEC)?.evidence is TelemetryEvidence.Unavailable)
        assertTrue(snapshot.metric(TelemetryMetricCatalog.ROUTE_BLUETOOTH_TRANSPORT_BITRATE)?.evidence is TelemetryEvidence.Unavailable)
        assertTrue(snapshot.metrics.all {
            it.evidence.observedAtElapsedRealtimeMs <= snapshot.capturedAtElapsedRealtimeMs
        })
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            (snapshot.metric(TelemetryMetricCatalog.SOURCE_SAMPLE_RATE)?.evidence as TelemetryEvidence.Unavailable).reason,
        )
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            (snapshot.metric(TelemetryMetricCatalog.DECODER_NAME)?.evidence as TelemetryEvidence.Unavailable).reason,
        )
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            (snapshot.metric(TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE)?.evidence as
                TelemetryEvidence.Unavailable).reason,
        )
        assertEquals(
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
            (snapshot.metric(TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED)?.evidence as TelemetryEvidence.Unavailable).reason,
        )
        val usbEvidence = snapshot.metric(TelemetryMetricCatalog.USB_DEVICE_INVENTORY)?.evidence
        assertNotNull(usbEvidence)
        assertTrue(
            usbEvidence is TelemetryEvidence.Measured ||
                usbEvidence is TelemetryEvidence.Unavailable &&
                usbEvidence.reason == TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )

        withTimeout(2_000) {
            while (telemetry.debugSamplingState().activeObservationCount != 0) delay(10)
        }
        assertEquals(null, telemetry.debugSamplingState().activeIntervalMs)
    }
}
