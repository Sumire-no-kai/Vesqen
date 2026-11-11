package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.library.LibrarySourceScanResult
import io.github.sumirenokai.vesqen.library.LibrarySourceScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanResultStateTest {
    private val device = LibrarySource("device", LibrarySourceKind.DEVICE, "Device")
    private val folder = LibrarySource("folder", LibrarySourceKind.FOLDER, "Music", treeUri = "content://tree/music")
    private val results = listOf(result(device), result(folder))

    @Test
    fun `revoking one grant discards its old success without discarding the unchanged source`() {
        assertEquals(
            listOf(results.first()),
            scanResultsAfterSourcesRead(results, listOf(device, folder), listOf(device, folder.copy(isAvailable = false))),
        )
    }

    @Test
    fun `removed or replaced sources cannot retain a previous scan diagnosis`() {
        assertEquals(
            emptyList<LibrarySourceScanResult>(),
            scanResultsAfterSourcesRead(results, listOf(device, folder), listOf(folder.copy(treeUri = "content://tree/other"))),
        )
    }

    @Test
    fun `ordinary label and count refreshes retain observations for the same available source`() {
        assertEquals(
            results,
            scanResultsAfterSourcesRead(results, listOf(device, folder), listOf(device, folder.copy(displayName = "Renamed", trackCount = 42))),
        )
    }

    @Test
    fun `a pause request does not report that the provider has actually stopped`() {
        val progress = LibraryScanProgress("folder", "Music", 9, pauseRequested = true)
        assertFalse(LibraryUiState(scanProgress = progress).isScanPaused)
        assertTrue(LibraryUiState(scanProgress = progress.copy(isPaused = true, pauseRequested = false)).isScanPaused)
    }

    @Test
    fun `a new live report overrides an old persisted paused source`() {
        val paused = folder.copy(scanState = LibraryScanState.PAUSED)
        assertTrue(LibraryUiState(sources = listOf(paused)).isScanPaused)
        assertFalse(
            LibraryUiState(sources = listOf(paused), scanProgress = LibraryScanProgress("folder", "Music", 0)).isScanPaused,
        )
    }

    @Test
    fun `an in flight row retains the pause request until the cursor confirms it stopped`() {
        val pending = LibraryScanProgress("folder", "Music", 9, pauseRequested = true)
        val row = LibraryScanProgress("folder", "Music", 10)
        val reported = scanProgressAfterReport(pending, row)
        assertEquals(10, reported.scannedTrackCount)
        assertTrue(reported.pauseRequested)
        assertFalse(reported.isPaused)

        val stopped = scanProgressAfterReport(reported, row.copy(isPaused = true))
        assertTrue(stopped.isPaused)
        assertFalse(stopped.pauseRequested)
    }

    @Test
    fun `a resumed request is not restored by the next running report`() {
        val resumed = LibraryScanProgress("folder", "Music", 9, pauseRequested = false)
        assertFalse(scanProgressAfterReport(resumed, resumed.copy(scannedTrackCount = 10)).pauseRequested)
    }

    private fun result(source: LibrarySource) = LibrarySourceScanResult(
        source.id, source.displayName, LibrarySourceScanStatus.COMPLETED, scannedTrackCount = 5,
    )
}
