package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.Player
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
    fun `reaching the end of the queue is saved within the same position bucket`() {
        gate.decide(observation(positionMs = 60_000))
        assertEquals(
            PlaybackPersistenceDecision.SAVE_PROGRESS,
            gate.decide(observation(positionMs = 60_000).copy(queueEnded = true)),
        )
    }

    @Test
    fun `the end survives a stop until the player moves away`() {
        // The album ended; dismissing the notification then stops the player, which goes idle at the end.
        assertEquals(true, queueEndedAfter(previous = false, playbackState = Player.STATE_ENDED, movedAway = false))
        assertEquals(true, queueEndedAfter(previous = true, playbackState = Player.STATE_IDLE, movedAway = false))
        // A seek, another item or a new queue ends it; so does playing again.
        assertEquals(false, queueEndedAfter(previous = true, playbackState = Player.STATE_IDLE, movedAway = true))
        assertEquals(false, queueEndedAfter(previous = true, playbackState = Player.STATE_READY, movedAway = false))
        assertEquals(false, queueEndedAfter(previous = true, playbackState = Player.STATE_BUFFERING, movedAway = false))
        // A stop before any end is not an end.
        assertEquals(false, queueEndedAfter(previous = false, playbackState = Player.STATE_IDLE, movedAway = false))
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
