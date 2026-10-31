package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMergeRecoveryTest {
    @Test
    fun `only deleted sources with surviving targets replay`() {
        val rows = setOf(7L, 3L)
        assertEquals(mapOf(2L to 7L), recoverCommittedTrackMerges(mapOf(2L to 7L, 3L to 7L, 4L to 8L), rows::contains))
    }

    @Test
    fun `a chain replays to its surviving row`() {
        assertEquals(mapOf(2L to 9L, 7L to 9L), recoverCommittedTrackMerges(mapOf(2L to 7L, 7L to 9L), setOf(9L)::contains))
    }

    @Test
    fun `a rolled back second transaction does not undo an earlier committed merge`() {
        assertEquals(mapOf(2L to 7L), recoverCommittedTrackMerges(mapOf(2L to 7L, 7L to 9L), setOf(7L, 9L)::contains))
    }

    @Test
    fun `invalid cycles cannot remap a queue`() {
        assertEquals(emptyMap<Long, Long>(), recoverCommittedTrackMerges(mapOf(2L to 7L, 7L to 2L)) { false })
    }
}
