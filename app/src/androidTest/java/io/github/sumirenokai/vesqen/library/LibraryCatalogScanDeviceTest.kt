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
            // #191 A1: as a resumed pause leaves it, so the edit sees the records the last scans left.
            assertEquals(LibraryScanState.IDLE, store.readSources().single { it.id == LibrarySourceId.DEVICE }.scanState)
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

    @Test
    fun aChoiceMadeWhileAScanRunsTakesEffect() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-catalog-choice-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, databaseName)
        val catalog = AndroidLibraryCatalog(context, store)
        try {
            // #139: two rows of one file key, missed by two completed scans, wait for the user's choice.
            store.ensureDeviceSource()
            store.scan(aira.at(9_001), tide.at(9_002))
            val record = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.title == "Aira" }
            val other = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.title == "Tide" }
            store.setFavorite(record.id, true)
            store.recordPlayback(other.id, playedAtMs = 100)
            store.scan(aira.at(9_003), tide.at(9_004))
            store.scan(aira.at(9_003), tide.at(9_004))
            val chosen = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.title == "Aira" && it.id != record.id }
            assertTrue(chosen.id in store.readUnmatchedRecords()[record.id].orEmpty())

            val scanRunning = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val scan = async(Dispatchers.IO) {
                catalog.refresh(includeDeviceLibrary = true) {
                    if (scanRunning.complete(Unit)) finishScan.await()
                }
            }
            withTimeout(10_000) { scanRunning.await() }
            val assign = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                catalog.assignUnmatchedRecord(record.id, chosen.id)
            }
            finishScan.complete(Unit)
            assertTrue(withTimeout(30_000) { scan.await() }.yieldedToEdit)

            // #191 A1: the stopped scan left its source INTERRUPTED, where no record waits, so the choice
            // changed nothing and the record came back after the next scan.
            assertTrue("The choice takes effect", withTimeout(5_000) { assign.await() })
            val rows = store.readTracks(listOf(LibrarySourceId.DEVICE)).associateBy(AudioTrack::id)
            assertFalse(chosen.id in rows)
            assertEquals(chosen.contentUri, rows.getValue(record.id).contentUri)
            assertTrue(rows.getValue(record.id).isFavorite)
            assertFalse(record.id in store.readUnmatchedRecords())
        } finally {
            catalog.close()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    // A file Android 10 and later lists under a concrete volume. Two of them share a folder name, a file
    // name and a size, so they have one file key: Album A/CD1/01.flac and Album B/CD1/01.flac.
    private val aira = VolumeFile(title = "Aira", album = "Album A")
    private val tide = VolumeFile(title = "Tide", album = "Album B")

    private data class VolumeFile(val title: String, val album: String) {
        fun at(mediaId: Long): LibraryTrackCandidate {
            val contentUri = "content://media/external_primary/audio/media/$mediaId"
            return LibraryTrackCandidate(
                remoteId = mediaStoreRemoteId("external_primary", mediaId),
                contentUri = contentUri,
                title = title,
                artist = "Artist",
                album = album,
                durationMs = if (title == "Aira") 200_000 else 300_000,
                sizeBytes = 10_000,
                fileName = "01.flac",
                folderName = "CD1",
                fingerprint = libraryFingerprint(contentUri, title, album),
            )
        }
    }

    /** A complete device scan of [candidates], which MediaStore lists under new IDs after it was rebuilt. */
    private fun LibraryCatalogStore.scan(vararg candidates: LibraryTrackCandidate) {
        val scan = beginMediaStoreScan(pathIdentifiesFile = false)
        candidates.forEach { candidate ->
            if (scan.firstOffer(candidate) && !markSeenIfUnchanged(scan, candidate)) upsertMediaStoreTrack(scan, candidate)
        }
        completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf("external_primary"))
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
