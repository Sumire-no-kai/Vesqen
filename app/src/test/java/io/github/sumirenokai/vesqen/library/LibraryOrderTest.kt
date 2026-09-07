package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    @Test fun partialOrderPreservesUnselectedSlotsAndIgnoresRemovedMembers() {
        assertEquals(listOf(3L, 2L, 1L, 4L),
            mergeLibraryOrder(listOf(1L, 2L, 3L, 4L), listOf(3L, 9L, 1L)))
    }

    @Test fun emptyOrderKeepsAllMembers() {
        assertEquals(listOf(1L, 2L), mergeLibraryOrder(listOf(1L, 2L), emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRequestedMembersAreRejected() {
        mergeLibraryOrder(listOf(1L, 2L), listOf(1L, 1L))
    }
}
