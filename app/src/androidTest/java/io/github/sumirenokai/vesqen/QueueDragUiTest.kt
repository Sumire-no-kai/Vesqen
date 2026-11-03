package io.github.sumirenokai.vesqen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.components.QueueSheet
import io.github.sumirenokai.vesqen.ui.components.queueRowKeys
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The queue's long-press drag (#211). The service's own move logic has its tests in PlayOrderEdits. */
class QueueDragUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val played = mutableListOf<Int>()
    private val moved = mutableListOf<Pair<Int, Int>>()
    private val removed = mutableListOf<Int>()
    private var dismissed = false
    private var snapshot by mutableStateOf(PlaybackSnapshot())

    private fun item(track: Long, listIndex: Int, entry: String = "entry-$listIndex") =
        PlaybackQueueItem(track, "Track $track", "Artist", isCurrent = false, listIndex = listIndex, entryId = entry)

    private fun show(queue: List<PlaybackQueueItem>) {
        snapshot = PlaybackSnapshot(isControllerReady = true, queue = queue)
        compose.setContent {
            VesqenTheme {
                QueueSheet(
                    snapshot = snapshot,
                    onDismiss = { dismissed = true },
                    onPlayItem = { played += it },
                    onRemoveItem = { removed += it },
                    onMoveItem = { from, to -> moved += from to to },
                    onClearQueue = {},
                )
            }
        }
        compose.onNodeWithTag("vesqen.queue.sheet").assertIsDisplayed()
    }

    /** Presses a row until it lifts, drags it by [rows] row heights, and leaves the finger down. */
    private fun liftAndDrag(row: Int, rows: Float) {
        val node = compose.onNodeWithTag("vesqen.queue.item.$row").fetchSemanticsNode()
        val list = compose.onNodeWithTag("vesqen.queue.list").fetchSemanticsNode().boundsInRoot
        val start = Offset(node.boundsInRoot.center.x - node.boundsInRoot.width / 4, node.boundsInRoot.center.y) - list.topLeft
        val height = node.boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(800)
        repeat(30) { step ->
            compose.onNodeWithTag("vesqen.queue.list").performTouchInput {
                moveTo(start + Offset(0f, height * rows * (step + 1) / 30f))
            }
            compose.mainClock.advanceTimeByFrame()
        }
    }

    private fun drop() {
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun rowTitle(row: Int): String = compose.onNodeWithTag("vesqen.queue.item.$row").fetchSemanticsNode()
        .config[SemanticsProperties.Text].first { it.text.startsWith("Track") }.text

    @Test
    fun aLongPressDragMovesTheRowAndNothingElseHappens() {
        show(listOf(item(1, 0), item(2, 1), item(3, 2), item(4, 3)))

        liftAndDrag(row = 0, rows = 2f)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("The others make room while it is held", "Track 2", rowTitle(0))
        assertTrue("Nothing moves until the drop", moved.isEmpty())
        drop()

        compose.runOnIdle {
            assertEquals(listOf(0 to 2), moved)
            assertTrue(played.isEmpty())
            assertTrue(removed.isEmpty())
            assertFalse(dismissed)
        }
    }

    @Test
    fun aDropNamesThePlayersListIndicesInAShuffledQueue() {
        // Shown in play order 2, 0, 3, 1.
        show(listOf(item(1, 2), item(2, 0), item(3, 3), item(4, 1)))

        liftAndDrag(row = 3, rows = -2f)
        drop()

        compose.runOnIdle { assertEquals(listOf(1 to 0), moved) }
    }

    @Test
    fun theSameTrackTwiceStaysTwoEntries() {
        show(listOf(item(1, 0), item(2, 1), item(1, 2)))

        liftAndDrag(row = 0, rows = 2f)
        drop()

        compose.runOnIdle { assertEquals(listOf(0 to 2), moved) }
    }

    @Test
    fun rowsWithoutUsableEntryIdsStillHaveUniqueKeys() {
        val keys = queueRowKeys(listOf(item(1, 0, entry = "same"), item(1, 1, entry = "same"), item(2, 2, entry = "")))
        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun aDragNearTheBottomEdgeScrollsAndReachesARowNotShownBefore() {
        show((0 until 60).map { item(it.toLong(), it) })

        liftAndDrag(row = 0, rows = 30f)
        // Held at the edge, the list keeps scrolling under the finger.
        repeat(120) { compose.mainClock.advanceTimeByFrame() }
        drop()

        compose.runOnIdle {
            val (from, to) = moved.single()
            assertEquals(0, from)
            assertTrue("It should pass the rows first in view, got $to", to > 15)
        }
    }

    @Test
    fun aQueueChangeDuringADragEndsItWithoutMoving() {
        show(listOf(item(1, 0), item(2, 1), item(3, 2)))

        liftAndDrag(row = 0, rows = 2f)
        snapshot = snapshot.copy(queue = listOf(item(2, 0, "entry-1"), item(3, 1, "entry-2")))
        compose.mainClock.advanceTimeBy(100)
        drop()

        compose.runOnIdle { assertTrue(moved.isEmpty()) }
        assertEquals("Track 2", rowTitle(0))
    }

    @Test
    fun aChangeBehindPositionKeysStillEndsADragAndAPreview() {
        // The same track twice without occurrence ids: the rows fall back to position keys.
        fun positions(vararg tracks: Long) = tracks.mapIndexed { index, track -> item(track, index, entry = "same-$track") }
        show(positions(1, 1, 2))

        liftAndDrag(row = 0, rows = 2f)
        snapshot = snapshot.copy(queue = positions(3, 3, 4))
        compose.mainClock.advanceTimeBy(100)
        drop()

        compose.runOnIdle { assertTrue("The drag belonged to the old queue", moved.isEmpty()) }
        assertEquals("Track 3", rowTitle(0))

        // A drop that the queue confirms shows the queue's own order at once.
        liftAndDrag(row = 0, rows = 2f)
        drop()
        compose.runOnIdle { assertEquals(listOf(0 to 2), moved) }
        snapshot = snapshot.copy(queue = positions(3, 4, 3))
        compose.waitForIdle()
        assertEquals("Track 3", rowTitle(0))
        assertEquals("Track 4", rowTitle(1))
        assertEquals("Track 3", rowTitle(2))
    }

    @Test
    fun aHoldWhileAMoveWaitsForTheQueueMovesNothingElse() {
        show(listOf(item(1, 0), item(2, 1), item(3, 2), item(4, 3)))

        // The queue never answers here, so the dropped order stays up until the timeout.
        liftAndDrag(row = 1, rows = 2f)
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Track 3", rowTitle(1))

        val node = compose.onNodeWithTag("vesqen.queue.item.1").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("vesqen.queue.list").fetchSemanticsNode().boundsInRoot
        val hold = Offset(node.center.x - node.width / 4, node.center.y) - list.topLeft
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { down(hold) }
        compose.mainClock.advanceTimeBy(600)
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("The held row stays where it was shown", "Track 3", rowTitle(1))
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { up() }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(listOf(1 to 3), moved)

        // Once the wait is over, the queue's own order is back and a row can be lifted again.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("Track 2", rowTitle(1))
        liftAndDrag(row = 0, rows = 1f)
        drop()
        compose.runOnIdle { assertEquals(listOf(1 to 3, 0 to 1), moved) }
    }

    @Test
    fun aRowOffersNoMovesWhileAMoveWaitsForTheQueue() {
        show(listOf(item(1, 0), item(2, 1), item(3, 2), item(4, 3)))
        fun moves(row: Int) = compose.onNodeWithTag("vesqen.queue.item.$row").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(2, moves(1).size)

        liftAndDrag(row = 1, rows = 2f)
        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { up() }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("Track 3", rowTitle(1))
        assertEquals(emptyList<Any>(), moves(1))

        // The queue's own order is back, and each move names the row beside it again.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("Track 2", rowTitle(1))
        val moveDown = moves(1).also { assertEquals(2, it.size) }[1]
        compose.runOnIdle { moveDown.action() }
        assertEquals(listOf(1 to 3, 1 to 2), moved)
    }

    @Test
    fun aDropOnTheSamePlaceMovesNothing() {
        show(listOf(item(1, 0), item(2, 1), item(3, 2)))

        liftAndDrag(row = 1, rows = 0f)
        drop()

        compose.runOnIdle {
            assertTrue(moved.isEmpty())
            assertTrue("Releasing a held row is not a tap", played.isEmpty())
        }
    }

    @Test
    fun aTapPlaysAndASwipeOnlyScrolls() {
        show((0 until 60).map { item(it.toLong(), it) })

        compose.onNodeWithTag("vesqen.queue.item.2").performClick()
        compose.runOnIdle { assertEquals(listOf(2), played) }

        compose.onNodeWithTag("vesqen.queue.list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(moved.isEmpty())
            assertEquals(listOf(2), played)
        }
    }

    @Test
    fun theRemoveButtonKeepsItsPressAndDoesNotLift() {
        show(listOf(item(1, 0), item(2, 1)))

        compose.onNodeWithTag("vesqen.queue.list").performTouchInput {
            down(Offset(width - 8f, height / 4f))
            advanceEventTime(800)
            moveBy(Offset(0f, height / 2f))
            up()
        }
        compose.runOnIdle { assertTrue(moved.isEmpty()) }
    }
}
