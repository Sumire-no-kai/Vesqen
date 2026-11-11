package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderPlaybackTest {
    private fun track(id: Long, folder: String, uri: String = "content://media/external/audio/media/$id") =
        AudioTrack(id, uri, "Track $id", "", "", 1_000, folderName = folder, fileName = "$id.flac")

    private fun folder(key: String, vararg tracks: AudioTrack) =
        LibraryCollection(key, key, "", tracks.toList())

    @Test
    fun `queue follows visible folder and track order from the selected occurrence`() {
        val folders = listOf(folder("B", track(3, "B"), track(1, "B")), folder("empty"), folder("A", track(4, "A"), track(2, "A")))
        val plan = checkNotNull(planFolderPlayback(folders, "A", 2))
        assertEquals(listOf(3L, 1L, 4L, 2L), plan.tracks.map(AudioTrack::id))
        assertEquals(3, plan.startIndex)
        assertEquals(listOf(2L), plan.tracks.drop(plan.startIndex).map(AudioTrack::id))
    }

    @Test
    fun `search scope is the supplied visible collections only`() {
        val plan = checkNotNull(planFolderPlayback(listOf(folder("one", track(2, "one"))), "one", 2))
        assertEquals(listOf(2L), plan.tracks.map(AudioTrack::id))
        assertEquals(0, plan.startIndex)
        assertNull(planFolderPlayback(emptyList(), "one", 2))
        assertNull(planFolderPlayback(listOf(folder("one", track(2, "one"))), "one", 8))
    }

    @Test
    fun `equal track ids in different groups do not change the chosen start`() {
        val groups = listOf(folder("one", track(1, "one")), folder("two", track(1, "two")))
        assertEquals(1, checkNotNull(planFolderPlayback(groups, "two", 1)).startIndex)
    }

    @Test
    fun `folder identity is identical to library grouping and distinguishes volumes`() {
        val phone = track(1, "Music/Album", "content://media/external_primary/audio/media/1")
        val card = track(2, "Music/Album", "content://media/1234-5678/audio/media/2")
        val saf = track(3, "Music/Album", "content://documents.one/tree/primary%3AMusic/document/one")
        val otherTree = track(4, "Music/Album", "content://documents.one/tree/card%3AMusic/document/two")
        val otherProvider = track(5, "Music/Album", "content://documents.two/tree/primary%3AMusic/document/one")
        assertNotEquals(phone.folderCollectionKey(), card.folderCollectionKey())
        val tracks = listOf(phone, card, saf, otherTree, otherProvider)
        val keys = tracks.map { it.folderCollectionKey() }.toSet()
        assertEquals(5, keys.size)
        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, tracks, emptyList(), String.CASE_INSENSITIVE_ORDER)
        assertEquals(keys, folders.map { it.key }.toSet())
    }
}
