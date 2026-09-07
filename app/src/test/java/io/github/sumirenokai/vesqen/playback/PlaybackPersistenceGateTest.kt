package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPersistenceGateTest {
    private val gate = PlaybackPersistenceGate(positionBucketMs = 5_000)

    @Test
    fun `moving to another occurrence of the same track updates the recovery cursor`() {
        gate.decide(observation())
        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(observation().copy(currentQueueIndex = 2)),
        )
    }

    @Test
    fun `an initially empty player does not erase a restorable queue`() {
        assertEquals(
            PlaybackPersistenceDecision.SKIP,
            gate.decide(observation(hasQueue = false, currentTrackId = null)),
        )
    }

    @Test
    fun `position checkpoints write once per five second bucket`() {
        assertEquals(PlaybackPersistenceDecision.SAVE_QUEUE, gate.decide(observation(positionMs = 0)))
        assertEquals(PlaybackPersistenceDecision.SKIP, gate.decide(observation(positionMs = 4_999)))
        assertEquals(PlaybackPersistenceDecision.SAVE_PROGRESS, gate.decide(observation(positionMs = 5_000)))
        assertEquals(PlaybackPersistenceDecision.SKIP, gate.decide(observation(positionMs = 9_999)))
        assertEquals(PlaybackPersistenceDecision.SAVE_PROGRESS, gate.decide(observation(positionMs = 10_000)))
    }

    @Test
    fun `queue current item and playback order changes bypass the position bucket`() {
        assertEquals(PlaybackPersistenceDecision.SAVE_QUEUE, gate.decide(observation()))
        assertEquals(
            PlaybackPersistenceDecision.SAVE_QUEUE,
            gate.decide(observation(queueRevision = 2)),
        )
        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(observation(queueRevision = 2, currentTrackId = 2)),
        )
        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(
                observation(
                    queueRevision = 2,
                    currentTrackId = 2,
                    repeatMode = PlaybackRepeatMode.ALL,
                ),
            ),
        )
    }

    @Test
    fun `clearing an observed queue clears storage exactly once`() {
        gate.decide(observation())
        assertEquals(
            PlaybackPersistenceDecision.CLEAR,
            gate.decide(observation(hasQueue = false, currentTrackId = null)),
        )
        assertEquals(
            PlaybackPersistenceDecision.SKIP,
            gate.decide(observation(hasQueue = false, currentTrackId = null)),
        )
    }

    @Test
    fun `a lifecycle boundary forces the exact position to be saved`() {
        gate.decide(observation(positionMs = 1_000))
        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(observation(positionMs = 1_001), force = true),
        )
    }

    @Test
    fun `position discontinuity forces an exact paused seek within the same bucket`() {
        gate.decide(observation(positionMs = 1_000))

        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(
                observation(positionMs = 1_001),
                force = shouldForcePlaybackPersistence(
                    positionDiscontinuity = true,
                    playingChanged = false,
                    isPlaying = false,
                ),
            ),
        )
    }

    @Test
    fun `persistence policy forces pause but not ordinary playback events`() {
        assertEquals(
            true,
            shouldForcePlaybackPersistence(
                positionDiscontinuity = false,
                playingChanged = true,
                isPlaying = false,
            ),
        )
        assertEquals(
            false,
            shouldForcePlaybackPersistence(
                positionDiscontinuity = false,
                playingChanged = false,
                isPlaying = false,
            ),
        )
    }

    private fun observation(
        queueRevision: Long = 1,
        hasQueue: Boolean = true,
        currentTrackId: Long? = 1,
        positionMs: Long = 0,
        shuffleEnabled: Boolean = false,
        repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    ) = PlaybackPersistenceObservation(
        queueRevision = queueRevision,
        hasQueue = hasQueue,
        currentTrackId = currentTrackId,
        positionMs = positionMs,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}
