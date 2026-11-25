package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderNavigationTest {
    private fun queue(vararg keys: String?, current: Int): List<PlaybackQueueItem> =
        keys.mapIndexed { i, key -> PlaybackQueueItem(i.toLong(), "", "", i == current, i, "entry-$i", key) }

    @Test
    fun `next and previous folder select group starts rather than neighboring songs`() {
        val rows = queue("A", "A", "B", "B", "C", "C", current = 3)
        assertEquals(FolderNavigation(0, 4), folderNavigation(rows, PlaybackRepeatMode.OFF))
        assertEquals(FolderNavigation(0, 4), folderNavigation(rows, PlaybackRepeatMode.ONE))
    }

    @Test
    fun `only list repeat wraps folder boundaries`() {
        assertEquals(FolderNavigation(null, 2), folderNavigation(queue("A", "A", "B", current = 1), PlaybackRepeatMode.OFF))
        assertEquals(FolderNavigation(2, 2), folderNavigation(queue("A", "A", "B", current = 0), PlaybackRepeatMode.ALL))
        assertEquals(FolderNavigation(0, null), folderNavigation(queue("A", "A", "B", current = 2), PlaybackRepeatMode.ONE))
        assertEquals(FolderNavigation(0, 0), folderNavigation(queue("A", "A", "B", current = 2), PlaybackRepeatMode.ALL))
    }

    @Test
    fun `a single identifiable folder never jumps to itself`() {
        assertEquals(FolderNavigation(), folderNavigation(queue("A", "A", current = 0), PlaybackRepeatMode.ALL))
        assertEquals(FolderNavigation(), folderNavigation(queue("A", null, "A", current = 0), PlaybackRepeatMode.ALL))
        assertEquals(FolderNavigation(), folderNavigation(queue(null, "B", current = 0), PlaybackRepeatMode.ALL))
        assertEquals(FolderNavigation(), folderNavigation(emptyList(), PlaybackRepeatMode.OFF))
    }

    @Test
    fun `shuffle uses actual play order and returns a player list index`() {
        val shuffled = listOf(
            PlaybackQueueItem(2, "", "", false, 2, folderKey = "B"),
            PlaybackQueueItem(0, "", "", true, 0, folderKey = "A"),
            PlaybackQueueItem(3, "", "", false, 3, folderKey = "C"),
            PlaybackQueueItem(1, "", "", false, 1, folderKey = "A"),
        )
        assertEquals(FolderNavigation(2, 3), folderNavigation(shuffled, PlaybackRepeatMode.OFF))
    }

    @Test
    fun `reordered duplicated entries and deleted groups are resolved from current rows`() {
        val rows = queue("A", "B", "C", "B", current = 2).map { it.copy(trackId = 1) }
        assertEquals(FolderNavigation(1, 3), folderNavigation(rows, PlaybackRepeatMode.OFF))
        assertEquals(FolderNavigation(0, 3), folderNavigation(rows.filterNot { it.listIndex == 1 }, PlaybackRepeatMode.OFF))
    }
}
