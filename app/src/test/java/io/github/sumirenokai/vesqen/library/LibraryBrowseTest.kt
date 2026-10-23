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
    fun `names sort by reading order in any script, not by code point`() {
        // By UTF-16 code point these were 周 王 陈, the reverse of pinyin; mixed case is one order too.
        val artists = listOf("周", "王", "陈", "beta", "Alpha").mapIndexed { index, name ->
            track(10L + index, "Song $index", name, "Album $index", trackNumber = 1, genre = "Pop", playCount = 0)
        }
        val names = buildLibraryCollections(LibraryBrowseMode.ARTISTS, artists, emptyList()).map(LibraryCollection::title)

        assertEquals(listOf("Alpha", "beta", "陈", "王", "周"), names)
    }

    @Test
    fun `artist sort orders artists by name, not by their album count`() {
        val many = (1..10).map { album ->
            track(100L + album, "Song $album", "Zed", "Album $album", trackNumber = 1, genre = "Pop", playCount = 0)
        }
        val few = listOf(
            track(200, "One", "Amy", "First", trackNumber = 1, genre = "Pop", playCount = 0),
            track(201, "Two", "Amy", "Second", trackNumber = 1, genre = "Pop", playCount = 0),
        )
        val artists = buildLibraryCollections(LibraryBrowseMode.ARTISTS, many + few, emptyList())

        // Their subtitles are album counts, "10" and "2"; compared as text "10" came first.
        assertEquals(listOf("Amy", "Zed"), sortLibraryCollections(artists, LibrarySortOrder.ARTIST).map(LibraryCollection::title))
        // Albums still sort by their artist.
        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, many + few, emptyList())
        assertEquals("Amy", sortLibraryCollections(albums, LibrarySortOrder.ARTIST).first().subtitle)
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

    @Test
    fun `a folder is titled by its own name with the path above it as the subtitle`() {
        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, tracks, emptyList())

        assertEquals(listOf("Open", "Quiet"), folders.map(LibraryCollection::title))
        assertEquals(listOf("Music/Nari", "Music/Mori"), folders.map(LibraryCollection::subtitle))
    }

    @Test
    fun `two folders with the same name stay apart`() {
        val copies = listOf(
            tracks[0].copy(folderName = "Music/A/CD1/"),
            tracks[2].copy(folderName = "Music/B/CD1/"),
        )

        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, copies, emptyList())

        assertEquals(listOf("CD1", "CD1"), folders.map(LibraryCollection::title))
        assertEquals(setOf("Music/A", "Music/B"), folders.map(LibraryCollection::subtitle).toSet())
    }

    @Test
    fun `folder paths split at the last slash`() {
        assertEquals("Music/Demo" to "Copper Hours", folderPathParts("Music/Demo/Copper Hours/"))
        // Before Android 10 the folder name has no path in front of it.
        assertEquals("" to "Copper Hours", folderPathParts("Copper Hours"))
        assertEquals("" to "", folderPathParts(""))
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
