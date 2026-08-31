package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSnapshotTest {
    @Test
    fun `progress fraction clamps invalid player positions`() {
        assertEquals(0f, PlaybackSnapshot(durationMs = 0, positionMs = 500).progressFraction)
        assertEquals(1f, PlaybackSnapshot(durationMs = 1_000, positionMs = 2_000).progressFraction)
    }

    @Test
    fun `m1 declaration never overclaims bit perfect playback`() {
        assertEquals(OutputDeclaration.SYSTEM_MIXED, PlaybackSnapshot().declaration)
    }

    @Test
    fun `active track is identified by stable MediaStore id rather than title`() {
        assertEquals(false, PlaybackSnapshot(title = "A title is not a session").hasActiveTrack)
        assertEquals(true, PlaybackSnapshot(trackId = 42, title = "").hasActiveTrack)
    }

    @Test
    fun `queue position is only exposed when Media3 reports a queue`() {
        assertNull(PlaybackSnapshot(queueIndex = 3, queueSize = 0).queuePosition)
        assertEquals(2, PlaybackSnapshot(queueIndex = 8, queueSize = 2).queuePosition)
    }

    @Test
    fun `playback order presents the four ordinary listener modes`() {
        assertEquals(PlaybackOrderMode.SEQUENTIAL, PlaybackSnapshot().playbackOrderMode)
        assertEquals(
            PlaybackOrderMode.SHUFFLE,
            PlaybackSnapshot(shuffleEnabled = true).playbackOrderMode,
        )
        assertEquals(
            PlaybackOrderMode.REPEAT_ALL,
            PlaybackSnapshot(repeatMode = PlaybackRepeatMode.ALL).playbackOrderMode,
        )
        assertEquals(
            PlaybackOrderMode.REPEAT_ONE,
            PlaybackSnapshot(repeatMode = PlaybackRepeatMode.ONE).playbackOrderMode,
        )
    }

    @Test
    fun `playback order surfaces external compound Media3 states exactly`() {
        assertEquals(
            PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
            PlaybackSnapshot(
                shuffleEnabled = true,
                repeatMode = PlaybackRepeatMode.ALL,
            ).playbackOrderMode,
        )
        assertEquals(
            PlaybackOrderMode.SHUFFLE_REPEAT_ONE,
            PlaybackSnapshot(
                shuffleEnabled = true,
                repeatMode = PlaybackRepeatMode.ONE,
            ).playbackOrderMode,
        )
    }

    @Test
    fun `playback order cycle covers sequential shuffle list repeat and single repeat`() {
        assertEquals(PlaybackOrderMode.SHUFFLE, PlaybackOrderMode.SEQUENTIAL.next())
        assertEquals(PlaybackOrderMode.REPEAT_ALL, PlaybackOrderMode.SHUFFLE.next())
        assertEquals(PlaybackOrderMode.REPEAT_ONE, PlaybackOrderMode.REPEAT_ALL.next())
        assertEquals(PlaybackOrderMode.SEQUENTIAL, PlaybackOrderMode.REPEAT_ONE.next())
        assertEquals(PlaybackOrderMode.SEQUENTIAL, PlaybackOrderMode.SHUFFLE_REPEAT_ALL.next())
        assertEquals(PlaybackOrderMode.SEQUENTIAL, PlaybackOrderMode.SHUFFLE_REPEAT_ONE.next())
    }

    @Test
    fun `each listener playback order resets the other Media3 switch`() {
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = false, repeatMode = PlaybackRepeatMode.OFF),
            PlaybackOrderMode.SEQUENTIAL.toSettings(),
        )
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = true, repeatMode = PlaybackRepeatMode.OFF),
            PlaybackOrderMode.SHUFFLE.toSettings(),
        )
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = false, repeatMode = PlaybackRepeatMode.ALL),
            PlaybackOrderMode.REPEAT_ALL.toSettings(),
        )
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = false, repeatMode = PlaybackRepeatMode.ONE),
            PlaybackOrderMode.REPEAT_ONE.toSettings(),
        )
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = true, repeatMode = PlaybackRepeatMode.ALL),
            PlaybackOrderMode.SHUFFLE_REPEAT_ALL.toSettings(),
        )
        assertEquals(
            PlaybackOrderSettings(shuffleEnabled = true, repeatMode = PlaybackRepeatMode.ONE),
            PlaybackOrderMode.SHUFFLE_REPEAT_ONE.toSettings(),
        )
    }
}
