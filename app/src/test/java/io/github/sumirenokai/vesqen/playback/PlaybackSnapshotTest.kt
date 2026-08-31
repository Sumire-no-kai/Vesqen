package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
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
}
