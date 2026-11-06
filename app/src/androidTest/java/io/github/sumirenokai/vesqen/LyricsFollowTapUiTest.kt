package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.sumirenokai.vesqen.lyrics.LyricLine
import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.LyricsSource
import io.github.sumirenokai.vesqen.lyrics.TrackLyrics
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsPane
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The list takes a touch while it scrolls. Only a scroll of the listener's own may use it up (#261 V2). */
class LyricsFollowTapUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var covers = 0
    private var position by mutableLongStateOf(0L)
    private val lyrics = TrackLyrics.Found(
        LyricsSource.SameNameFile,
        Lyrics.Synced(List(40) { LyricLine(it * 5_000L, "Line number $it") }),
    )

    @Before
    fun show() {
        compose.setContent {
            VesqenTheme {
                NowLyricsPane(
                    lyrics = lyrics,
                    positionMs = position,
                    durationMs = 200_000,
                    isPlaying = false,
                    canSeek = true,
                    motionPolicy = VesqenMotionPolicy(reduceMotion = false),
                    onSeek = {},
                    onImportLyrics = null,
                    onShowCover = { covers++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private val pane get() = compose.onNodeWithTag("vesqen.now.lyrics")

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    private fun startFollowing() {
        compose.mainClock.autoAdvance = false
        position = 50_000L
        frames(4)
    }

    private fun tap() {
        pane.performTouchInput { down(center) }
        frames(3)
        pane.performTouchInput { up() }
        frames(3)
    }

    @Test
    fun aTapOnAStillListShowsTheCoverOnce() {
        compose.mainClock.autoAdvance = false
        tap()
        assertEquals(1, covers)
    }

    @Test
    fun aTapWhileFollowingShowsTheCoverOnce() {
        startFollowing()
        tap()
        assertEquals(1, covers)
    }

    @Test
    fun aSlideWhileFollowingOnlyScrolls() {
        startFollowing()
        pane.performTouchInput { down(center) }
        frames(2)
        repeat(10) { step ->
            pane.performTouchInput { moveTo(center - Offset(0f, 30f * (step + 1))) }
            frames(1)
        }
        pane.performTouchInput { up() }
        frames(3)
        assertEquals(0, covers)
    }

    @Test
    fun aTapDuringTheListenersFlingOnlyStopsIt() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.now.lyrics.lines").performTouchInput { swipeUp() }
        frames(4)
        tap()
        assertEquals(0, covers)
    }
}
