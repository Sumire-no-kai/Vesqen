package io.github.sumirenokai.vesqen.ui.screens

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * #51: background playback on Honor kept the main thread at 30–50% CPU. When the activity stops,
 * the window recomposer pauses `withFrameNanos`, so a Now transition cannot settle. A transition
 * target that changed under the same content key (a play count update) made AnimatedContent
 * rewrite its visible list on every recomposition, which scheduled the next one.
 */
class NowBackgroundRecompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun catalogUpdateWhileStoppedLetsCompositionGoIdle() {
        val track = AudioTrack(
            id = 7,
            contentUri = "content://media/external/audio/media/7",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
        )
        var current by mutableStateOf(track)
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                NowScreen(
                    snapshot = PlaybackSnapshot(
                        isControllerReady = true,
                        isPlaying = true,
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        queueSize = 1,
                    ),
                    currentTrack = current,
                    artworkTrack = current,
                    onBackToLibrary = {},
                    onOpenChain = {},
                    onCyclePlaybackOrder = {},
                    onPrevious = {},
                    onPlayPause = {},
                    onNext = {},
                    onSeek = {},
                    onPlayTrack = {},
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
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val recomposer = compose.runOnUiThread {
            compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                .findViewTreeCompositionContext() as Recomposer
        }
        // What ON_STOP does to the window recomposer.
        recomposer.pauseCompositionFrameClock()
        try {
            compose.runOnUiThread { current = track.copy(playCount = 1, lastPlayedAtMs = 1_000) }
            repeat(30) { compose.mainClock.advanceTimeByFrame() }
            assertFalse("Now keeps recomposing while its animations are paused", recomposer.hasPendingWork)
        } finally {
            recomposer.resumeCompositionFrameClock()
        }
    }
}
