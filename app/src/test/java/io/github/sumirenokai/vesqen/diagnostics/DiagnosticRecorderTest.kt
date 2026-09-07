package io.github.sumirenokai.vesqen.diagnostics

import io.github.sumirenokai.vesqen.telemetry.FakePlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.UsbAudioInterfaceReading
import io.github.sumirenokai.vesqen.telemetry.UsbAudioOutputEndpointReading
import io.github.sumirenokai.vesqen.telemetry.UsbHostDeviceReading
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRecorderTest {
    @Test
    fun `recording lifetime belongs to its injected application scope`() = runBlocking {
        val applicationJob = SupervisorJob()
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "first"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = CoroutineScope(applicationJob + Dispatchers.Unconfined),
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 2_100, elapsedRealtimeMs = 210),
            ),
        )

        try {
            val shortLivedCaller = launch { assertTrue(recorder.start()) }
            shortLivedCaller.join()
            assertEquals(1, fake.activeObservationCount)

            fake.publish(snapshot(epochMs = 2_000, elapsedMs = 200, sessionId = "second"))
            yield()
            val recording = requireNotNull(recorder.stop())

            assertEquals(listOf("first", "second"), recording.snapshots.map { it.playbackSessionId })
            assertEquals(0, fake.activeObservationCount)
        } finally {
            applicationJob.cancel()
        }
    }

    @Test
    fun `recording requests the complete catalog and crosses playback sessions`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "first"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 2_100, elapsedRealtimeMs = 210),
            ),
        )

        assertTrue(recorder.start())
        yield()
        assertFalse(recorder.start())
        assertEquals(1, fake.activeObservationCount)
        assertEquals(1, fake.observationHistory.size)
        val observation = fake.observationHistory.single()
        assertEquals(DiagnosticRecorder.RECORDING_OBSERVATION, observation)
        assertEquals(
            TelemetryMetricCatalog.allIds,
            (observation.selection as TelemetryMetricSelection.Explicit).metricIds,
        )

        fake.publish(snapshot(epochMs = 2_000, elapsedMs = 200, sessionId = "second"))
        yield()

        val recording = requireNotNull(recorder.stop())
        assertEquals(DiagnosticRecordingTermination.USER_STOPPED, recording.termination)
        assertEquals(listOf("first", "second"), recording.snapshots.map { it.playbackSessionId })
        assertEquals(0, fake.activeObservationCount)
        assertSame(recording, (recorder.state.value as DiagnosticRecordingState.Stopped).recording)
        assertFalse(recorder.start())
        assertTrue(recorder.clear())
        assertEquals(DiagnosticRecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `state flow replay from before start is not recorded`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 800, elapsedMs = 80, sessionId = "old"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 1_100, elapsedRealtimeMs = 110),
            ),
        )

        assertTrue(recorder.start())
        yield()
        fake.publish(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "current"))
        yield()

        val recording = requireNotNull(recorder.stop())
        assertEquals(listOf("current"), recording.snapshots.map { it.playbackSessionId })
    }

    @Test
    fun `state flow replay captured in the same millisecond as start is not recorded`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 900, elapsedMs = 90, sessionId = "old"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 1_100, elapsedRealtimeMs = 110),
            ),
        )

        assertTrue(recorder.start())
        yield()
        fake.publish(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "current"))
        yield()

        val recording = requireNotNull(recorder.stop())
        assertEquals(listOf("current"), recording.snapshots.map { it.playbackSessionId })
    }

    @Test
    fun `sustained missing playback session automatically seals and releases observation`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "active"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 5_100, elapsedRealtimeMs = 3_100),
            ),
        )

        assertTrue(recorder.start())
        yield()
        fake.publish(snapshot(epochMs = 1_500, elapsedMs = 150, sessionId = null))
        yield()
        fake.publish(snapshot(epochMs = 3_499, elapsedMs = 2_149, sessionId = null))
        yield()
        assertTrue(recorder.state.value is DiagnosticRecordingState.Active)

        fake.publish(snapshot(epochMs = 3_500, elapsedMs = 2_150, sessionId = null))
        yield()

        val recording = (recorder.state.value as DiagnosticRecordingState.Stopped).recording
        assertEquals(DiagnosticRecordingTermination.PLAYBACK_STOPPED, recording.termination)
        assertEquals(listOf(100L, 150L, 2_149L, 2_150L), recording.snapshots.map {
            it.capturedAtElapsedRealtimeMs
        })
        val output = ByteArrayOutputStream()
        DiagnosticJsonExporter.write(recording, output)
        assertTrue(output.toString(StandardCharsets.UTF_8.name()).contains(
            "\"termination\":\"playback_stopped\"",
        ))
        assertEquals(0, fake.activeObservationCount)
    }

    @Test
    fun `temporary missing playback session does not stop a recording`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(epochMs = 1_000, elapsedMs = 100, sessionId = "first"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 5_100, elapsedRealtimeMs = 3_100),
            ),
        )

        assertTrue(recorder.start())
        yield()
        fake.publish(snapshot(epochMs = 1_500, elapsedMs = 150, sessionId = null))
        yield()
        fake.publish(snapshot(epochMs = 2_000, elapsedMs = 200, sessionId = "reconnected"))
        yield()
        fake.publish(snapshot(epochMs = 2_500, elapsedMs = 250, sessionId = null))
        yield()
        fake.publish(snapshot(epochMs = 4_499, elapsedMs = 2_249, sessionId = null))
        yield()

        assertTrue(recorder.state.value is DiagnosticRecordingState.Active)
        val recording = requireNotNull(recorder.stop())
        assertEquals(DiagnosticRecordingTermination.USER_STOPPED, recording.termination)
    }

    @Test
    fun `retention keeps newest snapshots and unique events at deterministic limits`() = runBlocking {
        val event0 = event(sequence = 0, epochMs = 1_000, elapsedMs = 100)
        val event1 = event(sequence = 1, epochMs = 2_000, elapsedMs = 200)
        val event2 = event(sequence = 2, epochMs = 3_000, elapsedMs = 300)
        val fake = FakePlaybackTelemetry(
            snapshot(1_000, 100, "session", recentEvents = listOf(event0)),
        )
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            limits = DiagnosticRecordingLimits(maxSnapshots = 2, maxEvents = 2),
            clock = QueueClock(
                DiagnosticTimestamp(900, 90),
                DiagnosticTimestamp(3_100, 310),
            ),
        )

        recorder.start()
        yield()
        fake.publish(snapshot(2_000, 200, "session", recentEvents = listOf(event0, event1)))
        yield()
        fake.publish(snapshot(3_000, 300, "session", recentEvents = listOf(event0, event1, event2)))
        yield()
        val recording = requireNotNull(recorder.stop())

        assertEquals(listOf(2_000L, 3_000L), recording.snapshots.map { it.capturedAtEpochMs })
        assertEquals(listOf(1L, 2L), recording.events.map { it.sequence })
        assertTrue(recording.events.all { it.playbackSessionId == null })
        assertEquals(1L, recording.droppedSnapshotCount)
        assertEquals(1L, recording.droppedEventCount)
        assertEquals(0L, recording.observedEventSequenceGapCount)
        assertEquals(2, recording.limits.maxSnapshots)
        assertEquals(2, recording.limits.maxEvents)
    }

    @Test
    fun `rolling events are filtered and global sequence crosses playback sessions`() = runBlocking {
        val oldEvent = event(sequence = 4, epochMs = 800, elapsedMs = 80, sessionId = "first")
        val firstRecordedEvent = event(
            sequence = 5,
            epochMs = 950,
            elapsedMs = 95,
            sessionId = "first",
        )
        val eventAfterGap = event(
            sequence = 8,
            epochMs = 1_900,
            elapsedMs = 190,
            sessionId = "second",
        )
        val staleCrossSessionEvent = event(
            sequence = 7,
            epochMs = 2_000,
            elapsedMs = 200,
            sessionId = "third",
        )
        val fake = FakePlaybackTelemetry(
            snapshot(
                epochMs = 1_000,
                elapsedMs = 100,
                sessionId = "playback-session",
                recentEvents = listOf(oldEvent, firstRecordedEvent),
            ),
        )
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(
                DiagnosticTimestamp(epochMs = 900, elapsedRealtimeMs = 90),
                DiagnosticTimestamp(epochMs = 2_100, elapsedRealtimeMs = 210),
            ),
        )

        recorder.start()
        yield()
        fake.publish(
            snapshot(
                epochMs = 2_000,
                elapsedMs = 200,
                sessionId = "second",
                recentEvents = listOf(oldEvent, firstRecordedEvent, eventAfterGap),
            ),
        )
        yield()
        fake.publish(
            snapshot(
                epochMs = 2_050,
                elapsedMs = 205,
                sessionId = "third",
                recentEvents = listOf(staleCrossSessionEvent),
            ),
        )
        yield()
        val recording = requireNotNull(recorder.stop())

        assertEquals(listOf(5L, 8L), recording.events.map { it.sequence })
        assertEquals(listOf("first", "second"), recording.events.map { it.playbackSessionId })
        assertEquals(2L, recording.observedEventSequenceGapCount)
    }

    @Test
    fun `captured collections and stopped recording are immutable`() = runBlocking {
        val relatedIds = linkedSetOf(TelemetryMetricCatalog.PLAYBACK_STATE)
        val audioInterfaces = mutableListOf(UsbAudioInterfaceReading(1, 2, 0))
        val sampleRates = mutableListOf(48_000)
        val channelCounts = mutableListOf(2)
        val encodings = mutableListOf("PCM_16BIT")
        val hostDevices = mutableListOf(
            UsbHostDeviceReading(
                snapshotKey = "host-key",
                manufacturerName = "manufacturer",
                productName = "product",
                vendorId = 1,
                productId = 2,
                permissionGranted = true,
                audioInterfaces = audioInterfaces,
            ),
        )
        val endpoints = mutableListOf(
            UsbAudioOutputEndpointReading(
                snapshotKey = "endpoint-key",
                productName = "product",
                type = "usb_device",
                sampleRatesHz = sampleRates,
                arbitrarySampleRate = false,
                channelCounts = channelCounts,
                arbitraryChannelCount = false,
                encodings = encodings,
                arbitraryEncoding = false,
            ),
        )
        val events = mutableListOf(
            event(
                sequence = 0,
                epochMs = 1_000,
                elapsedMs = 100,
                relatedMetricIds = relatedIds,
            ),
        )
        val sourceSnapshot = TelemetrySnapshot(
            capturedAtEpochMs = 1_000,
            capturedAtElapsedRealtimeMs = 100,
            playbackSessionId = "session",
            metrics = mutableListOf(
                TelemetryMetric(
                    id = TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
                    section = TelemetrySection.USB,
                    evidence = TelemetryEvidence.Measured(
                        reading = TelemetryReading.UsbInventory(
                            UsbInventoryReading(hostDevices, endpoints),
                        ),
                        source = TelemetryDataSource(
                            TelemetrySourceId("adapter.usb_manager"),
                            detail = "content://private/device IllegalStateException",
                        ),
                        observedAtEpochMs = 1_000,
                        observedAtElapsedRealtimeMs = 100,
                    ),
                ),
            ),
            recentEvents = events,
        )
        val fake = FakePlaybackTelemetry(sourceSnapshot)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )

        recorder.start()
        yield()
        events.clear()
        relatedIds.clear()
        audioInterfaces.clear()
        sampleRates.clear()
        channelCounts.clear()
        encodings.clear()
        hostDevices.clear()
        endpoints.clear()
        val recording = requireNotNull(recorder.stop())

        assertEquals(1, recording.events.size)
        assertEquals(setOf(TelemetryMetricCatalog.PLAYBACK_STATE), recording.events.single().relatedMetricIds)
        val capturedEvidence = recording.snapshots.single().metrics.single().evidence
        assertEquals(null, capturedEvidence.source?.detail)
        val inventory = (capturedEvidence.reading as TelemetryReading.UsbInventory).value
        assertEquals(1, inventory.hostDevices.size)
        assertEquals(1, inventory.hostDevices.single().audioInterfaces.size)
        assertEquals(listOf(48_000), inventory.audioOutputEndpoints.single().sampleRatesHz)
        assertEquals(listOf(2), inventory.audioOutputEndpoints.single().channelCounts)
        assertEquals(listOf("PCM_16BIT"), inventory.audioOutputEndpoints.single().encodings)
        assertUnsupported { (recording.snapshots as MutableList).clear() }
        assertUnsupported { (recording.events as MutableList).clear() }
        assertUnsupported { (recording.events.single().relatedMetricIds as MutableSet).clear() }
        assertUnsupported { (inventory.hostDevices as MutableList).clear() }
        assertUnsupported { (inventory.hostDevices.single().audioInterfaces as MutableList).clear() }
        assertUnsupported { (inventory.audioOutputEndpoints.single().sampleRatesHz as MutableList).clear() }
    }

    @Test
    fun `write failure leaves the same stopped recording available for retry`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(1_000, 100, "session"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )
        recorder.start()
        yield()
        val recording = requireNotNull(recorder.stop())

        val result = recorder.exportTo(
            object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("destination disappeared")
                }
            },
        )

        assertEquals(
            DiagnosticExportResult.Failure(DiagnosticExportFailure.WRITE_FAILED),
            result,
        )
        assertSame(recording, recorder.retainedRecording())
        assertSame(recording, (recorder.state.value as DiagnosticRecordingState.Stopped).recording)
        assertTrue(recorder.exportTo(ByteArrayOutputStream()) is DiagnosticExportResult.Success)
    }

    @Test
    fun `recording cannot be cleared while an export owns it`() = runBlocking {
        val fake = FakePlaybackTelemetry(snapshot(1_000, 100, "session"))
        val recorder = DiagnosticRecorder(
            playbackTelemetry = fake,
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )
        recorder.start()
        yield()
        recorder.stop()
        val output = BlockingOutputStream()
        val export = async(start = CoroutineStart.UNDISPATCHED) { recorder.exportTo(output) }

        try {
            assertTrue(output.writeEntered.await(5, TimeUnit.SECONDS))
            assertFalse(recorder.clear())
        } finally {
            output.allowWrite.countDown()
        }

        assertTrue(export.await() is DiagnosticExportResult.Success)
        assertTrue(recorder.clear())
    }

    @Test
    fun `recording remains protected until every concurrent export releases it`() = runBlocking {
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session")),
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )
        recorder.start()
        yield()
        recorder.stop()
        val firstOutput = BlockingOutputStream()
        val secondOutput = BlockingOutputStream()
        val firstExport = async(start = CoroutineStart.UNDISPATCHED) {
            recorder.exportTo(firstOutput)
        }
        val secondExport = async(start = CoroutineStart.UNDISPATCHED) {
            recorder.exportTo(secondOutput)
        }

        assertTrue(firstOutput.writeEntered.await(5, TimeUnit.SECONDS))
        assertTrue(secondOutput.writeEntered.await(5, TimeUnit.SECONDS))
        assertFalse(recorder.clear())

        firstOutput.allowWrite.countDown()
        assertTrue(firstExport.await() is DiagnosticExportResult.Success)
        assertFalse(recorder.clear())

        secondOutput.allowWrite.countDown()
        assertTrue(secondExport.await() is DiagnosticExportResult.Success)
        assertTrue(recorder.clear())
    }

    @Test
    fun `cancelled export releases its lease and retains the stopped recording`() = runBlocking {
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session")),
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )
        recorder.start()
        yield()
        val recording = requireNotNull(recorder.stop())
        val output = BlockingOutputStream()
        val export = async(start = CoroutineStart.UNDISPATCHED) { recorder.exportTo(output) }

        assertTrue(output.writeEntered.await(5, TimeUnit.SECONDS))
        export.cancel()
        output.allowWrite.countDown()
        export.join()

        assertTrue(export.isCancelled)
        assertSame(recording, recorder.retainedRecording())
        assertTrue(recorder.clear())
    }

    @Test
    fun `source exception seals a failed recording without retaining exception text`() = runBlocking {
        val telemetry = object : PlaybackTelemetry {
            override fun observe(observation: TelemetryObservation) = flow {
                emit(snapshot(1_000, 100, "session"))
                throw IllegalStateException("content://private/file should never be exported")
            }
        }
        val recorder = DiagnosticRecorder(
            playbackTelemetry = telemetry,
            scope = this,
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )

        assertTrue(recorder.start())
        yield()
        val stopped = recorder.state.value as DiagnosticRecordingState.Stopped
        assertEquals(DiagnosticRecordingTermination.SOURCE_FAILED, stopped.recording.termination)
        assertSame(stopped.recording, recorder.stop())
    }

    @Test
    fun `already cancelled owner still seals the recording`() = runBlocking {
        val owner = Job().apply { cancel() }
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            clock = QueueClock(DiagnosticTimestamp(900, 90), DiagnosticTimestamp(1_100, 110)),
        )

        assertTrue(recorder.start())
        val stopped = recorder.state.value as DiagnosticRecordingState.Stopped
        assertEquals(DiagnosticRecordingTermination.OWNER_CANCELLED, stopped.recording.termination)
        assertSame(stopped.recording, recorder.stop())
    }

    private fun snapshot(
        epochMs: Long,
        elapsedMs: Long,
        sessionId: String?,
        recentEvents: List<TelemetryEvent> = emptyList(),
    ) = TelemetrySnapshot(
        capturedAtEpochMs = epochMs,
        capturedAtElapsedRealtimeMs = elapsedMs,
        playbackSessionId = sessionId,
        recentEvents = recentEvents,
    )

    private fun event(
        sequence: Long,
        epochMs: Long,
        elapsedMs: Long,
        sessionId: String? = null,
        relatedMetricIds: Set<io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId> = emptySet(),
    ) = TelemetryEvent(
        sequence = sequence,
        kind = TelemetryEventKind.PLAYBACK_STATE_CHANGED,
        severity = TelemetryEventSeverity.INFO,
        occurredAtEpochMs = epochMs,
        occurredAtElapsedRealtimeMs = elapsedMs,
        code = "playback.state_changed",
        playbackSessionId = sessionId,
        relatedMetricIds = relatedMetricIds,
    )

    private fun assertUnsupported(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertTrue(thrown is UnsupportedOperationException)
    }

    private class QueueClock(vararg timestamps: DiagnosticTimestamp) : DiagnosticClock {
        private val remaining = ArrayDeque(timestamps.toList())

        override fun now(): DiagnosticTimestamp = remaining.removeFirst()
    }

    private class BlockingOutputStream : OutputStream() {
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        private val delegate = ByteArrayOutputStream()

        override fun write(value: Int) {
            awaitRelease()
            delegate.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            awaitRelease()
            delegate.write(buffer, offset, length)
        }

        private fun awaitRelease() {
            writeEntered.countDown()
            check(allowWrite.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release export" }
        }
    }
}
