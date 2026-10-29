package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryBrowseMode
import io.github.sumirenokai.vesqen.library.LibraryCollection
import io.github.sumirenokai.vesqen.library.buildLibraryCollections
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

    @Test
    fun `filter also searches rich local metadata`() {
        val rich = tracks[0].copy(
            albumArtist = "The Collective",
            genre = "Ambient",
            folderName = "Music/Night",
            fileName = "signal.flac",
            codec = "FLAC",
        )
        val source = listOf(rich, tracks[1])

        listOf("collective", "ambient", "night", "signal.flac", "flac").forEach { query ->
            assertEquals(listOf(rich), filterTracks(source, query))
        }
    }

    @Test
    fun `search finds one artist of a shared tag and the Artists page lists that artist`() {
        val shared = AudioTrack(3, "content://three", "Theme", "塞壬唱片-MSR/Adam Gubman", "Arknights OST", 3000)
        val source = tracks + shared

        val found = filterTracks(source, "adam gubman")

        assertEquals(listOf(shared), found)
        val artists = buildLibraryCollections(LibraryBrowseMode.ARTISTS, found, emptyList(), String.CASE_INSENSITIVE_ORDER)
        assertEquals(listOf("Adam Gubman", "塞壬唱片-MSR"), artists.map(LibraryCollection::title))
    }
}
