package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** #164: which listed folder row takes over an old row's user data. */
class FolderReattachmentPlanTest {
    /** An old row last seen by scan 1, holding user data unless [data] is false. */
    private fun old(id: Long, name: String, size: Long, modified: Long, data: Boolean = true, hidden: Boolean = false) =
        FolderTrackIdentity(id, name, size, modified, holdsUserState = data, seenEpoch = 1, writtenEpoch = 1, hidden = hidden)

    /**
     * A row listed by the completing scan 3: inserted by it unless [inserted] is false, else last
     * written by scan [written]. It holds no user data unless [data] is true.
     */
    private fun listed(
        id: Long,
        name: String,
        size: Long,
        modified: Long,
        inserted: Boolean = true,
        written: Long = if (inserted) SCAN else 1,
        data: Boolean = false,
    ) = FolderTrackIdentity(
        id, name, size, modified, holdsUserState = data, seenEpoch = SCAN, writtenEpoch = written, insertedThisScan = inserted,
    )

    private fun planFolderReattachments(missing: List<FolderTrackIdentity>, listed: List<FolderTrackIdentity>) =
        planFolderReattachments(missing, listed, scanEpoch = SCAN)

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
        assertEquals(none, planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100)), listOf(listed(11, "Aira.flac", 1_000, 100, inserted = false))))
        // The same copy rewritten in place by this scan, for new tags or a new metadata revision.
        assertEquals(none, planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100)), listOf(listed(11, "Aira.flac", 1_000, 100, inserted = false, written = SCAN))))
    }

    @Test
    fun `a file a paused scan inserted pairs, unless a completed scan hid the old row since`() {
        val moved = listed(11, "Aira.flac", 1_000, 100, inserted = false, written = 2)
        assertEquals(listOf(1L to 11L), planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100)), listOf(moved)))
        // A hidden row's completed scan already saw what scan 2 wrote; only a new file takes it over.
        assertEquals(none, planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100, hidden = true)), listOf(moved)))
        assertEquals(
            listOf(1L to 11L),
            planFolderReattachments(listOf(old(1, "Aira.flac", 1_000, 100, hidden = true)), listOf(listed(11, "Aira.flac", 1_000, 100))),
        )
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
    fun `unknown size or time never pairs`() {
        assertEquals(none, planFolderReattachments(
            listOf(old(1, "a.flac", 0, 100), old(2, "b.flac", 1_000, 0)),
            listOf(listed(11, "a.flac", 0, 100), listed(12, "b.flac", 1_000, 0)),
        ))
    }

    @Test
    fun `a new row that played before the scan completed still pairs`() {
        // Its data is merged into the old row, which keeps its catalog ID.
        assertEquals(listOf(1L to 11L), planFolderReattachments(listOf(old(1, "a.flac", 1_000, 100)), listOf(listed(11, "a.flac", 1_000, 100, data = true))))
    }

    private companion object {
        const val SCAN = 3L
    }
}
