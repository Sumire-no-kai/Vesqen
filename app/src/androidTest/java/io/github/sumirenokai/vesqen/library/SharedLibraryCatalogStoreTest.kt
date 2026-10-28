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

/** #165: the screen and service catalogs, listening history and the play count share one helper. */
class SharedLibraryCatalogStoreTest {
    @Test
    fun writesThroughOneUserReachTheOthersAndClosingACatalogKeepsTheStoreOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-shared-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val historyJob = SupervisorJob()
        try {
            val trackId = store.addDeviceTrack()
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
        } finally {
            historyJob.cancel()
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

    private suspend fun PlaybackHistoryRecorder.recordAndAwait(trackId: Long) = coroutineScope {
        val recorded = async(start = CoroutineStart.UNDISPATCHED) {
            recordedUpdates.first { it.trackId == trackId }
        }
        recordPlayback(trackId)
        withTimeout(5_000) { recorded.await() }
    }
}
