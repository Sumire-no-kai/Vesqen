package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.navigation.VesqenDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesqenPlaybackRefreshPolicyTest {
    @Test
    fun `position ticker runs only for a playing focused track`() {
        val playingTrack = PlaybackSnapshot(trackId = 7, isPlaying = true)

        assertTrue(shouldRefreshPlaybackPosition(VesqenDestination.NOW, playingTrack))
        assertFalse(shouldRefreshPlaybackPosition(VesqenDestination.LIBRARY, playingTrack))
        assertFalse(shouldRefreshPlaybackPosition(VesqenDestination.CHAIN, playingTrack))
        assertFalse(
            shouldRefreshPlaybackPosition(
                VesqenDestination.NOW,
                playingTrack.copy(isPlaying = false),
            ),
        )
        assertFalse(
            shouldRefreshPlaybackPosition(
                VesqenDestination.NOW,
                playingTrack.copy(trackId = null),
            ),
        )
    }
}
