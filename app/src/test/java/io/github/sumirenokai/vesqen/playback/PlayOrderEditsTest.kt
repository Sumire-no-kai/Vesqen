package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** #158, #170 F9: where queue edits land in a shuffled play order (list indices in the order they play). */
class PlayOrderEditsTest {
    private val shuffled = intArrayOf(2, 0, 3, 1)

    @Test
    fun `inserted items play right after their list predecessor`() {
        // Play next: the current item is list index 1, so the new item goes into the list at 2.
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2), shuffled.withInserted(listIndex = 2, count = 1, last = false))
        // When the current item plays first, the new one plays second.
        assertArrayEquals(intArrayOf(2, 3, 0, 4, 1), shuffled.withInserted(listIndex = 3, count = 1, last = false))
        // After the last list item too, which is not the last to play.
        assertArrayEquals(intArrayOf(2, 0, 3, 4, 1), shuffled.withInserted(listIndex = 4, count = 1, last = false))
        // Several items keep their list order; without a list predecessor they play first.
        assertArrayEquals(intArrayOf(0, 1, 4, 2, 5, 3), shuffled.withInserted(listIndex = 0, count = 2, last = false))
    }

    @Test
    fun `add to queue plays after every other item`() {
        assertArrayEquals(intArrayOf(2, 0, 3, 1, 4), shuffled.withInserted(listIndex = 4, count = 1, last = true))
        assertArrayEquals(intArrayOf(0), intArrayOf().withInserted(listIndex = 0, count = 1, last = true))
    }

    @Test
    fun `moves change only the play order`() {
        // Move down: 0 takes the place of 3, the row after it.
        assertArrayEquals(intArrayOf(2, 3, 0, 1), shuffled.withMoved(item = 0, anchor = 3, after = true))
        // Move up: 3 takes the place of 0, the row before it.
        assertArrayEquals(intArrayOf(2, 3, 0, 1), shuffled.withMoved(item = 3, anchor = 0, after = false))
        // To the ends.
        assertArrayEquals(intArrayOf(1, 2, 0, 3), shuffled.withMoved(item = 1, anchor = 2, after = false))
        assertArrayEquals(intArrayOf(0, 3, 1, 2), shuffled.withMoved(item = 2, anchor = 1, after = true))
    }

    @Test
    fun `a repeated move leaves the item where the first one put it`() {
        // A second tap sent before the queue sheet showed the first move.
        val once = shuffled.withMoved(item = 0, anchor = 3, after = true)
        assertArrayEquals(once, once.withMoved(item = 0, anchor = 3, after = true))
    }

    @Test
    fun `a new queue plays its start item first and every item once`() {
        repeat(20) { seed ->
            val order = PlayOrder(IntArray(0), random = Random(seed)).cloneAndSet(insertionCount = 6, startIndex = 4).playOrder()
            assertEquals(4, order.first())
            assertEquals((0 until 6).toList(), order.sorted())
        }
        // Without a start index Media3 starts at the first item in the play order.
        assertEquals((0 until 6).toList(), PlayOrder(IntArray(0)).cloneAndSet(6, C.INDEX_UNSET).playOrder().sorted())
    }

    @Test
    fun `the order walks both ways and ends with unset`() {
        val order: ShuffleOrder = PlayOrder(shuffled)
        assertEquals(2, order.firstIndex)
        assertEquals(1, order.lastIndex)
        assertEquals(3, order.getNextIndex(0))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(1))
        assertEquals(2, order.getPreviousIndex(0))
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(2))
        assertEquals(C.INDEX_UNSET, PlayOrder(IntArray(0)).firstIndex)
        assertEquals(0, order.cloneAndClear().length)
    }

    @Test
    fun `play next is one insert after the current item`() {
        // ExoPlayer inserts Play next's item at the current list index + 1; the order already places it.
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2), PlayOrder(shuffled).cloneAndInsert(2, 1).playOrder())
        assertArrayEquals(intArrayOf(2, 0, 3, 4, 1), PlayOrder(shuffled).cloneAndInsert(4, 1).playOrder())
    }

    @Test
    fun `only the next insert plays last`() {
        val appended = PlayOrder(shuffled, nextInsertPlaysLast = true).cloneAndInsert(4, 1)
        assertArrayEquals(intArrayOf(2, 0, 3, 1, 4), appended.playOrder())
        // The flag is spent: a later insert plays after its list predecessor again.
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2, 5), appended.cloneAndInsert(2, 1).playOrder())
    }

    @Test
    fun `removed items leave the others in their order`() {
        assertArrayEquals(intArrayOf(1, 0, 2), PlayOrder(shuffled).cloneAndRemove(1, 2).playOrder())
        assertArrayEquals(intArrayOf(0, 1), PlayOrder(shuffled).cloneAndRemove(0, 2).playOrder())
    }

    @Test
    fun `an item a library refresh re-creates keeps its place`() {
        // ExoPlayer replaces list item 3 by inserting the new item at 4 and removing 3.
        assertArrayEquals(shuffled, PlayOrder(shuffled).cloneAndInsert(4, 1).cloneAndRemove(3, 4).playOrder())
        assertArrayEquals(shuffled, PlayOrder(shuffled).cloneAndInsert(1, 1).cloneAndRemove(0, 1).playOrder())
    }
}
