package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.screens.NowScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The cover settles on a pause, and a seek is not a pause. */
class NowCoverPlayStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val track = AudioTrack(7, "content://media/external/audio/media/7", "Evening", "Artist", "Album", 60_000)
    private val playing = PlaybackSnapshot(
        isControllerReady = true,
        isPlaying = true,
        trackId = track.id,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        queueSize = 1,
    )
    private var snapshot by mutableStateOf(playing)

    private fun coverWidth() = compose.onNodeWithTag("vesqen.now.artwork", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.width

    private fun show() = compose.setContent {
        VesqenTheme(darkTheme = true) {
            NowScreen(
                snapshot = snapshot,
                currentTrack = track,
                artworkTrack = track,
                onBackToLibrary = {},
                onOpenChain = {},
                onCyclePlaybackOrder = {},
                onPrevious = {},
                onPlayPause = {},
                onNext = {},
                onSeek = { _, _ -> },
                onPlayQueueIndex = {},
                onRemoveQueueItem = {},
                onMoveQueueItem = { _, _ -> },
                onClearQueue = {},
                onRetryPlayback = {},
                onToggleOrientation = {},
                showOrientationToggle = false,
                isLandscape = false,
                motionPolicy = VesqenMotionPolicy(reduceMotion = false),
            )
        }
    }

    @Test
    fun bufferingAfterASeekKeepsTheCoverItsSize() {
        show()
        compose.waitForIdle()
        val full = coverWidth()

        // Media3 buffers for a moment after a seek: not playing, and not paused either.
        snapshot = playing.copy(isPlaying = false, showsPauseAction = true)
        compose.waitForIdle()
        assertEquals("Buffering should not move the cover", full, coverWidth(), 0.5f)
    }

    @Test
    fun aPauseStillSettlesTheCoverAndPlayBringsItBack() {
        show()
        compose.waitForIdle()
        val full = coverWidth()

        snapshot = playing.copy(isPlaying = false, showsPauseAction = false)
        compose.waitForIdle()
        assertTrue("A pause should settle the cover, at ${coverWidth()} of $full", coverWidth() < full * .96f)

        snapshot = playing
        compose.waitForIdle()
        assertEquals("Play should bring the cover back", full, coverWidth(), 0.5f)
    }
}
