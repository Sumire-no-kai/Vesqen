package io.github.sumirenokai.vesqen.library

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #136 R12: a running scan leaves favorites, playlist edits and reads to go ahead. #191 D17: folder
 * removal and the #139 choices still need the scan lock, so a running scan stops for them.
 */
class LibraryCatalogScanDeviceTest {
    @Test
    fun favoritesPlaylistEditsAndReadsDoNotWaitForARunningScan() = runBlocking {
        // Without the music permission, Android 10 and later list only this app's own audio, so the
        // device scan runs on any emulator; Android 9 refuses the query instead.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-scan-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val catalog = AndroidLibraryCatalog(context, store)
        try {
            val trackId = store.addFixtureTrack()
            val scanRunning = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            // The scan reports its start, then waits there with the catalog's scan under way.
            val scan = async(Dispatchers.IO) {
                catalog.refresh(includeDeviceLibrary = true) {
                    if (scanRunning.complete(Unit)) finishScan.await()
                }
            }
            withTimeout(10_000) { scanRunning.await() }

            withTimeout(5_000) {
                catalog.setFavorite(trackId, true)
                val playlistId = requireNotNull(catalog.createPlaylist("Mix"))
                catalog.addTrackToPlaylist(playlistId, trackId)
                val snapshot = catalog.snapshot(includeDeviceLibrary = true)
                assertTrue(snapshot.tracks.single { it.id == trackId }.isFavorite)
                assertEquals(listOf(trackId), snapshot.playlists.single().trackIds)
                assertEquals(LibraryScanState.SCANNING, snapshot.sources.single { it.id == LibrarySourceId.DEVICE }.scanState)
            }

            finishScan.complete(Unit)
            val result = withTimeout(30_000) { scan.await() }
            val track = result.snapshot.tracks.single { it.id == trackId }
            assertTrue(track.isFavorite)
            assertEquals(listOf(trackId), result.snapshot.playlists.single().trackIds)
        } finally {
            catalog.close()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun aRemovalOrAChoiceStopsARunningScanAtItsNextRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-yield-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val catalog = AndroidLibraryCatalog(context, store)
        try {
            store.addFixtureTrack()
            val scanRunning = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val scan = async(Dispatchers.IO) {
                catalog.refresh(includeDeviceLibrary = true) {
                    if (scanRunning.complete(Unit)) finishScan.await()
                }
            }
            withTimeout(10_000) { scanRunning.await() }

            // The discard waited for the whole scan. Started undispatched, it asks for the scan lock
            // before the scan goes on to its rows.
            val discard = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { catalog.discardUnmatchedRecord(1) }
            finishScan.complete(Unit)
            val result = withTimeout(30_000) { scan.await() }
            assertTrue("The scan stops for the waiting edit", result.yieldedToEdit)
            assertFalse(result.hadFailure)
            assertEquals(LibraryScanState.INTERRUPTED, store.readSources().single { it.id == LibrarySourceId.DEVICE }.scanState)
            withTimeout(5_000) { discard.await() }

            // The Library scans again, and with no edit waiting that scan completes.
            val rescan = withTimeout(30_000) { catalog.refresh(includeDeviceLibrary = true) {} }
            assertFalse(rescan.yieldedToEdit)
            assertEquals(LibraryScanState.IDLE, rescan.snapshot.sources.single { it.id == LibrarySourceId.DEVICE }.scanState)
        } finally {
            catalog.close()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    /** A device row outside MediaStore's volumes, which a device scan neither lists nor prunes. */
    private fun LibraryCatalogStore.addFixtureTrack(): Long {
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
}
