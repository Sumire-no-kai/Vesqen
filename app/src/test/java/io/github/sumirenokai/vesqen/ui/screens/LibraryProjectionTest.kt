package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.library.ArtistNames
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryBrowseMode
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibrarySortOrder
import io.github.sumirenokai.vesqen.library.LibraryTitleIndex
import io.github.sumirenokai.vesqen.library.libraryTitleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists the Library shows, and which of them a change works out again (#223). */
class LibraryProjectionTest {
    private val tracks = listOf(
        track(1, "Cedar", "Mori", "Quiet", playCount = 1, favoritePosition = 2),
        track(2, "Alder", "Nari", "Elsewhere", playCount = 5, favoritePosition = 1),
        track(3, "Birch", "Mori", "Quiet", playCount = 3),
    )
    private val input = LibraryProjectionInput(
        tracks = tracks,
        playlists = emptyList(),
        query = "",
        browseMode = LibraryBrowseMode.SONGS,
        rootSortOrder = LibrarySortOrder.TITLE,
        favoriteSortOrder = LibrarySortOrder.TITLE,
        favoriteCustomOrder = true,
        title = null,
        artistNames = ArtistNames(ArtistSplitSettings()),
    )
    private val names: () -> Comparator<String> = { String.CASE_INSENSITIVE_ORDER }

    private fun project(input: LibraryProjectionInput, previous: LibraryProjection? = null) =
        projectLibrary(input, previous, names)

    private fun titled(tracks: List<AudioTrack>) = LibraryTitleProjection(
        libraryTitleKeys(tracks),
        LibraryTitleIndex(tracks.sortedBy(AudioTrack::title), mapOf("A" to 0, "B" to 1, "C" to 2)),
    )

    @Test fun `a blank query keeps the library in its own order until a title order exists`() {
        val untitled = project(input)
        assertSame(tracks, untitled.searchedTracks)
        assertEquals(listOf(1L, 2L, 3L), untitled.rootVisibleTracks.map(AudioTrack::id))
        assertEquals(emptyMap<String, Int>(), untitled.alphabetSections)

        val withTitle = project(input.copy(title = titled(tracks)))
        assertEquals(listOf(2L, 3L, 1L), withTitle.rootVisibleTracks.map(AudioTrack::id))
        assertEquals(mapOf("A" to 0, "B" to 1, "C" to 2), withTitle.alphabetSections)
    }

    @Test fun `sort orders search and favorites give the same lists as before`() {
        val sorted = project(input.copy(rootSortOrder = LibrarySortOrder.MOST_PLAYED))
        assertEquals(listOf(2L, 3L, 1L), sorted.rootVisibleTracks.map(AudioTrack::id))

        val searched = project(input.copy(query = "mori"))
        assertEquals(listOf(1L, 3L), searched.searchedTracks.map(AudioTrack::id))
        assertEquals(listOf(1L), searched.favoriteTracks.map(AudioTrack::id))

        val customOrder = project(input)
        assertEquals(listOf(2L, 1L), customOrder.favoriteVisibleTracks.map(AudioTrack::id))
        val favoriteByPlays = project(
            input.copy(favoriteCustomOrder = false, favoriteSortOrder = LibrarySortOrder.MOST_PLAYED),
        )
        assertEquals(listOf(2L, 1L), favoriteByPlays.favoriteVisibleTracks.map(AudioTrack::id))
        val favoriteByTitle = project(input.copy(favoriteCustomOrder = false, title = titled(tracks)))
        assertEquals(listOf(2L, 1L), favoriteByTitle.favoriteVisibleTracks.map(AudioTrack::id))
    }

    @Test fun `songs have no collections and other modes group what the search found`() {
        assertEquals(emptyList<Any>(), project(input).collections)

        val artists = project(input.copy(browseMode = LibraryBrowseMode.ARTISTS, query = "quiet"))
        assertEquals(listOf("Mori"), artists.collections.map { it.title })

        val playlist = LibraryPlaylist(id = 9, name = "Road", trackIds = listOf(2L), createdAtMs = 0, updatedAtMs = 0)
        val playlists = project(input.copy(browseMode = LibraryBrowseMode.PLAYLISTS, playlists = listOf(playlist)))
        assertEquals(listOf(9L), playlists.collections.map { it.playlistId })
    }

    @Test fun `the album shelf shows only for a blank query with more than one album`() {
        val albums = project(input.copy(browseMode = LibraryBrowseMode.ALBUMS))
        assertEquals(2, albums.albumShelf.size)
        assertEquals(emptyList<Any>(), project(albums.input.copy(query = "quiet"), albums).albumShelf)
        assertEquals(2, project(albums.input.copy(query = " "), albums).albumShelf.size)
        assertEquals(emptyList<Any>(), project(input.copy(browseMode = LibraryBrowseMode.ARTISTS)).albumShelf)
    }

    @Test fun `letters appear only for the whole title list of the tracks they were built from`() {
        val title = titled(tracks)
        assertTrue(project(input.copy(title = title)).alphabetSections.isNotEmpty())
        assertTrue(project(input.copy(title = title, query = "mori")).alphabetSections.isEmpty())
        assertTrue(
            project(input.copy(title = title, rootSortOrder = LibrarySortOrder.ARTIST)).alphabetSections.isEmpty(),
        )
        val added = tracks + track(4, "Dogwood", "Mori", "Quiet", playCount = 0)
        assertTrue(project(input.copy(tracks = added, title = title)).alphabetSections.isEmpty())
    }

    @Test fun `a play count change keeps the letters but shows the new tracks`() {
        val title = titled(tracks)
        val before = project(input.copy(title = title))
        val played = tracks.map { if (it.id == 3L) it.copy(playCount = 9) else it }
        val after = project(before.input.copy(tracks = played), before)
        assertTrue(after.alphabetSections.isNotEmpty())
        assertEquals(9, after.rootVisibleTracks.first { it.id == 3L }.playCount)
    }

    @Test fun `a new mode costs only its grouping and a new sort only its order`() {
        val songs = project(input.copy(rootSortOrder = LibrarySortOrder.ARTIST))

        val albums = project(songs.input.copy(browseMode = LibraryBrowseMode.ALBUMS), songs)
        assertSame(songs.searchedTracks, albums.searchedTracks)
        assertSame(songs.favoriteTracks, albums.favoriteTracks)
        assertSame(songs.rootVisibleTracks, albums.rootVisibleTracks)
        assertSame(songs.favoriteVisibleTracks, albums.favoriteVisibleTracks)

        val resorted = project(albums.input.copy(rootSortOrder = LibrarySortOrder.MOST_PLAYED), albums)
        assertSame(albums.searchedTracks, resorted.searchedTracks)
        assertSame(albums.favoriteVisibleTracks, resorted.favoriteVisibleTracks)
        assertNotSame(albums.rootVisibleTracks, resorted.rootVisibleTracks)
        assertNotSame(albums.collections, resorted.collections)

        val favoritesResorted = project(resorted.input.copy(favoriteCustomOrder = false), resorted)
        assertSame(resorted.rootVisibleTracks, favoritesResorted.rootVisibleTracks)
        assertSame(resorted.collections, favoritesResorted.collections)
        assertSame(resorted.albumShelf, favoritesResorted.albumShelf)
    }

    @Test fun `new tracks a new query or a new title order are worked out again`() {
        val first = project(input.copy(rootSortOrder = LibrarySortOrder.ARTIST, browseMode = LibraryBrowseMode.ARTISTS))

        val played = project(first.input.copy(tracks = tracks.map { it.copy(playCount = 7) }), first)
        assertEquals(7, played.rootVisibleTracks.first().playCount)
        assertEquals(7, played.collections.first().tracks.first().playCount)

        val searched = project(first.input.copy(query = "nari"), first)
        assertEquals(listOf("Nari"), searched.collections.map { it.title })

        val title = titled(tracks)
        val titleOrder = project(input, null)
        val reordered = project(titleOrder.input.copy(title = title), titleOrder)
        assertEquals(listOf(2L, 3L, 1L), reordered.rootVisibleTracks.map(AudioTrack::id))

        val split = project(
            first.input.copy(artistNames = ArtistNames(ArtistSplitSettings(enabled = false))),
            first,
        )
        assertNotSame(first.collections, split.collections)
    }

    private fun track(id: Long, title: String, artist: String, album: String, playCount: Int, favoritePosition: Long? = null) =
        AudioTrack(
            id = id,
            contentUri = "content://track/$id",
            title = title,
            artist = artist,
            album = album,
            durationMs = 1_000,
            playCount = playCount,
            isFavorite = favoritePosition != null,
            favoritePosition = favoritePosition,
        )
}
