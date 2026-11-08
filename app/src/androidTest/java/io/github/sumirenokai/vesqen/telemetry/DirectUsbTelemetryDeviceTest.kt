package io.github.sumirenokai.vesqen.telemetry

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.DirectUsbReadings
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chain reads Vesqen's own USB output from the route, as measured by Vesqen, and nothing when it is not streaming. */
class DirectUsbTelemetryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = setOf(
        TelemetryMetricCatalog.USB_DIRECT_WIRE_SAMPLE_RATE,
        TelemetryMetricCatalog.USB_DIRECT_HARDWARE_VOLUME,
        TelemetryMetricCatalog.USB_DIRECT_PROTOCOL,
        TelemetryMetricCatalog.USB_DIRECT_UNDERRUNS,
    )

    @Test
    fun ownOutputReadingsAreMeasuredByVesqenAndOtherwiseDoNotApply() = runBlocking {
        val readings = AtomicReference<DirectUsbReadings?>(DirectUsbReadings(
            sampleRateHz = 48_000, bitDepth = 24, subslotBytes = 3, channelCount = 2, protocol = "uac1", syncType = "adaptive",
            hardwareVolumeDb = -30.078125, deliveredFrames = 96_000, underruns = 1, lostFrames = 0, failedPackets = 0,
        ))
        val telemetry = AndroidPlaybackTelemetry(context, directUsbReadings = readings::get)
        val observation = TelemetryObservation(selection = TelemetryMetricSelection.Explicit(ids))

        val streaming = withTimeout(5_000) { telemetry.observe(observation).first() }
        val rate = streaming.metric(TelemetryMetricCatalog.USB_DIRECT_WIRE_SAMPLE_RATE)?.evidence as TelemetryEvidence.Measured
        assertEquals(TelemetryReading.Integer(48_000, TelemetryUnit.HERTZ), rate.reading)
        assertEquals("vesqen.direct_usb", rate.source.id.value)
        assertEquals(
            TelemetryReading.Decimal(-30.078125, TelemetryUnit.DECIBELS),
            streaming.metric(TelemetryMetricCatalog.USB_DIRECT_HARDWARE_VOLUME)?.evidence?.reading,
        )
        assertEquals(TelemetryReading.Text("uac1"), streaming.metric(TelemetryMetricCatalog.USB_DIRECT_PROTOCOL)?.evidence?.reading)
        assertEquals(
            TelemetryReading.Integer(1, TelemetryUnit.COUNT),
            streaming.metric(TelemetryMetricCatalog.USB_DIRECT_UNDERRUNS)?.evidence?.reading,
        )

        readings.set(null)
        val idle = withTimeout(5_000) {
            telemetry.observe(observation).first { it.capturedAtElapsedRealtimeMs > streaming.capturedAtElapsedRealtimeMs }
        }
        ids.forEach { id ->
            assertEquals(
                id.value,
                TelemetryUnavailableReason.NOT_APPLICABLE,
                (idle.metric(id)?.evidence as TelemetryEvidence.Unavailable).reason,
            )
        }
    }
}
