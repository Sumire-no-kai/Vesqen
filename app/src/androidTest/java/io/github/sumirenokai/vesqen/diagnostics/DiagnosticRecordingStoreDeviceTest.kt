package io.github.sumirenokai.vesqen.diagnostics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticRecordingStoreDeviceTest {
    @Test
    fun stoppedRecordingPersistsNewestBoundedSamplesAndExportsPrivateJson() = runBlocking {
        val store = AndroidDiagnosticRecordingStore(ApplicationProvider.getApplicationContext())
        val start = DiagnosticRecordingStart(
            id = "device-store-stopped",
            startedAt = DiagnosticTimestamp(900, 90),
            limits = DiagnosticRecordingLimits(maxSnapshots = 2, maxEvents = 2),
        )
        assertTrue(store.begin(start))
        repeat(3) { index ->
            val ordinal = index + 1
            assertTrue(
                store.append(
                    DiagnosticRecordingBatch(
                        recordingId = start.id,
                        snapshot = TelemetrySnapshot(
                            capturedAtEpochMs = ordinal * 1_000L,
                            capturedAtElapsedRealtimeMs = ordinal * 100L,
                            playbackSessionId = "private-session-$ordinal",
                        ),
                        events = listOf(event(ordinal.toLong(), ordinal * 1_000L, ordinal * 100L)),
                        progress = DiagnosticRecordingProgress(
                            id = start.id,
                            startedAt = start.startedAt,
                            snapshotCount = ordinal.coerceAtMost(2),
                            eventCount = ordinal.coerceAtMost(2),
                            droppedSnapshotCount = (ordinal - 2).coerceAtLeast(0).toLong(),
                            droppedEventCount = (ordinal - 2).coerceAtLeast(0).toLong(),
                            observedEventSequenceGapCount = 0,
                        ),
                    ),
                ),
            )
        }
        val summary = DiagnosticRecordingSummary(
            id = start.id,
            startedAt = start.startedAt,
            stoppedAt = DiagnosticTimestamp(3_100, 310),
            termination = DiagnosticRecordingTermination.USER_STOPPED,
            limits = start.limits,
            snapshotCount = 2,
            eventCount = 2,
            droppedSnapshotCount = 1,
            droppedEventCount = 1,
            observedEventSequenceGapCount = 0,
        )
        assertTrue(store.finish(summary))

        val restored = AndroidDiagnosticRecordingStore(
            ApplicationProvider.getApplicationContext(),
        ).restore()
        assertEquals(summary, restored)
        val output = ByteArrayOutputStream()
        val result = store.export(start.id, output)
        val json = output.toString(StandardCharsets.UTF_8.name())

        assertTrue(result is DiagnosticExportResult.Success)
        assertTrue(json.contains("\"termination\":\"user_stopped\""))
        assertFalse(json.contains("\"capturedAtEpochMs\":1000"))
        assertTrue(json.contains("\"capturedAtEpochMs\":2000"))
        assertTrue(json.contains("\"capturedAtEpochMs\":3000"))
        assertFalse(json.contains("private-session"))
        assertTrue(store.clear(start.id))
        assertNull(store.restore())
        assertTrue(store.clear(start.id))
    }

    @Test
    fun activeRecordingIsSealedAsProcessTerminatedOnNextStoreOpen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstProcess = AndroidDiagnosticRecordingStore(context)
        val start = DiagnosticRecordingStart(
            id = "device-store-interrupted",
            startedAt = DiagnosticTimestamp(1_000, 100),
            limits = DiagnosticRecordingLimits(maxSnapshots = 2, maxEvents = 2),
        )
        assertTrue(firstProcess.begin(start))
        assertTrue(
            firstProcess.append(
                DiagnosticRecordingBatch(
                    recordingId = start.id,
                    snapshot = TelemetrySnapshot(
                        capturedAtEpochMs = 2_000,
                        capturedAtElapsedRealtimeMs = 200,
                        playbackSessionId = "private-interrupted-session",
                    ),
                    events = listOf(event(1, 2_000, 200)),
                    progress = DiagnosticRecordingProgress(
                        id = start.id,
                        startedAt = start.startedAt,
                        snapshotCount = 1,
                        eventCount = 1,
                        droppedSnapshotCount = 0,
                        droppedEventCount = 0,
                        observedEventSequenceGapCount = 0,
                    ),
                ),
            ),
        )

        val restored = requireNotNull(AndroidDiagnosticRecordingStore(context).restore())

        assertEquals(DiagnosticRecordingTermination.PROCESS_TERMINATED, restored.termination)
        assertEquals(DiagnosticTimestamp(2_000, 200), restored.stoppedAt)
        assertEquals(1, restored.snapshotCount)
        assertEquals(1, restored.eventCount)
        assertTrue(firstProcess.clear(start.id))
    }

    private fun event(sequence: Long, epochMs: Long, elapsedMs: Long) = TelemetryEvent(
        sequence = sequence,
        kind = TelemetryEventKind.PLAYBACK_STATE_CHANGED,
        severity = TelemetryEventSeverity.INFO,
        occurredAtEpochMs = epochMs,
        occurredAtElapsedRealtimeMs = elapsedMs,
        code = "playback.state_changed",
        playbackSessionId = "private-event-session",
    )
}
