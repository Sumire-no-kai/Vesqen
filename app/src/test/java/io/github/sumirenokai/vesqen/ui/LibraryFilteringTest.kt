package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.screens.filterTracks
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilteringTest {
    private val tracks = listOf(
        AudioTrack(1, "content://one", "Dawn Signal", "Mori", "Quiet Rooms", 1000),
        AudioTrack(2, "content://two", "Long Light", "Nari", "Elsewhere", 2000),
    )

    @Test
    fun `filter searches title artist and album without changing blank results`() {
        assertEquals(tracks, filterTracks(tracks, "  "))
        assertEquals(listOf(tracks[0]), filterTracks(tracks, "signal"))
        assertEquals(listOf(tracks[1]), filterTracks(tracks, "NARI"))
        assertEquals(listOf(tracks[0]), filterTracks(tracks, "quiet"))
    }
}
