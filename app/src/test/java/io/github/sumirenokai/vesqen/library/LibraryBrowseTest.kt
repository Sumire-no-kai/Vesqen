package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryBrowseTest {
    private val tracks = listOf(
        track(1, "Second", "Mori", "Quiet", trackNumber = 2, genre = "Ambient", playCount = 4),
        track(2, "First", "Mori", "Quiet", trackNumber = 1, genre = "Ambient", playCount = 8),
        track(3, "Elsewhere", "Nari", "Open", trackNumber = 1, genre = "Jazz", playCount = 2),
    )

    @Test
    fun `album browsing keeps disc and track order`() {
        val quiet = buildLibraryCollections(LibraryBrowseMode.ALBUMS, tracks, emptyList())
            .first { it.title == "Quiet" }

        assertEquals(listOf(2L, 1L), quiet.tracks.map(AudioTrack::id))
    }

    @Test
    fun `playlist browsing preserves explicit playlist order`() {
        val playlist = LibraryPlaylist(7, "Night", listOf(3, 1), 1, 2)

        val collection = buildLibraryCollections(LibraryBrowseMode.PLAYLISTS, tracks, listOf(playlist)).single()

        assertEquals(listOf(3L, 1L), collection.tracks.map(AudioTrack::id))
    }

    @Test
    fun `most played sorting is deterministic`() {
        assertEquals(
            listOf(2L, 1L, 3L),
            sortLibraryTracks(tracks, LibrarySortOrder.MOST_PLAYED).map(AudioTrack::id),
        )
    }

    @Test
    fun `collection sorting uses aggregate listening history`() {
        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, tracks, emptyList())

        assertEquals(
            listOf("Quiet", "Open"),
            sortLibraryCollections(albums, LibrarySortOrder.MOST_PLAYED).map(LibraryCollection::title),
        )
    }

    @Test
    fun `album collection keys cannot collide through metadata separators`() {
        val ambiguous = listOf(
            tracks.first().copy(id = 10, albumArtist = "a::b", album = "c"),
            tracks.first().copy(id = 11, albumArtist = "a", album = "b::c"),
        )

        val collections = buildLibraryCollections(LibraryBrowseMode.ALBUMS, ambiguous, emptyList())

        assertEquals(2, collections.size)
        assertEquals(2, collections.map(LibraryCollection::key).distinct().size)
    }

    @Test
    fun `collection keys are namespaced by browse mode`() {
        val track = tracks.first().copy(artist = "Ambient", genre = "Ambient")

        val artistKey = buildLibraryCollections(LibraryBrowseMode.ARTISTS, listOf(track), emptyList()).single().key
        val genreKey = buildLibraryCollections(LibraryBrowseMode.GENRES, listOf(track), emptyList()).single().key

        assertNotEquals(artistKey, genreKey)
    }

    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        genre: String,
        playCount: Int,
    ) = AudioTrack(
        id = id,
        contentUri = "content://track/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = 1_000,
        trackNumber = trackNumber,
        genre = genre,
        folderName = "Music/$artist/$album",
        playCount = playCount,
    )
}
