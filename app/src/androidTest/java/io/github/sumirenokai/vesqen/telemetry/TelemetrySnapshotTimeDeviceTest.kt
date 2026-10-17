package io.github.sumirenokai.vesqen.telemetry

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each observer builds its snapshot from the latest captured sample, which may predate its
 * subscription. State published after that capture must not reach the snapshot: a snapshot holds
 * no evidence observed after its own capture time, and its constructor rejects such evidence.
 */
class TelemetrySnapshotTimeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aUsbStatusPublishedAfterTheCaptureStaysOutOfALaterObserversSnapshot() = runBlocking {
        val usbOutput = UsbOutputStateRepository()
        val telemetry = AndroidPlaybackTelemetry(context, usbOutputStateRepository = usbOutput)
        // Five seconds between captures, so the second observer is served the first one's sample.
        val observation = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.FIVE_SECONDS,
            selection = TelemetryMetricSelection.Explicit(setOf(TelemetryMetricCatalog.ROUTE_OUTPUT_DECLARATION)),
        )
        val first = CompletableDeferred<TelemetrySnapshot>()
        val holder = launch(Dispatchers.Default) { telemetry.observe(observation).collect { first.complete(it) } }
        try {
            val captured = withTimeout(5_000) { first.await() }
            delay(10)
            usbOutput.publish(
                UsbOutputStatus(
                    observedAtEpochMs = System.currentTimeMillis(),
                    observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    generation = 1,
                ),
            )
            val late = withTimeout(2_000) { telemetry.observe(observation).first() }
            assertEquals(captured.capturedAtElapsedRealtimeMs, late.capturedAtElapsedRealtimeMs)
        } finally {
            holder.cancel()
        }
    }
}
