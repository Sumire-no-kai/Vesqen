package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Grouping and order rules. The ICU name order itself is checked on a device (LibraryNameOrderTest). */
class LibraryBrowseTest {
    private val names: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    private val tracks = listOf(
        track(1, "Second", "Mori", "Quiet", trackNumber = 2, genre = "Ambient", playCount = 4),
        track(2, "First", "Mori", "Quiet", trackNumber = 1, genre = "Ambient", playCount = 8),
        track(3, "Elsewhere", "Nari", "Open", trackNumber = 1, genre = "Jazz", playCount = 2),
    )

    @Test
    fun `album browsing keeps disc and track order`() {
        val quiet = buildLibraryCollections(LibraryBrowseMode.ALBUMS, tracks, emptyList(), names)
            .first { it.title == "Quiet" }

        assertEquals(listOf(2L, 1L), quiet.tracks.map(AudioTrack::id))
    }

    @Test
    fun `playlist browsing preserves explicit playlist order`() {
        val playlist = LibraryPlaylist(7, "Night", listOf(3, 1), 1, 2)

        val collection = buildLibraryCollections(LibraryBrowseMode.PLAYLISTS, tracks, listOf(playlist), names).single()

        assertEquals(listOf(3L, 1L), collection.tracks.map(AudioTrack::id))
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
        val artists = buildLibraryCollections(LibraryBrowseMode.ARTISTS, many + few, emptyList(), names)

        // Their subtitles are album counts, "10" and "2"; compared as text "10" came first.
        assertEquals(listOf("Amy", "Zed"), sortLibraryCollections(artists, LibrarySortOrder.ARTIST, names).map(LibraryCollection::title))
        // Albums still sort by their artist.
        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, many + few, emptyList(), names)
        assertEquals("Amy", sortLibraryCollections(albums, LibrarySortOrder.ARTIST, names).first().subtitle)
    }

    @Test
    fun `most played sorting is deterministic`() {
        assertEquals(
            listOf(2L, 1L, 3L),
            sortLibraryTracks(tracks, LibrarySortOrder.MOST_PLAYED, names).map(AudioTrack::id),
        )
    }

    @Test
    fun `collection sorting uses aggregate listening history`() {
        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, tracks, emptyList(), names)

        assertEquals(
            listOf("Quiet", "Open"),
            sortLibraryCollections(albums, LibrarySortOrder.MOST_PLAYED, names).map(LibraryCollection::title),
        )
    }

    @Test
    fun `same-named albums by different artists stay apart without an album artist`() {
        val hits = listOf(
            track(20, "One", "Artist A", "Greatest Hits", trackNumber = 1, genre = "Pop", playCount = 0),
            track(21, "Two", "Artist B", "Greatest Hits", trackNumber = 1, genre = "Pop", playCount = 0),
        )

        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, hits, emptyList(), names)

        assertEquals(listOf(listOf(20L), listOf(21L)), albums.map { album -> album.tracks.map(AudioTrack::id) }.sortedBy { it.first() })
        assertEquals(setOf("Artist A", "Artist B"), albums.map(LibraryCollection::subtitle).toSet())
    }

    @Test
    fun `a compilation without an album artist stays one album`() {
        val compilation = listOf("Artist A", "Artist B", "Artist C").mapIndexed { index, artist ->
            track(30L + index, "Song $index", artist, "Summer Mix", trackNumber = index + 1, genre = "Pop", playCount = 0)
                .copy(folderName = "Music/Compilations/Summer Mix")
        }

        val album = buildLibraryCollections(LibraryBrowseMode.ALBUMS, compilation, emptyList(), names).single()

        assertEquals(listOf(30L, 31L, 32L), album.tracks.map(AudioTrack::id))
    }

    @Test
    fun `disc folders of one album without an album artist stay one album`() {
        val discs = listOf(
            track(40, "Second disc", "Mori", "Long Night", trackNumber = 1, genre = "Pop", playCount = 0)
                .copy(discNumber = 2, folderName = "Music/Mori/Long Night/Disc 2"),
            track(41, "First disc", "Mori", "Long Night", trackNumber = 1, genre = "Pop", playCount = 0)
                .copy(discNumber = 1, folderName = "Music/Mori/Long Night/CD1/"),
            track(42, "Loose copy", "Mori", "Long Night", trackNumber = 1, genre = "Pop", playCount = 0)
                .copy(folderName = "Music/Elsewhere/Long Night"),
        )

        val albums = buildLibraryCollections(LibraryBrowseMode.ALBUMS, discs, emptyList(), names)

        assertEquals(2, albums.size)
        assertEquals(listOf(41L, 40L), albums.single { it.tracks.size == 2 }.tracks.map(AudioTrack::id))
    }

    @Test
    fun `disc folders beside each other stay one album without an album artist`() {
        val discs = listOf("Music/Pink Floyd/The Wall (Disc 1)", "Music/Pink Floyd/The Wall (Disc 2)").mapIndexed { index, folder ->
            track(50L + index, "Side $index", "Pink Floyd", "The Wall", trackNumber = 1, genre = "Rock", playCount = 0)
                .copy(discNumber = index + 1, folderName = folder)
        }

        val album = buildLibraryCollections(LibraryBrowseMode.ALBUMS, discs, emptyList(), names).single()

        assertEquals(listOf(50L, 51L), album.tracks.map(AudioTrack::id))
        assertEquals("Music/A/Long Night", albumFolder("Music/A/Long Night CD2"))
        assertEquals("Music/A/Long Night", albumFolder("Music/A/Long Night [Disc 1]"))
        assertEquals("Music/A/Long Night", albumFolder("Music/A/Long Night - CD 1"))
        assertEquals("Music/A/Long Night", albumFolder("Music/A/Long Night/CD1/"))
        // A name that only ends in letters and a digit is not a disc.
        assertEquals("Music/A/ABCD1", albumFolder("Music/A/ABCD1"))
    }

    @Test
    fun `same-named albums stay apart in the songs list and the artist count`() {
        val hits = listOf(
            track(60, "One", "Mori", "Greatest Hits", trackNumber = 1, genre = "Pop", playCount = 0).copy(folderName = "Music/A/Greatest Hits"),
            track(61, "One", "Mori", "Greatest Hits", trackNumber = 1, genre = "Pop", playCount = 0).copy(folderName = "Music/B/Greatest Hits"),
            track(62, "Two", "Mori", "Greatest Hits", trackNumber = 2, genre = "Pop", playCount = 0).copy(folderName = "Music/A/Greatest Hits"),
        )

        // Sorted by album, one album's tracks follow each other instead of interleaving.
        val order = sortLibraryTracks(hits, LibrarySortOrder.ALBUM, names).map(AudioTrack::id)
        assertEquals(listOf(60L, 62L), order.filter { it != 61L })
        assertEquals(1, kotlin.math.abs(order.indexOf(60L) - order.indexOf(62L)))
        assertEquals("2", buildLibraryCollections(LibraryBrowseMode.ARTISTS, hits, emptyList(), names).single().subtitle)
    }

    @Test
    fun `files in a folder keep the plain order with digits first`() {
        val files = listOf("Bonus Track.flac", "02 Song.flac", "01 Intro.flac").mapIndexed { index, file ->
            tracks[0].copy(id = 70L + index, fileName = file, folderName = "Music/Album")
        }

        val folder = buildLibraryCollections(LibraryBrowseMode.FOLDERS, files, emptyList(), names).single()

        assertEquals(listOf("01 Intro.flac", "02 Song.flac", "Bonus Track.flac"), folder.tracks.map(AudioTrack::fileName))
    }

    @Test
    fun `an album artist keeps one album across folders`() {
        val split = listOf(
            tracks[0].copy(albumArtist = "Mori", folderName = "Music/One"),
            tracks[1].copy(albumArtist = "Mori", folderName = "Music/Two"),
        )

        assertEquals(1, buildLibraryCollections(LibraryBrowseMode.ALBUMS, split, emptyList(), names).size)
    }

    @Test
    fun `album collection keys cannot collide through metadata separators`() {
        val ambiguous = listOf(
            tracks.first().copy(id = 10, albumArtist = "a::b", album = "c"),
            tracks.first().copy(id = 11, albumArtist = "a", album = "b::c"),
        )

        val collections = buildLibraryCollections(LibraryBrowseMode.ALBUMS, ambiguous, emptyList(), names)

        assertEquals(2, collections.size)
        assertEquals(2, collections.map(LibraryCollection::key).distinct().size)
    }

    @Test
    fun `collection keys are namespaced by browse mode`() {
        val track = tracks.first().copy(artist = "Ambient", genre = "Ambient")

        val artistKey = buildLibraryCollections(LibraryBrowseMode.ARTISTS, listOf(track), emptyList(), names).single().key
        val genreKey = buildLibraryCollections(LibraryBrowseMode.GENRES, listOf(track), emptyList(), names).single().key

        assertNotEquals(artistKey, genreKey)
    }

    @Test
    fun `a folder is titled by its own name with the path above it as the subtitle`() {
        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, tracks, emptyList(), names)

        assertEquals(listOf("Open", "Quiet"), folders.map(LibraryCollection::title))
        assertEquals(listOf("Music/Nari", "Music/Mori"), folders.map(LibraryCollection::subtitle))
    }

    @Test
    fun `two folders with the same name stay apart`() {
        val copies = listOf(
            tracks[0].copy(folderName = "Music/A/CD1/"),
            tracks[2].copy(folderName = "Music/B/CD1/"),
        )

        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, copies, emptyList(), names)

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
