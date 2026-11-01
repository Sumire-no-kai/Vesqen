package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #216: a drag on the progress bar seeks only the queue entry it started on. */
class NowProgressScrubUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val seeks = mutableListOf<Long>()
    private var snapshot by mutableStateOf(entry("a", trackId = 1))

    /** Both songs last as long, so the slider's range does not change and end the drag by itself. */
    private fun entry(id: String, trackId: Long) = PlaybackSnapshot(
        isControllerReady = true,
        isPlaying = true,
        trackId = trackId,
        queueEntryId = id,
        title = "Song $trackId",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000,
        positionMs = 10_000,
        queueSize = 2,
    )

    private fun setNow() = compose.setContent {
        VesqenTheme(darkTheme = false) {
            Box(Modifier.size(360.dp, 720.dp)) {
                NowScreen(
                    snapshot = snapshot,
                    currentTrack = null,
                    artworkTrack = null,
                    onBackToLibrary = {},
                    onOpenChain = {},
                    onCyclePlaybackOrder = {},
                    onPrevious = {},
                    onPlayPause = {},
                    onNext = {},
                    onSeek = { seeks += it },
                    onPlayQueueIndex = {},
                    onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> },
                    onClearQueue = {},
                    onRetryPlayback = {},
                    onToggleOrientation = {},
                    showOrientationToggle = false,
                    isLandscape = false,
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }
    }

    private fun dragToTheMiddleThen(duringDrag: () -> Unit) {
        val progress = compose.onNodeWithTag("vesqen.now.progress")
        progress.performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        duringDrag()
        compose.waitForIdle()
        progress.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun aDragSeeksItsOwnSong() {
        setNow()
        dragToTheMiddleThen {}
        assertEquals(1, seeks.size)
        assertTrue("Seek ${seeks.single()} is near the middle", seeks.single() in 80_000L..120_000L)
    }

    @Test
    fun aTapSeeksOnce() {
        setNow()
        compose.onNodeWithTag("vesqen.now.progress").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, seeks.size)
    }

    @Test
    fun aDragDoesNotSeekTheSongPlayingAfterItsOwnEnded() {
        setNow()
        dragToTheMiddleThen { snapshot = entry("b", trackId = 2).copy(positionMs = 0) }
        assertEquals(emptyList<Long>(), seeks)
    }

    @Test
    fun aDragDoesNotSeekTheSameSongQueuedAgain() {
        setNow()
        // A headphone Next to the same track's next entry in the queue.
        dragToTheMiddleThen { snapshot = entry("a2", trackId = 1).copy(positionMs = 0) }
        assertEquals(emptyList<Long>(), seeks)
    }
}
