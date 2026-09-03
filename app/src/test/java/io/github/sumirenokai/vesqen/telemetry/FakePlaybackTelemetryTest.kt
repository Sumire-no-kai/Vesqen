package io.github.sumirenokai.vesqen.telemetry

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakePlaybackTelemetryTest {
    @Test
    fun `fake records observation demand emits snapshots and releases collection`() = runBlocking {
        val initial = TelemetrySnapshot.empty(capturedAtEpochMs = 1_000)
        val updated = TelemetrySnapshot.empty(capturedAtEpochMs = 2_000)
        val fake = FakePlaybackTelemetry(initial)
        val observation = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            derivedWindowMs = 2_000,
            powerMode = TelemetryPowerMode.STANDARD,
            selection = TelemetryMetricSelection.Explicit(
                setOf(TelemetryMetricId("playback.buffered_bytes")),
            ),
        )
        val received = mutableListOf<TelemetrySnapshot>()

        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            fake.observe(observation).take(2).toList(received)
        }

        assertEquals(1, fake.activeObservationCount)
        assertEquals(listOf(observation), fake.observationHistory)
        fake.publish(updated)
        collection.join()

        assertEquals(listOf(initial, updated), received)
        assertEquals(0, fake.activeObservationCount)
    }
}
