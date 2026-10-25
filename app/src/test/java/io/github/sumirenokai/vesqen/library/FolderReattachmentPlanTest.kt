package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** #164: which listed folder row takes over an old row's user data. */
class FolderReattachmentPlanTest {
    private fun row(id: Long, name: String, size: Long, duration: Long) = FolderTrackIdentity(id, name, size, duration)

    @Test
    fun `a moved file is found by name and size, a renamed one by size and duration`() {
        val moved = row(1, "Aira.flac", 1_000, 60_000)
        val renamed = row(2, "02 Tide.flac", 2_000, 90_000)
        val plan = planFolderReattachments(
            missing = listOf(moved, renamed),
            listed = listOf(row(11, "Aira.flac", 1_000, 60_000), row(12, "Tide (remaster).flac", 2_000, 90_000)),
        )
        assertEquals(setOf(1L to 11L, 2L to 12L), plan.toSet())
    }

    @Test
    fun `an ambiguous key on either side pairs nothing`() {
        val old = row(1, "Aira.flac", 1_000, 60_000)
        // Two listed copies of the same file.
        assertEquals(emptyList<Pair<Long, Long>>(), planFolderReattachments(
            listOf(old), listOf(row(11, "x.flac", 1_000, 60_000), row(12, "y.flac", 1_000, 60_000)),
        ))
        // Two old rows for one listed file.
        assertEquals(emptyList<Pair<Long, Long>>(), planFolderReattachments(
            listOf(old, row(2, "Aira.flac", 1_000, 60_000)), listOf(row(11, "Aira.flac", 1_000, 60_000)),
        ))
    }

    @Test
    fun `a name match is taken first and leaves the rest for the rename pass`() {
        val plan = planFolderReattachments(
            missing = listOf(row(1, "a.flac", 1_000, 60_000), row(2, "b.flac", 1_000, 60_000)),
            listed = listOf(row(11, "a.flac", 1_000, 60_000), row(12, "c.flac", 1_000, 60_000)),
        )
        assertEquals(listOf(1L to 11L, 2L to 12L), plan)
    }

    @Test
    fun `unknown size or duration never pairs`() {
        assertEquals(emptyList<Pair<Long, Long>>(), planFolderReattachments(
            listOf(row(1, "a.flac", 0, 60_000), row(2, "", 1_000, 0)),
            listOf(row(11, "a.flac", 0, 60_000), row(12, "b.flac", 1_000, 0)),
        ))
    }
}
