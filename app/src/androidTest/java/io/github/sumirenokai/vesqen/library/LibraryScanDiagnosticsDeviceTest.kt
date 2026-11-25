package io.github.sumirenokai.vesqen.library

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanDiagnosticsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeDocumentsProvider.authority(context.packageName), FakeDocumentsProvider.FOLDER,
    )

    @After fun resetProvider() = FakeDocumentsProvider.reset()

    @Test fun completedScanReportsOnlyObservedAudioAndRealStages() = runBlocking {
        withCatalog { store, catalog ->
            FakeDocumentsProvider.files["music/short.flac"] = ByteArray(0)
            FakeDocumentsProvider.files["music/album.dsf"] = ByteArray(0)
            FakeDocumentsProvider.files["music/album.wv"] = ByteArray(0)
            FakeDocumentsProvider.files["music/cover.jpg"] = ByteArray(0)
            FakeDocumentsProvider.files["music/short.lrc"] = ByteArray(0)
            FakeDocumentsProvider.files["music/album.cue"] = ByteArray(0)
            val progress = mutableListOf<LibraryScanProgress>()
            val result = catalog.refresh(false) { progress += it }
            val sourceResult = result.sourceResults.single()
            assertFalse(result.hadFailure)
            assertEquals(LibrarySourceScanStatus.COMPLETED, sourceResult.status)
            assertEquals(1, sourceResult.scannedTrackCount)
            assertEquals(mapOf(UnsupportedAudioFormat.DSD to 1, UnsupportedAudioFormat.WAVPACK to 1), sourceResult.skippedUnsupportedAudio)
            assertEquals(listOf(LibraryScanStage.READING, LibraryScanStage.RECONCILING), progress.map { it.stage })
            assertEquals(1, progress.last().scannedTrackCount)
            assertEquals(1, store.readTracks(listOf(LibrarySourceId.forTree(tree.toString()))).size)
        }
    }

    @Test fun incompleteProviderIsAFailedObservationAndNeverAnEmptyCompletedScan() = runBlocking {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            FakeDocumentsProvider.listing = FakeDocumentsProvider.Listing.LOADING
            val progress = mutableListOf<LibraryScanProgress>()
            val result = catalog.refresh(false) { progress += it }
            assertTrue(result.hadFailure)
            assertEquals(LibrarySourceScanStatus.FAILED, result.sourceResults.single().status)
            assertEquals(LibraryScanFailureReason.PROVIDER_NOT_READY, result.sourceResults.single().failureReason)
            assertEquals(LibraryScanStage.READING, progress.last().stage)
            assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
        }
    }

    @Test fun aPauseHasItsOwnResultAndDoesNotFinishOrPruneTheSource() = runBlocking {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            FakeDocumentsProvider.files["music/other.flac"] = ByteArray(0)
            val progress = mutableListOf<LibraryScanProgress>()
            val result = catalog.refresh(false) {
                progress += it
                if (!it.isPaused) catalog.pause()
            }
            assertFalse(result.hadFailure)
            assertEquals(LibrarySourceScanStatus.PAUSED, result.sourceResults.single().status)
            assertEquals(0, result.sourceResults.single().scannedTrackCount)
            assertTrue(progress.last().isPaused)
            assertEquals(LibraryScanStage.READING, progress.last().stage)
            assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
        }
    }

    @Test fun anUnavailableGrantReportsNoUnobservedFilesAndRetainsStoredTracks() = runBlocking {
        withCatalog { store, _ ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            val unavailable = AndroidLibraryCatalog(context, store, folderGrants = { emptyList() })
            try {
                val result = unavailable.refresh(false) {}
                assertEquals(LibrarySourceScanStatus.UNAVAILABLE, result.sourceResults.single().status)
                assertEquals(LibraryScanFailureReason.PERMISSION_REQUIRED, result.sourceResults.single().failureReason)
                assertEquals(0, result.sourceResults.single().scannedTrackCount)
                assertEquals(0, result.sourceResults.single().skippedUnsupportedAudioCount)
                assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
            } finally {
                unavailable.close()
            }
        }
    }

    @Test fun revokingTheGrantDuringReconciliationCannotPruneUnseenRows() = runBlocking {
        var granted = true
        withCatalog(grants = { if (granted) listOf(tree) else emptyList() }) { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            val result = catalog.refresh(false) { progress ->
                if (progress.stage == LibraryScanStage.RECONCILING) granted = false
            }
            assertEquals(LibrarySourceScanStatus.UNAVAILABLE, result.sourceResults.single().status)
            assertEquals(LibraryScanFailureReason.PERMISSION_REQUIRED, result.sourceResults.single().failureReason)
            assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
            assertEquals(LibraryScanState.INTERRUPTED, store.readSources().single().scanState)
        }
    }

    @Test fun pausingDuringReconciliationStopsBeforePruning() = runBlocking {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            val progress = mutableListOf<LibraryScanProgress>()
            val result = catalog.refresh(false) {
                progress += it
                if (it.stage == LibraryScanStage.RECONCILING && !it.isPaused) catalog.pause()
            }
            assertEquals(LibrarySourceScanStatus.PAUSED, result.sourceResults.single().status)
            assertTrue(progress.last().isPaused)
            assertEquals(LibraryScanStage.RECONCILING, progress.last().stage)
            assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
            assertEquals(LibraryScanState.PAUSED, store.readSources().single().scanState)
        }
    }

    @Test fun aThrowingCompletionObserverCannotRelabelACommittedScan() = runBlocking {
        verifyNoObserverAfterCommit(IllegalStateException("Observer failed after completion"))
    }

    @Test fun aCancellingCompletionObserverCannotRelabelACommittedScan() = runBlocking {
        verifyNoObserverAfterCommit(CancellationException("Observer cancelled after completion"))
    }

    private suspend fun verifyNoObserverAfterCommit(completionFailure: RuntimeException) {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            seed(store, sourceId)
            var callbacks = 0
            val result = catalog.refresh(false) {
                callbacks++
                // Before this regression was fixed, COMPLETE ran after the source became IDLE.
                if (store.readSources().single().scanState == LibraryScanState.IDLE) throw completionFailure
            }
            assertEquals(2, callbacks)
            assertEquals(LibrarySourceScanStatus.COMPLETED, result.sourceResults.single().status)
            assertFalse(result.hadFailure)
            assertEquals(LibraryScanState.IDLE, store.readSources().single().scanState)
            assertTrue(store.readTracks(listOf(sourceId)).isEmpty())
        }
    }

    @Test fun renewalKeepsExistingTrackIdentityAndUserState() = runBlocking {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            store.setFavorite(trackId, true)
            store.recordPlayback(trackId, 123L)
            val playlist = requireNotNull(store.createPlaylist("Mix"))
            store.addTrackToPlaylist(playlist, trackId)
            store.markSourceFailed(sourceId)
            assertEquals(FolderGrantRenewalResult.RENEWED, catalog.renewFolder(sourceId, tree))
            val track = store.readTracks(listOf(sourceId)).single()
            assertEquals(trackId, track.id)
            assertTrue(track.isFavorite)
            assertEquals(1, track.playCount)
            assertEquals(listOf(trackId), store.readPlaylists().single().trackIds)
            assertEquals(LibraryScanState.IDLE, store.readSources().single().scanState)
        }
    }

    @Test fun wrongFolderDoesNotTouchSourcesOrReadTheSelectedProvider() = runBlocking {
        withCatalog { store, catalog ->
            val sourceId = LibrarySourceId.forTree(tree.toString())
            val trackId = seed(store, sourceId)
            val before = store.readSources()
            val other = DocumentsContract.buildTreeDocumentUri(tree.authority, "other")
            assertEquals(FolderGrantRenewalResult.WRONG_FOLDER, catalog.renewFolder(sourceId, other))
            assertEquals(before, store.readSources())
            assertEquals(trackId, store.readTracks(listOf(sourceId)).single().id)
            assertEquals(0, FakeDocumentsProvider.childQueries)
        }
    }

    private suspend fun withCatalog(
        grants: () -> List<Uri> = { listOf(tree) },
        block: suspend (LibraryCatalogStore, AndroidLibraryCatalog) -> Unit,
    ) {
        val databaseName = "scan-diagnostics-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val catalog = AndroidLibraryCatalog(context, store, folderGrants = grants)
        try {
            store.upsertFolderSource(tree.toString(), "Music")
            block(store, catalog)
        } finally {
            catalog.close()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun seed(store: LibraryCatalogStore, sourceId: String): Long {
        val scan = store.beginSourceScan(sourceId)
        store.upsertTrack(scan, LibraryTrackCandidate(
            remoteId = "music/kept.flac", contentUri = "content://fixture/kept", title = "Kept", artist = "", album = "",
            durationMs = 10_000, fingerprint = "fixture",
        ))
        store.completeSourceScan(scan)
        return store.readTracks(listOf(sourceId)).single().id
    }
}
