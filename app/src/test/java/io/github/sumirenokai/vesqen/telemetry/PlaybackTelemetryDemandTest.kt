package io.github.sumirenokai.vesqen.telemetry

import android.app.Application
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import io.github.sumirenokai.vesqen.reports.AndroidDeviceReportRuntime
import io.github.sumirenokai.vesqen.reports.DeviceReportOptions
import io.github.sumirenokai.vesqen.reports.DeviceReportState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackTelemetryDemandTest {
    @Test
    fun `an audio capabilities report waits for a sample with output probes`() = runBlocking {
        withNarrowSample { telemetry, capture ->
            val runtimeJob = Job(coroutineContext[Job])
            val reporter = AndroidDeviceReportRuntime(
                RuntimeEnvironment.getApplication(),
                CoroutineScope(coroutineContext + runtimeJob),
                UsbOutputStateRepository(),
                { telemetry },
            ).reporter
            reporter.setOptions(DeviceReportOptions(audioCapabilities = true))
            reporter.generate()
            try {
                withTimeout(3_000) { capture.entered.await() }
                assertNull(withTimeoutOrNull(250) {
                    reporter.snapshot.first { it.state !is DeviceReportState.Generating }
                })
                capture.release.countDown()
                val preview = withTimeout(3_000) {
                    reporter.snapshot.first { it.state !is DeviceReportState.Generating }.state
                } as DeviceReportState.Preview
                val text = preview.report.previewText
                assertTrue(text.contains(TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES.value))
                assertFalse(text.contains(TelemetryUnavailableReason.NOT_SAMPLED.name))
            } finally {
                reporter.discard()
                runtimeJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `an expanded system observation skips the replay with no system probing`() = runBlocking {
        withNarrowSample { telemetry, capture ->
            val id = TelemetryMetricCatalog.PROCESS_CPU_TIME
            val observation = TelemetryObservation(selection = TelemetryMetricSelection.Explicit(setOf(id)))
            val first = async { telemetry.observe(observation).first() }
            try {
                withTimeout(3_000) { capture.entered.await() }
                assertNull(withTimeoutOrNull(250) { first.await() })
                capture.release.countDown()
                val metric = withTimeout(3_000) { first.await().metric(id) }
                assertNotNull(metric)
                assertFalse((metric?.evidence as? TelemetryEvidence.Unavailable)?.reason == TelemetryUnavailableReason.NOT_SAMPLED)
            } finally {
                first.cancelAndJoin()
            }
        }
    }

    private suspend fun withNarrowSample(
        checkSample: suspend CoroutineScope.(AndroidPlaybackTelemetry, HeldCapture) -> Unit,
    ) = coroutineScope {
        val capture = HeldCapture()
        val telemetry = AndroidPlaybackTelemetry(RuntimeEnvironment.getApplication(), directUsbReadings = {
            if (capture.armed.get()) {
                capture.entered.complete(Unit)
                check(capture.release.await(6, TimeUnit.SECONDS))
            }
            null
        })
        val initial = CompletableDeferred<Unit>()
        val narrow = launch {
            telemetry.observe(TelemetryObservation(
                refreshInterval = TelemetryRefreshInterval.FIVE_SECONDS,
                selection = TelemetryMetricSelection.Explicit(setOf(TelemetryMetricCatalog.PLAYBACK_STATE)),
            )).collect { initial.complete(Unit) }
        }
        try {
            withTimeout(3_000) { initial.await() }
            capture.armed.set(true)
            checkSample(telemetry, capture)
        } finally {
            capture.release.countDown()
            narrow.cancelAndJoin()
        }
    }

    private class HeldCapture {
        val armed = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }
}
