package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryTitleProjectionTest {
    @Test fun `history and favorite changes reuse title keys but project fresh track objects`() {
        val original = listOf(track(1, "A"), track(2, "B"))
        val current = original.map { it.copy(isFavorite = true, playCount = 9, lastPlayedAtMs = 500) }
        assertEquals(libraryTitleKeys(original), libraryTitleKeys(current))
        assertEquals(current, projectLibraryTitleOrder(original, current.reversed()))
        assertNotEquals(libraryTitleKeys(original), libraryTitleKeys(listOf(original[0].copy(title = "C"), original[1])))
    }

    @Test fun `in flight title rebuild keeps surviving order drops removals and includes new tracks`() {
        val original = listOf(track(1, "A"), track(2, "B"), track(3, "C"))
        val renamed = original[2].copy(title = "0")
        val added = track(4, "D")
        assertEquals(listOf(original[0], renamed, added),
            projectLibraryTitleOrder(original, listOf(added, renamed, original[0])))
    }

    private fun track(id: Long, title: String) = AudioTrack(id, "content://fixture/$id", title, "", "", 1000)
}
