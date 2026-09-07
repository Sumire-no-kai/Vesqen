package io.github.sumirenokai.vesqen.playback

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryRecorderTest {
    @Test
    fun `failed write does not affect playback or disable the next history update`() = runBlocking {
        val ownerJob = SupervisorJob()
        val backend = FakePlaybackHistoryBackend(failTrackId = 1)
        val recorder = PlaybackHistoryRecorder(
            scope = CoroutineScope(ownerJob + Dispatchers.Unconfined),
            backend = backend,
            clock = { 456L },
        )
        val nextRecorded = async(start = CoroutineStart.UNDISPATCHED) {
            recorder.recordedTrackIds.first()
        }

        recorder.recordPlayback(1)
        recorder.recordPlayback(2)

        assertEquals(2L, withTimeout(2_000) { nextRecorded.await() })
        assertEquals(listOf(1L to 456L, 2L to 456L), backend.attempts.toList())

        ownerJob.cancel()
        withTimeout(2_000) { backend.closed.await() }
    }

    @Test
    fun `blocked backend keeps a bounded window of newest pending events and resumes consumption`() = runBlocking {
        val ownerJob = SupervisorJob()
        val capacity = 3
        val backend = BlockingPlaybackHistoryBackend()
        val recorder = PlaybackHistoryRecorder(
            scope = CoroutineScope(ownerJob + Dispatchers.Unconfined),
            backend = backend,
            clock = { 789L },
            requestCapacity = capacity,
        )

        recorder.recordPlayback(0)
        withTimeout(2_000) { backend.firstWriteStarted.await() }
        (1L..6L).forEach(recorder::recordPlayback)

        // The playback-facing calls above are non-suspending even while the writer is blocked.
        assertEquals(listOf(0L), backend.attempts.toList())
        backend.releaseFirstWrite.complete(Unit)
        withTimeout(2_000) { backend.lastWriteCompleted.await() }

        assertEquals(listOf(0L, 4L, 5L, 6L), backend.attempts.toList())
        assertTrue(backend.attempts.size <= capacity + 1)

        ownerJob.cancel()
        withTimeout(2_000) { backend.closed.await() }
    }

    private class FakePlaybackHistoryBackend(
        private val failTrackId: Long,
    ) : PlaybackHistoryBackend {
        val attempts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val closed = CompletableDeferred<Unit>()

        override suspend fun recordPlayback(trackId: Long, playedAtMs: Long) {
            attempts += trackId to playedAtMs
            if (trackId == failTrackId) error("simulated write failure")
        }

        override fun close() {
            closed.complete(Unit)
        }
    }

    private class BlockingPlaybackHistoryBackend : PlaybackHistoryBackend {
        val attempts = Collections.synchronizedList(mutableListOf<Long>())
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val lastWriteCompleted = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        override suspend fun recordPlayback(trackId: Long, playedAtMs: Long) {
            attempts += trackId
            if (trackId == 0L) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
            if (trackId == 6L) lastWriteCompleted.complete(Unit)
        }

        override fun close() {
            closed.complete(Unit)
        }
    }
}
