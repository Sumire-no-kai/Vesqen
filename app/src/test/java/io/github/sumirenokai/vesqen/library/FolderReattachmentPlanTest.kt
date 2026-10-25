package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** #164: which listed folder row takes over an old row's user data. */
class FolderReattachmentPlanTest {
    /** An old row last seen by scan 1, holding user data unless [data] is false. */
    private fun old(id: Long, name: String, size: Long, modified: Long, data: Boolean = true) =
        FolderTrackIdentity(id, name, size, modified, holdsUserState = data, seenEpoch = 1, writtenEpoch = 1)

    /** A listed row written by scan [written], holding no user data unless [data] is true. */
    private fun listed(id: Long, name: String, size: Long, modified: Long, written: Long = 2, data: Boolean = false) =
        FolderTrackIdentity(id, name, size, modified, holdsUserState = data, seenEpoch = 2, writtenEpoch = written)

    private val none = emptyList<Pair<Long, Long>>()

    @Test
    fun `a moved file is found by name, size and time, a renamed one by size and time`() {
        val plan = planFolderReattachments(
            missing = listOf(old(1, "Aira.flac", 1_000, 100), old(2, "02 Tide.flac", 2_000, 200)),
            listed = listOf(listed(11, "Aira.flac", 1_000, 100), listed(12, "Tide (remaster).flac", 2_000, 200)),
        )
        assertEquals(setOf(1L to 11L, 2L to 12L), plan.toSet())
    }

    @Test
    fun `an uncompressed twin with another modified time takes nothing`() {
        // Two CD rips with the same sector count share size and duration, not their time.
        assertEquals(none, planFolderReattachments(listOf(old(1, "07.wav", 30_000_044, 100)), listOf(listed(11, "05.wav", 30_000_044, 175))))
    }

    @Test
    fun `a file that sat beside the old row all along keeps its own row`() {
        // An identical copy written in scan 1, when the old row was still listed.
        assertEquals(none, planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100)), listOf(listed(11, "Aira.flac", 1_000, 100, written = 1))))
    }

    @Test
    fun `an ambiguous key on either side pairs nothing`() {
        val aira = old(1, "Aira.flac", 1_000, 100)
        // Two listed copies.
        assertEquals(none, planFolderReattachments(listOf(aira), listOf(listed(11, "x.flac", 1_000, 100), listed(12, "y.flac", 1_000, 100))))
        // A listed copy with user data of its own still counts.
        assertEquals(none, planFolderReattachments(listOf(aira), listOf(listed(11, "x.flac", 1_000, 100), listed(12, "y.flac", 1_000, 100, data = true))))
        // Two old rows for one listed file, one of them without user data.
        assertEquals(none, planFolderReattachments(listOf(aira, old(2, "Aira.flac", 1_000, 100, data = false)), listOf(listed(11, "Aira.flac", 1_000, 100))))
    }

    @Test
    fun `a key the first pass shared stays ambiguous in the second`() {
        val plan = planFolderReattachments(
            missing = listOf(old(1, "a.flac", 1_000, 100), old(2, "b.flac", 1_000, 100)),
            listed = listOf(listed(11, "a.flac", 1_000, 100), listed(12, "c.flac", 1_000, 100)),
        )
        // a.flac pairs by name; b.flac and c.flac share (size, time) with the pair above, so they don't.
        assertEquals(listOf(1L to 11L), plan)
    }

    @Test
    fun `unknown size or time never pairs, and a listed row with user data takes nothing`() {
        assertEquals(none, planFolderReattachments(
            listOf(old(1, "a.flac", 0, 100), old(2, "b.flac", 1_000, 0)),
            listOf(listed(11, "a.flac", 0, 100), listed(12, "b.flac", 1_000, 0)),
        ))
        assertEquals(none, planFolderReattachments(listOf(old(1, "a.flac", 1_000, 100)), listOf(listed(11, "a.flac", 1_000, 100, data = true))))
    }
}
