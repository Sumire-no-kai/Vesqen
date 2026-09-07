package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySnapshotReaderTest {
    @Test
    fun `slow old read cannot replace newer catalog or resynchronize its playback queue`() = runBlocking {
        val reader = LibrarySnapshotReader()
        val oldReadStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<LibraryCatalogSnapshot>()
        val published = mutableListOf<LibraryCatalogSnapshot>()
        val oldRead = launch {
            reader.read(
                load = {
                    oldReadStarted.complete(Unit)
                    oldResult.await()
                },
                publish = { published += it },
            )
        }
        oldReadStarted.await()
        val latest = snapshot(2)
        reader.read(load = { latest }, publish = { published += it })
        oldResult.complete(snapshot(1))
        oldRead.join()

        assertEquals(listOf(latest), published)
    }

    @Test
    fun `permission change invalidates pending read even before replacement read starts`() = runBlocking {
        val reader = LibrarySnapshotReader()
        val readStarted = CompletableDeferred<Unit>()
        val result = CompletableDeferred<LibraryCatalogSnapshot>()
        val published = mutableListOf<LibraryCatalogSnapshot>()
        val read = launch {
            reader.read(
                load = {
                    readStarted.complete(Unit)
                    result.await()
                },
                publish = { published += it },
            )
        }
        readStarted.await()
        reader.invalidate()
        result.complete(snapshot(1))
        read.join()
        assertTrue(published.isEmpty())

        val permitted = snapshot(2)
        reader.read(load = { permitted }, publish = { published += it })
        assertEquals(listOf(permitted), published)
    }

    private fun snapshot(id: Long) = LibraryCatalogSnapshot(
        tracks = listOf(AudioTrack(id, "content://track/$id", "Track", "Artist", "Album", 60_000)),
        sources = emptyList(),
    )
}
