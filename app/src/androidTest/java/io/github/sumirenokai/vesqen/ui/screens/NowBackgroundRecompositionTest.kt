package io.github.sumirenokai.vesqen.ui.screens

import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #51: background playback on Honor kept the main thread at 30–50% CPU. A stopped activity's
 * window recomposer pauses `withFrameNanos`, so a Now transition cannot settle; a transition
 * target that changed under the same content key (a play count update) made AnimatedContent
 * rewrite its visible list on every recomposition, which scheduled the next one.
 *
 * This uses a real activity and its own window recomposer, because the Compose test rule
 * replaces both.
 */
class NowBackgroundRecompositionTest {
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
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
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
            }
            val recomposer = windowRecomposer(scenario)
            assertTrue("Now did not settle in the foreground", awaitQuiet(recomposer, timeoutMs = 5_000))
            // ON_STOP pauses the recomposer's frame clock, as when the app goes to the background.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { current = track.copy(playCount = 1, lastPlayedAtMs = 1_000) }
            // The update itself takes a few frames (about 0.1 s on the Honor). The loop kept work
            // pending about 90% of the time and never went quiet for long.
            assertTrue("Now keeps recomposing while stopped", awaitQuiet(recomposer, timeoutMs = 3_000))
        }
    }

    /** The window recomposer is created when the ComposeView attaches, after setContent returns. */
    private fun windowRecomposer(scenario: ActivityScenario<ComponentActivity>): Recomposer {
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < 5_000) {
            var found: Recomposer? = null
            scenario.onActivity {
                // Compose installs it on the content view's child, the ComposeView.
                found = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    ?.findViewTreeCompositionContext() as? Recomposer
            }
            found?.let { return it }
            SystemClock.sleep(10)
        }
        error("The window recomposer was not created")
    }

    /** True once the recomposer has had no pending work for [quietMs] in a row. */
    private fun awaitQuiet(recomposer: Recomposer, quietMs: Long = 500, timeoutMs: Long): Boolean {
        val started = SystemClock.uptimeMillis()
        var lastBusy = started
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (recomposer.hasPendingWork) lastBusy = now else if (now - lastBusy >= quietMs) return true
            if (now - started >= timeoutMs) return false
            SystemClock.sleep(5)
        }
    }
}
