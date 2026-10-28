package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScanProgressAfterReadTest {
    private val scanning = LibraryScanProgress(sourceId = "media:external", sourceName = "Device music", scannedTrackCount = 120)

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
            LibraryScanProgress(sourceId = "media:external", sourceName = "Device music", scannedTrackCount = 0, isPaused = true),
            scanProgressAfterRead(null, snapshotWith(LibraryScanState.PAUSED), scanRunning = false),
        )
    }

    private fun snapshotWith(state: LibraryScanState) = LibraryCatalogSnapshot(
        tracks = emptyList(),
        sources = listOf(
            LibrarySource(
                id = "media:external",
                kind = LibrarySourceKind.DEVICE,
                displayName = "Device music",
                scanState = state,
            ),
        ),
    )
}
