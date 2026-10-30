package io.github.sumirenokai.vesqen

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySourceId
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.UnmatchedRecordPrompt
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #165 U4: a favorite that finds the catalog corrupt runs in a transaction, which then ends on the
 * database the corruption handler closed and fails with IllegalStateException. The app must not
 * crash; the Library handles it as the rebuild. #191 D9, D18: the rebuild also ends a paused scan and
 * forgets the records put off for later. Run on the isolated deviceTest application, whose catalog
 * this test replaces.
 */
class CatalogRebuildDuringWriteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    private val store = app.libraryCatalogStore
    private val database = app.getDatabasePath("library-catalog.db")
    private val copy = File(database.path + ".corrupt")

    @Test
    fun aFavoriteThatFindsTheCatalogCorruptIsHandledAsTheRebuild() {
        val trackId = corruptCatalog()
        val rebuildsBefore = store.rebuilds.value

        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        try {
            instrumentation.runOnMainSync { viewModel.setFavorite(trackId, true) }
            // Without the fix, the write's IllegalStateException ends this process here. The view model
            // starts loading; only the scan that follows the rebuild finishes it.
            val library = awaitRebuildHandled(viewModel, rebuildsBefore)
            assertEquals(rebuildsBefore + 1, store.rebuilds.value)
            assertFalse("A rebuild is not reported as a failed edit", library.catalogMutationFailed)
            assertEquals(emptyList<AudioTrack>(), library.tracks)
            assertTrue(copy.exists())
            assertTrue(app.libraryRebuildNotice.due.value)
        } finally {
            app.libraryRebuildNotice.markShown()
            SQLiteDatabase.deleteDatabase(copy)
        }
    }

    @Test
    fun aRebuildEndsThePauseAndForgetsTheRecordsPutOffForLater() {
        // Without the music permission, Android 10 and later list only this app's own audio, so the
        // device scan runs on any emulator; Android 9 refuses the query instead.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        corruptCatalog()
        val rebuildsBefore = store.rebuilds.value
        UnmatchedRecordPrompt(app).dismiss(setOf(1L, 2L))

        lateinit var viewModel: VesqenViewModel
        instrumentation.runOnMainSync { viewModel = VesqenViewModel(app) }
        try {
            instrumentation.runOnMainSync {
                viewModel.pauseLibraryScan()
                // The first read meets the damage, and the catalog is rebuilt.
                viewModel.initialisePermissions(musicGranted = true, notificationsGranted = true)
            }
            val library = awaitRebuildHandled(viewModel, rebuildsBefore)

            // #191 D9: the new catalog gives the old record IDs out again.
            assertEquals(emptySet<Long>(), library.dismissedUnmatchedRecordIds)
            assertEquals(emptySet<Long>(), UnmatchedRecordPrompt(app).dismissedRecordIds())
            // #191 D18: the pause was for the old catalog's scan; the new one was scanned, not paused.
            assertEquals(LibraryScanState.IDLE, library.sources.single { it.id == LibrarySourceId.DEVICE }.scanState)
            assertFalse(library.scanProgress?.isPaused == true)
        } finally {
            app.libraryRebuildNotice.markShown()
            SQLiteDatabase.deleteDatabase(copy)
        }
    }

    /** Waits until the view model has handled a rebuild after [rebuildsBefore] and the scan of the new catalog ended. */
    private fun awaitRebuildHandled(viewModel: VesqenViewModel, rebuildsBefore: Int): LibraryUiState {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (true) {
            lateinit var current: LibraryUiState
            instrumentation.runOnMainSync { current = viewModel.uiState.library }
            if (store.rebuilds.value != rebuildsBefore && !current.isLoading) return current
            check(SystemClock.uptimeMillis() < deadline) {
                "The rebuild was not handled: rebuilds ${store.rebuilds.value}, loading ${current.isLoading}"
            }
            Thread.sleep(50)
        }
    }

    /**
     * Replaces the catalog with 600 device rows whose last quarter is damaged, and returns the last
     * row's ID. Closing drops the cached pages, so the next read of the tracks table meets the damage.
     */
    private fun corruptCatalog(): Long {
        store.close()
        SQLiteDatabase.deleteDatabase(database)
        SQLiteDatabase.deleteDatabase(copy)
        val source = store.ensureDeviceSource()
        val session = store.beginSourceScan(source.id)
        repeat(600) { store.upsertTrack(session, candidate(it)) }
        store.finishSourceScan(source.id)
        val trackId = store.readTracks(listOf(source.id)).maxOf(AudioTrack::id)
        store.close()
        val pageSize = 4_096
        val pages = (database.length() / pageSize).toInt()
        RandomAccessFile(database, "rw").use { file ->
            file.seek((pages - pages / 4).toLong() * pageSize)
            file.write(ByteArray(pages / 4 * pageSize) { 0x5A })
        }
        return trackId
    }

    private fun candidate(index: Int) = LibraryTrackCandidate(
        remoteId = "fixture-$index",
        contentUri = "content://fixture/$index",
        title = "Track $index with a title long enough to fill pages",
        artist = "Artist $index",
        album = "Album ${index / 10}",
        durationMs = 60_000,
        fingerprint = "fixture-$index",
    )
}
