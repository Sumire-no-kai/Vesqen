package io.github.sumirenokai.vesqen.diagnostics

import io.github.sumirenokai.vesqen.telemetry.FakePlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentDiagnosticRecorderTest {
    @Test
    fun `restore exposes interrupted recording and blocks replacement until clear`() = runBlocking {
        val restored = summary(DiagnosticRecordingTermination.PROCESS_TERMINATED)
        val store = FakeStore(restored = restored)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = this,
            store = store,
            clock = clock(),
        )

        recorder.restore()
        yield()

        assertEquals(DiagnosticRecordingState.Recovered(restored), recorder.state.value)
        assertFalse(recorder.start())
        assertTrue(recorder.clear())
        yield()
        assertEquals(listOf(restored.id), store.clearedIds)
        assertEquals(DiagnosticRecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `captured samples reach private store before user stop returns`() = runBlocking {
        val telemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session"))
        val store = FakeStore()
        val recorder = DiagnosticRecorder(
            playbackTelemetry = telemetry,
            scope = this,
            store = store,
            clock = clock(),
        )

        assertTrue(recorder.start())
        yield()
        val stopped = requireNotNull(recorder.stop())

        assertEquals(listOf(stopped.id), store.startedIds)
        assertEquals(listOf(100L), store.batches.map { it.snapshot.capturedAtElapsedRealtimeMs })
        assertEquals(listOf(stopped.summary()), store.finished)
    }

    @Test
    fun `storage failure seals recording without silently continuing`() = runBlocking {
        val store = FakeStore(acceptAppend = false)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session")),
            scope = this,
            store = store,
            clock = clock(),
        )

        assertTrue(recorder.start())
        yield()

        val stopped = recorder.state.value as DiagnosticRecordingState.Stopped
        assertEquals(DiagnosticRecordingTermination.STORAGE_FAILED, stopped.recording.termination)
        assertEquals(stopped.recording, recorder.stop())
    }

    @Test
    fun `every concurrent stop waits for the final persistence marker`() = runBlocking {
        val allowFinish = CompletableDeferred<Unit>()
        val store = FakeStore(finishGate = allowFinish)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session")),
            scope = this,
            store = store,
            clock = clock(),
        )
        assertTrue(recorder.start())
        yield()

        val first = async { recorder.stop() }
        store.finishEntered.await()
        val second = async { recorder.stop() }
        yield()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        allowFinish.complete(Unit)
        assertEquals(requireNotNull(first.await()).id, requireNotNull(second.await()).id)
    }

    @Test
    fun `final persistence failure is visible and keeps the in-memory recording`() = runBlocking {
        val store = FakeStore(acceptFinish = false)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(snapshot(1_000, 100, "session")),
            scope = this,
            store = store,
            clock = clock(),
        )
        assertTrue(recorder.start())
        yield()

        val stopped = requireNotNull(recorder.stop())

        assertEquals(DiagnosticRecordingTermination.STORAGE_FAILED, stopped.termination)
        assertEquals(
            DiagnosticRecordingTermination.STORAGE_FAILED,
            (recorder.state.value as DiagnosticRecordingState.Stopped).recording.termination,
        )
    }

    @Test
    fun `recovered recording exports through persistent store`() = runBlocking {
        val restored = summary(DiagnosticRecordingTermination.PROCESS_TERMINATED)
        val store = FakeStore(restored = restored, exportPayload = "{\"recovered\":true}\n")
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = this,
            store = store,
            clock = clock(),
        )
        recorder.restore()
        yield()
        val output = ByteArrayOutputStream()

        val result = recorder.exportTo(output)

        assertTrue(result is DiagnosticExportResult.Success)
        assertEquals("{\"recovered\":true}\n", output.toString(StandardCharsets.UTF_8.name()))
        assertEquals(listOf(restored.id), store.exportedIds)
        assertEquals(DiagnosticRecordingState.Recovered(restored), recorder.state.value)
    }

    @Test
    fun `persistent export never converts cancellation into a write failure`() = runBlocking {
        val restored = summary(DiagnosticRecordingTermination.PROCESS_TERMINATED)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = this,
            store = FakeStore(restored = restored, cancelExport = true),
            clock = clock(),
        )
        recorder.restore()
        yield()

        var cancellationObserved = false
        try {
            recorder.exportTo(ByteArrayOutputStream())
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(DiagnosticRecordingState.Recovered(restored), recorder.state.value)
    }

    @Test
    fun `failed persistent clear restores the retained recording instead of losing it`() = runBlocking {
        val restored = summary(DiagnosticRecordingTermination.PROCESS_TERMINATED)
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = this,
            store = FakeStore(restored = restored, acceptClear = false),
            clock = clock(),
        )
        recorder.restore()
        yield()

        assertTrue(recorder.clear())
        yield()

        assertEquals(DiagnosticRecordingState.Recovered(restored), recorder.state.value)
    }

    @Test
    fun `cancelled application scope never starts persistence work`() {
        val owner = SupervisorJob().apply { cancel() }
        val store = FakeStore()
        val recorder = DiagnosticRecorder(
            playbackTelemetry = FakePlaybackTelemetry(),
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            store = store,
            clock = clock(),
        )

        assertTrue(recorder.start())

        assertTrue(store.startedIds.isEmpty())
        assertTrue(recorder.state.value is DiagnosticRecordingState.Stopped)
    }

    private fun summary(termination: DiagnosticRecordingTermination) = DiagnosticRecordingSummary(
        id = "recording-restored",
        startedAt = DiagnosticTimestamp(1_000, 100),
        stoppedAt = DiagnosticTimestamp(2_000, 200),
        termination = termination,
        limits = DiagnosticRecordingLimits(maxSnapshots = 3, maxEvents = 4),
        snapshotCount = 1,
        eventCount = 2,
        droppedSnapshotCount = 0,
        droppedEventCount = 0,
        observedEventSequenceGapCount = 0,
    )

    private fun snapshot(epochMs: Long, elapsedMs: Long, sessionId: String?) = TelemetrySnapshot(
        capturedAtEpochMs = epochMs,
        capturedAtElapsedRealtimeMs = elapsedMs,
        playbackSessionId = sessionId,
    )

    private fun clock() = QueueClock(
        DiagnosticTimestamp(900, 90),
        DiagnosticTimestamp(2_100, 210),
    )

    private class QueueClock(vararg values: DiagnosticTimestamp) : DiagnosticClock {
        private val timestamps = ArrayDeque(values.toList())
        override fun now(): DiagnosticTimestamp = timestamps.removeFirst()
    }

    private class FakeStore(
        private val restored: DiagnosticRecordingSummary? = null,
        private val acceptAppend: Boolean = true,
        private val acceptFinish: Boolean = true,
        private val acceptClear: Boolean = true,
        private val exportPayload: String = "{}\n",
        private val cancelExport: Boolean = false,
        private val finishGate: CompletableDeferred<Unit>? = null,
    ) : DiagnosticRecordingStore {
        val startedIds = mutableListOf<String>()
        val batches = mutableListOf<DiagnosticRecordingBatch>()
        val finished = mutableListOf<DiagnosticRecordingSummary>()
        val clearedIds = mutableListOf<String>()
        val exportedIds = mutableListOf<String>()
        val finishEntered = CompletableDeferred<Unit>()

        override suspend fun restore(): DiagnosticRecordingSummary? = restored

        override suspend fun begin(recording: DiagnosticRecordingStart): Boolean {
            startedIds += recording.id
            return true
        }

        override suspend fun append(batch: DiagnosticRecordingBatch): Boolean {
            batches += batch
            return acceptAppend
        }

        override suspend fun finish(summary: DiagnosticRecordingSummary): Boolean {
            finishEntered.complete(Unit)
            finishGate?.await()
            finished += summary
            return acceptFinish
        }

        override suspend fun clear(recordingId: String): Boolean {
            clearedIds += recordingId
            return acceptClear
        }

        override suspend fun export(
            recordingId: String,
            output: OutputStream,
        ): DiagnosticExportResult {
            if (cancelExport) throw CancellationException("cancel persistent export")
            exportedIds += recordingId
            output.write(exportPayload.toByteArray(StandardCharsets.UTF_8))
            return DiagnosticExportResult.Success(restored?.snapshotCount ?: 0, restored?.eventCount ?: 0)
        }
    }
}
