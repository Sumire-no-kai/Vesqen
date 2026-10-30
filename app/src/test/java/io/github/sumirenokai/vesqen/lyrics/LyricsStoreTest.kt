package io.github.sumirenokai.vesqen.lyrics

import io.github.sumirenokai.vesqen.library.CatalogTrackIds
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files

class LyricsStoreTest {
    private val directory = Files.createTempDirectory("lyrics-store").toFile()
    private val store = LyricsStore(directory)

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun anImportIsStoredAsUtf8AndOutlivesTheProcess() {
        val gbk = SAMPLE.toByteArray(Charset.forName("GBK"))

        assertEquals(LyricsImportResult.IMPORTED, store.import(7, ByteArrayInputStream(gbk), store.generation()))
        assertEquals(setOf(7L), store.importedLyrics.value.trackIds)

        val reopened = LyricsStore(directory)
        reopened.load()
        assertEquals(setOf(7L), reopened.importedLyrics.value.trackIds)
        assertArrayEquals(SAMPLE.toByteArray(Charsets.UTF_8), reopened.read(7))
        assertNull(reopened.read(8))
    }

    @Test
    fun aSecondImportReplacesTheFirst() {
        store.import(7, "[00:01.00]first".byteInput(), store.generation())
        store.import(7, "[00:01.00]second".byteInput(), store.generation())

        assertEquals("[00:01.00]second", store.read(7)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun filesThatCannotBeShownAreRefusedAndNothingIsStored() {
        val korean = "[00:01.00]바람이 부는 저녁 거리\n[00:05.00]우리는 강을 따라 천천히 걸었다\n[00:09.00]내일 다시 만날 수 있을까"

        assertEquals(LyricsImportResult.UNREADABLE, store.import(1, ByteArrayInputStream(korean.toByteArray(Charset.forName("EUC-KR"))), 0))
        assertEquals(LyricsImportResult.NO_LYRICS, store.import(2, "[ti:Only a title]".byteInput(), 0))
        assertEquals(LyricsImportResult.TOO_LARGE, store.import(3, ByteArrayInputStream(ByteArray(MAX_LYRICS_TEXT_BYTES + 1) { 'a'.code.toByte() }), 0))
        assertEquals(LyricsImportResult.FAILED, store.import(4, FailingInputStream(), 0))
        assertTrue(store.importedLyrics.value.trackIds.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun removeDeletesTheImportAndRaisesTheRevision() {
        store.import(7, SAMPLE.byteInput(), 0)
        val revision = store.importedLyrics.value.revision

        store.remove(7)

        assertNull(store.read(7))
        assertTrue(store.importedLyrics.value.trackIds.isEmpty())
        assertTrue(store.importedLyrics.value.revision > revision)
    }

    @Test
    fun anImportThatSpansARebuildCommitsNothing() {
        store.import(1, SAMPLE.byteInput(), 0)
        val generationAtPicker = store.generation()

        store.clearAll()

        assertNull(store.read(1))
        assertEquals(LyricsImportResult.TRACK_CHANGED, store.import(2, SAMPLE.byteInput(), generationAtPicker))
        assertNull(store.read(2))
        assertEquals(LyricsImportResult.IMPORTED, store.import(2, SAMPLE.byteInput(), store.generation()))
    }

    @Test
    fun aMergeKeepsTheSurvivorsLyricsAndMovesTheOtherOnlyWhenItHasNone() {
        store.import(10, "[00:01.00]merged away".byteInput(), 0)

        store.prepareMerge(from = 10, into = 20)
        // Inside the transaction both rows hold the lyrics, so a crash before the clean-up loses nothing.
        assertEquals("[00:01.00]merged away", store.read(20)!!.toString(Charsets.UTF_8))
        assertEquals("[00:01.00]merged away", store.read(10)!!.toString(Charsets.UTF_8))
        store.mergeCommitted(from = 10, into = 20)
        assertNull(store.read(10))
        assertEquals("[00:01.00]merged away", store.read(20)!!.toString(Charsets.UTF_8))

        store.import(30, "[00:01.00]other".byteInput(), 0)
        store.prepareMerge(from = 30, into = 20)
        store.mergeCommitted(from = 30, into = 20)
        assertEquals("[00:01.00]merged away", store.read(20)!!.toString(Charsets.UTF_8))
        assertNull(store.read(30))
        assertEquals(setOf(20L), store.importedLyrics.value.trackIds)
    }

    @Test
    fun aRolledBackMergeUndoesItsCopy() {
        store.import(10, SAMPLE.byteInput(), 0)
        store.prepareMerge(from = 10, into = 20)

        store.mergeRolledBack(from = 10, into = 20)

        assertNull(store.read(20))
        assertEquals(setOf(10L), store.importedLyrics.value.trackIds)
    }

    @Test
    fun aMergeOnAFullDiskRenamesTheFileInsideTheTransaction() {
        val full = LyricsStore(directory, duplicate = { _, _ -> throw IOException("No space left on device") })
        full.import(10, SAMPLE.byteInput(), 0)

        full.prepareMerge(from = 10, into = 20)
        // Already with the survivor before the commit, so a death or a clean-up after it loses nothing.
        assertNull(full.read(10))
        assertArrayEquals(SAMPLE.toByteArray(Charsets.UTF_8), full.read(20))
        full.sweep(CatalogTrackIds(trackIds = setOf(20L), largestAssignedId = 20), full.generation())
        full.mergeCommitted(from = 10, into = 20)
        assertArrayEquals(SAMPLE.toByteArray(Charsets.UTF_8), full.read(20))
        assertEquals(setOf(20L), full.importedLyrics.value.trackIds)

        // A rollback renames it back.
        full.prepareMerge(from = 20, into = 30)
        full.mergeRolledBack(from = 20, into = 30)
        assertEquals(setOf(20L), full.importedLyrics.value.trackIds)
        assertArrayEquals(SAMPLE.toByteArray(Charsets.UTF_8), full.read(20))
    }

    @Test
    fun storedFilesLeaveNoTemporaryFileBehind() {
        store.import(7, SAMPLE.byteInput(), 0)
        store.prepareMerge(from = 7, into = 8)
        store.mergeCommitted(from = 7, into = 8)

        assertEquals(listOf("8.lrc"), directory.list()!!.sorted())
    }

    @Test
    fun theSweepRemovesDeletedRowsButNeverNewerOnes() {
        listOf(1L, 2L, 3L, 9L).forEach { store.import(it, SAMPLE.byteInput(), 0) }

        store.sweep(CatalogTrackIds(trackIds = setOf(2L), largestAssignedId = 5), store.generation())

        assertEquals(setOf(2L, 9L), store.importedLyrics.value.trackIds)
        assertNull(store.read(1))
        assertNull(store.read(3))
    }

    @Test
    fun aSweepReadBeforeARebuildDeletesNothing() {
        val generation = store.generation()
        store.clearAll()
        store.import(1, SAMPLE.byteInput(), store.generation())

        store.sweep(CatalogTrackIds(trackIds = emptySet(), largestAssignedId = 100), generation)

        assertEquals(setOf(1L), store.importedLyrics.value.trackIds)
    }

    private fun String.byteInput() = ByteArrayInputStream(toByteArray(Charsets.UTF_8))

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw IOException("unreadable")
    }

    private companion object {
        // Sample lines written for these tests.
        const val SAMPLE = "[00:01.00]晚风吹过安静的街道\n[00:05.00]我们沿着河岸慢慢走\n[00:09.00]灯光落在水面上"
    }
}
