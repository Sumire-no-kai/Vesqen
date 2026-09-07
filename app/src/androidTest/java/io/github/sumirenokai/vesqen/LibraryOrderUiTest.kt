package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.components.LibraryTrackList
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryOrderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun handleReordersTheExistingListWithoutPlaying() {
        val tracks = mutableStateOf((1L..100L).map { AudioTrack(it, "content://fixture/$it", "Track $it", "", "", 1000) })
        var played = false
        val editing = mutableStateOf(false)
        compose.setContent {
            VesqenTheme {
                LibraryTrackList(tracks.value, null, false, { played = true }, {},
                    editing = editing.value, onReorder = { tracks.value = it })
            }
        }
        compose.runOnIdle { editing.value = true }
        val first = compose.onNodeWithTag("vesqen.library.drag.1", useUnmergedTree = true)
        val third = compose.onNodeWithTag("vesqen.library.drag.3", useUnmergedTree = true)
        val list = compose.onNodeWithTag("vesqen.library.tracks")
        fun drag(start: Offset, end: Offset) {
            // Reordering runs on frame callbacks. Advance the frame clock with each move,
            // rather than batch a second gesture whose entire duration can precede a frame.
            compose.mainClock.autoAdvance = false
            try {
                list.performTouchInput { down(start) }
                compose.mainClock.advanceTimeByFrame()
                repeat(30) { step ->
                    list.performTouchInput { moveTo(start + (end - start) * ((step + 1) / 30f)) }
                    compose.mainClock.advanceTimeByFrame()
                }
                list.performTouchInput { up() }
                compose.mainClock.advanceTimeByFrame()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
        }
        val origin = list.fetchSemanticsNode().boundsInRoot.topLeft
        val start = first.fetchSemanticsNode().boundsInRoot.center - origin
        val end = third.fetchSemanticsNode().boundsInRoot.center - origin
        drag(start, end)
        compose.runOnIdle {
            assertEquals("Drag $start to $end; order=${tracks.value.take(10).map(AudioTrack::id)}",
                listOf(2L, 3L, 1L), tracks.value.take(3).map(AudioTrack::id))
            assertEquals(100, tracks.value.size)
            assertEquals(false, played)
        }
        val returnStart = first.fetchSemanticsNode().boundsInRoot.center - origin
        val returnEnd = compose.onNodeWithTag("vesqen.library.drag.2", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - origin
        drag(returnStart, returnEnd)
        compose.runOnIdle {
            assertEquals("Return $returnStart to $returnEnd", (1L..100L).toList(), tracks.value.map(AudioTrack::id))
            assertEquals(false, played)
        }
    }
}
