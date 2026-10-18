package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryTrackIdentityTest {
    @Test
    fun `a reload with the same tracks keeps the previous list`() {
        val previous = LibraryUiState(tracks = listOf(track(1), track(2)))
        val reloaded = previous.copy(tracks = listOf(track(1), track(2)), isLoading = false, loadingFailed = true)

        val published = reloaded.keepingEqualTracks(previous)

        assertSame(previous.tracks, published.tracks)
        assertEquals(true, published.loadingFailed)
    }

    @Test
    fun `a reload with changed tracks publishes the new list`() {
        val previous = LibraryUiState(tracks = listOf(track(1), track(2)))
        val favorite = listOf(track(1), track(2).copy(isFavorite = true))
        val shorter = listOf(track(1))

        assertSame(favorite, previous.copy(tracks = favorite).keepingEqualTracks(previous).tracks)
        assertSame(shorter, previous.copy(tracks = shorter).keepingEqualTracks(previous).tracks)
    }

    private fun track(id: Long) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 60_000,
    )
}
