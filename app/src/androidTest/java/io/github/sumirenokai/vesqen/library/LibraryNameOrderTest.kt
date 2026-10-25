package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** ICU exists only on a device, so the shared Library name order is checked here (#167). */
class LibraryNameOrderTest {
    @Test
    fun namesSortByReadingOrderInAnyScriptNotByCodePoint() {
        // By UTF-16 code point these were 周 王 陈, the reverse of pinyin; mixed case is one order too.
        val artists = listOf("周", "王", "陈", "beta", "Alpha").mapIndexed { index, name ->
            track(10L + index, title = "Song $index", artist = name, album = "Album $index")
        }

        val names = buildLibraryCollections(LibraryBrowseMode.ARTISTS, artists, emptyList()).map(LibraryCollection::title)

        assertEquals(listOf("Alpha", "beta", "陈", "王", "周"), names)
    }

    @Test
    fun collectionsInterleaveChineseAndLatinNamesLikeSongs() {
        val titles = listOf("盐与雾", "Copper Hours", "潮汐表", "1999", "Alpha", "北京", "Yellow", "# Intro")
        val albums = titles.mapIndexed { index, title ->
            track(20L + index, title = title, artist = "Artist", album = title)
        }

        val albumOrder = buildLibraryCollections(LibraryBrowseMode.ALBUMS, albums, emptyList()).map(LibraryCollection::title)
        val songOrder = LibraryTitleIndex.build(albums).tracks.map(AudioTrack::title)

        // 盐与雾 sits in Y with Yellow, not ahead of every Latin name; names without a letter come last.
        assertEquals(songOrder, albumOrder)
        assertEquals(listOf("Alpha", "北京"), albumOrder.take(2))
        assertEquals(setOf("Copper Hours", "潮汐表"), albumOrder.subList(2, 4).toSet())
        assertEquals(setOf("盐与雾", "Yellow"), albumOrder.subList(4, 6).toSet())
        assertEquals(setOf("1999", "# Intro"), albumOrder.takeLast(2).toSet())
        assertEquals(albumOrder, sortLibraryCollections(buildLibraryCollections(LibraryBrowseMode.ALBUMS, albums, emptyList()), LibrarySortOrder.TITLE).map(LibraryCollection::title))
    }

    @Test
    fun groupsFollowTheSongsIndex() {
        val names = LibraryNameOrder()

        assertEquals(listOf("A", "C", "Y", "#", "#"), listOf(" alpha", "潮汐表", "盐与雾", "1999", "").map(names::group))
    }

    private fun track(id: Long, title: String, artist: String, album: String) = AudioTrack(
        id = id,
        contentUri = "content://fixture/$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = 1_000,
        folderName = "Music/$artist/$album",
    )
}
