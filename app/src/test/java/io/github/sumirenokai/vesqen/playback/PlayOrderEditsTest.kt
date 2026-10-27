package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** #158: where queue edits land in a shuffled play order (list indices in the order they play). */
class PlayOrderEditsTest {
    private val shuffled = intArrayOf(2, 0, 3, 1)

    @Test
    fun `play next plays right after the current item`() {
        // The current item is list index 1, so the new item goes into the list at 2.
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2), shuffled.withInsertedAt(listIndex = 2, anchor = 1))
        // When the current item plays first, the new one plays second.
        assertArrayEquals(intArrayOf(2, 3, 0, 4, 1), shuffled.withInsertedAt(listIndex = 3, anchor = 2))
    }

    @Test
    fun `add to queue plays after every other item`() {
        assertArrayEquals(intArrayOf(2, 0, 3, 1, 4), shuffled.withInsertedAt(listIndex = 4, anchor = null))
        assertArrayEquals(intArrayOf(0), intArrayOf().withInsertedAt(listIndex = 0, anchor = null))
    }

    @Test
    fun `a missing anchor puts the new item last`() {
        assertArrayEquals(intArrayOf(2, 0, 3, 1, 4), shuffled.withInsertedAt(listIndex = 4, anchor = 9))
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
}
