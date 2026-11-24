package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceId
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScanProgressAfterReadTest {
    private val scanning = LibraryScanProgress(
        sourceId = LibrarySourceId.forScan(LibrarySourceId.DEVICE),
        sourceName = "Device music",
        scannedTrackCount = 120,
    )

    @Test
    fun `a read while a scan runs keeps the scan's progress`() {
        // #136 U1: the device source reads as scanning, not paused, and the scan reports again only
        // when the source ends.
        val snapshot = snapshotWith(LibraryScanState.SCANNING)

        assertSame(scanning, scanProgressAfterRead(scanning, snapshot, scanRunning = true))
    }

    @Test
    fun `without a scan a read keeps only a paused scan`() {
        assertNull(scanProgressAfterRead(scanning, snapshotWith(LibraryScanState.SCANNING), scanRunning = false))
        val paused = scanning.copy(isPaused = true)
        assertSame(paused, scanProgressAfterRead(paused, snapshotWith(LibraryScanState.SCANNING), scanRunning = false))
        assertEquals(
            paused.copy(scannedTrackCount = 0),
            scanProgressAfterRead(null, snapshotWith(LibraryScanState.PAUSED), scanRunning = false),
        )
    }

    @Test
    fun `restored paused progress omits the folder tree URI`() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPrivateCollection%2FLive"
        val folder = LibrarySource(
            id = LibrarySourceId.forTree(treeUri),
            kind = LibrarySourceKind.FOLDER,
            displayName = "Live",
            treeUri = treeUri,
            scanState = LibraryScanState.PAUSED,
        )
        val snapshot = LibraryCatalogSnapshot(tracks = emptyList(), sources = listOf(folder))
        val progress = checkNotNull(scanProgressAfterRead(null, snapshot, scanRunning = false))

        assertFalse(treeUri in progress.toString())
        assertFalse("PrivateCollection" in progress.toString())
    }

    private fun snapshotWith(state: LibraryScanState) = LibraryCatalogSnapshot(
        tracks = emptyList(),
        sources = listOf(
            LibrarySource(
                id = LibrarySourceId.DEVICE,
                kind = LibrarySourceKind.DEVICE,
                displayName = "Device music",
                scanState = state,
            ),
        ),
    )
}
