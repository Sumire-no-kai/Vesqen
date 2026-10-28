package io.github.sumirenokai.vesqen.library

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.PlaybackHistoryRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #165: the screen and service catalogs, listening history and the play count share one helper,
 * which also reconciles scans an earlier process left running once for the whole process.
 */
class SharedLibraryCatalogStoreTest {
    @Test
    fun writesThroughOneUserReachTheOthersAndClosingACatalogKeepsTheStoreOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-shared-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val historyJob = SupervisorJob()
        try {
            val trackId = store.addDeviceTrack()
            // The helper reopens a closed database on its next use, so check the open one itself.
            val database = store.writableDatabase
            val screenCatalog = AndroidLibraryCatalog(context, store)
            val serviceCatalog = AndroidLibraryCatalog(context, store)
            val history = PlaybackHistoryRecorder(store, CoroutineScope(historyJob))

            history.recordAndAwait(trackId)
            assertEquals(1, screenCatalog.track(trackId).playCount)
            assertEquals(1, serviceCatalog.track(trackId).playCount)
            assertEquals(1L, store.totalPlayCount())

            screenCatalog.setFavorite(trackId, true)
            assertTrue(serviceCatalog.track(trackId).isFavorite)

            // The ViewModel closes its catalog when cleared, the service when destroyed.
            screenCatalog.close()
            history.recordAndAwait(trackId)
            assertEquals(2, serviceCatalog.track(trackId).playCount)
            serviceCatalog.close()
            history.recordAndAwait(trackId)
            assertEquals(3L, store.totalPlayCount())
            assertTrue(database.isOpen)
        } finally {
            historyJob.cancel()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun aSecondCatalogLeavesAScanStartedInThisProcessRunning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-shared-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        try {
            // An earlier process stopped during a scan; the first catalog marks it interrupted.
            store.ensureDeviceSource()
            store.markSourceScanning(LibrarySourceId.DEVICE)
            val firstCatalog = AndroidLibraryCatalog(context, store)
            assertEquals(LibraryScanState.INTERRUPTED, firstCatalog.deviceScanState())

            // The first screen's catalog scans while a second screen opens its own catalog.
            val scan = store.beginSourceScan(LibrarySourceId.DEVICE)
            val secondCatalog = AndroidLibraryCatalog(context, store)
            assertEquals(LibraryScanState.SCANNING, secondCatalog.deviceScanState())
            assertEquals(LibraryScanState.SCANNING, firstCatalog.deviceScanState())

            store.completeSourceScan(scan)
            assertEquals(LibraryScanState.IDLE, secondCatalog.deviceScanState())
            firstCatalog.close()
            secondCatalog.close()
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun LibraryCatalogStore.addDeviceTrack(): Long {
        val session = beginSourceScan(ensureDeviceSource().id)
        upsertTrack(
            session,
            LibraryTrackCandidate(
                remoteId = "fixture-1",
                contentUri = "content://fixture/1",
                title = "Signal",
                artist = "Mori",
                album = "Quiet",
                durationMs = 60_000,
                fingerprint = "fixture",
            ),
        )
        completeSourceScan(session)
        return readTracks(listOf(LibrarySourceId.DEVICE)).single().id
    }

    private suspend fun AndroidLibraryCatalog.track(trackId: Long): AudioTrack =
        playableTracks(includeDeviceLibrary = true).single { it.id == trackId }

    private suspend fun AndroidLibraryCatalog.deviceScanState(): LibraryScanState =
        snapshot(includeDeviceLibrary = true).sources.single { it.id == LibrarySourceId.DEVICE }.scanState

    private suspend fun PlaybackHistoryRecorder.recordAndAwait(trackId: Long) = coroutineScope {
        val recorded = async(start = CoroutineStart.UNDISPATCHED) {
            recordedUpdates.first { it.trackId == trackId }
        }
        recordPlayback(trackId)
        withTimeout(5_000) { recorded.await() }
    }
}
